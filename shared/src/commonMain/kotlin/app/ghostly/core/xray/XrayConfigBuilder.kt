package app.ghostly.core.xray

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.DnsPreset
import app.ghostly.core.model.RoutingMode
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.LoopbackAuth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** User-facing local proxy (SOCKS5 + HTTP), optionally with login/password. */
data class LocalProxy(
    val socksPort: Int,
    val httpPort: Int,
    val listen: String = "127.0.0.1",
    val user: String? = null,
    val pass: String? = null,
)

/** How the core receives traffic. */
sealed interface Ingress {
    /** Extra user-facing proxy next to the tunnel (null = none). */
    val proxy: LocalProxy?

    /**
     * Loopback SOCKS port for the app's own requests (subscription updates, pings) through the
     * tunnel when the direct route is blocked, guarded by [app.ghostly.core.vpn.LoopbackAuth]. 0 = none.
     */
    val appPort: Int

    /** Android/iOS: the OS hands a TUN fd to the core (env `xray.tun.fd`). */
    data class TunFd(val mtu: Int, override val proxy: LocalProxy? = null, override val appPort: Int = 0) : Ingress

    /** Desktop TUN: the core creates the adapter itself and installs routes (needs admin/root). */
    data class TunSystem(val mtu: Int, val name: String, override val proxy: LocalProxy? = null, override val appPort: Int = 0) : Ingress

    /**
     * Desktop "system proxy": [proxy] is what the user sees; [osHttpPort] is a hidden loopback HTTP
     * port without auth that the OS proxy setting points at (Windows/macOS can't send proxy auth).
     */
    data class Proxy(override val proxy: LocalProxy, val osHttpPort: Int, override val appPort: Int = 0) : Ingress
}

/**
 * Builds the final Xray JSON.
 *
 * A provider config (JSON subscription) is kept as-is — its routing, DNS and balancers are tuned by
 * the provider — and only the ingress and the user's switches are applied on top. A link-based
 * server is wrapped into our own template.
 */
object XrayConfigBuilder {

    const val PROXY = "proxy"
    /** Outbounds that reach a remote server; freedom/blackhole/dns ones can't be measured. */
    private val PING_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "socks", "http", "wireguard")
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val DNS_OUT = "dns-out"

    /** Ghostly's server domains with their addresses (see [dns]). */
    val OWN_HOSTS = mapOf(
        "srv.ghostlinknex.online" to "78.17.1.122",
        "de.ghostlinknex.online" to "179.254.127.78",
    )
    private const val FRAGMENT = "fragment"

    /** [errorLog]: a file for the core's log (Android, where the core has no stdout to read). */
    fun build(server: Server, settings: AppSettings, ingress: Ingress, errorLog: String? = null): JsonObject {
        val base = server.config ?: template(server, settings)
        val cfg = base.toMutableMap()
        cfg.remove("remarks")
        cfg.remove("meta")

        cfg["log"] = buildJsonObject {
            put("loglevel", settings.logLevel)
            if (errorLog != null) { put("error", errorLog); put("access", "none") }
        }
        cfg["inbounds"] = inbounds(ingress, settings)

        var outbounds = (cfg["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { stripMeta(it) }
        outbounds = ensureService(outbounds)
        outbounds = applyMux(outbounds, settings)
        outbounds = applyFragment(outbounds, settings)
        outbounds = applyFastOpen(outbounds, settings)
        cfg["outbounds"] = JsonArray(outbounds)

        cfg["routing"] = routing(cfg["routing"] as? JsonObject, settings, fromProvider = server.config != null)
        cfg["dns"] = dns(cfg["dns"] as? JsonObject, settings)

        cfg["stats"] = JsonObject(emptyMap())
        cfg["policy"] = buildJsonObject {
            putJsonObject("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }
        return JsonObject(cfg)
    }

    /**
     * Config for measuring one server's latency: the proxy outbound first (the core dials through the
     * first outbound when there is no routing), plus whatever it chains through (dialerProxy).
     */
    fun buildPing(server: Server): JsonObject? {
        val outbounds: List<JsonObject> = when {
            server.outbound != null -> listOf(server.outbound)
            server.config != null -> {
                // A balancer config is measured through its main proxy — an estimate, but better than "нет".
                val all = (server.config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val main = all.firstOrNull { tag(it) == PROXY }
                    ?: all.firstOrNull { it["protocol"]?.jsonPrimitive?.contentOrNull in PING_PROTOCOLS }
                    ?: all.firstOrNull() ?: return null
                listOf(main) + all.filter { it !== main }
            }
            else -> return null
        }
        return buildJsonObject {
            putJsonObject("log") { put("loglevel", "none") }
            put("outbounds", JsonArray(outbounds.map { stripMeta(it) }))
        }
    }

    // ------------------------------------------------------------------ template for share links

    private fun template(server: Server, settings: AppSettings): JsonObject = buildJsonObject {
        putJsonArray("outbounds") {
            add(server.outbound ?: error("server without outbound"))
            add(buildJsonObject { put("tag", DIRECT); put("protocol", "freedom") })
            add(buildJsonObject { put("tag", BLOCK); put("protocol", "blackhole") })
            add(buildJsonObject { put("tag", DNS_OUT); put("protocol", "dns") })
        }
        putJsonObject("routing") {
            put("domainStrategy", "IPIfNonMatch")
            putJsonArray("rules") {
                add(rule(port = "53", outbound = DNS_OUT))
                add(rule(ip = listOf("geoip:private"), outbound = DIRECT))
                if (settings.routingMode == RoutingMode.SMART) {
                    add(rule(domain = listOf("geosite:category-ru", "domain:ru", "domain:xn--p1ai", "domain:su"), outbound = DIRECT))
                    add(rule(ip = listOf("geoip:ru"), outbound = DIRECT))
                }
                add(buildJsonObject { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", PROXY) })
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    private fun inbounds(ingress: Ingress, settings: AppSettings): JsonArray = buildJsonArray {
        val sniffing = buildJsonObject {
            put("enabled", settings.sniffing)
            putJsonArray("destOverride") { listOf("http", "tls", "quic").forEach { add(JsonPrimitive(it)) } }
            // Sniffed domains are used for routing only; the connection still goes to the real IP.
            put("routeOnly", true)
        }
        fun socks(tag: String, listen: String, port: Int, user: String?, pass: String?) = buildJsonObject {
            put("tag", tag)
            put("protocol", "socks")
            put("listen", listen)
            put("port", port)
            putJsonObject("settings") {
                put("udp", true)
                if (user != null && pass != null) {
                    put("auth", "password")
                    putJsonArray("accounts") { add(buildJsonObject { put("user", user); put("pass", pass) }) }
                } else put("auth", "noauth")
            }
            put("sniffing", sniffing)
        }
        fun http(tag: String, listen: String, port: Int, user: String?, pass: String?) = buildJsonObject {
            put("tag", tag)
            put("protocol", "http")
            put("listen", listen)
            put("port", port)
            if (user != null && pass != null) putJsonObject("settings") {
                putJsonArray("accounts") { add(buildJsonObject { put("user", user); put("pass", pass) }) }
            }
            put("sniffing", sniffing)
        }
        when (ingress) {
            is Ingress.TunFd -> add(buildJsonObject {
                put("tag", "tun-in")
                put("port", 0)
                put("protocol", "tun")
                putJsonObject("settings") {
                    put("name", "xray0")
                    put("mtu", ingress.mtu)
                }
                put("sniffing", sniffing)
            })
            is Ingress.TunSystem -> add(buildJsonObject {
                put("tag", "tun-in")
                put("port", 0)
                put("protocol", "tun")
                putJsonObject("settings") {
                    put("name", ingress.name)
                    put("mtu", ingress.mtu)
                    putJsonArray("gateway") { add(JsonPrimitive("172.19.0.1/30")); if (settings.ipv6) add(JsonPrimitive("fdfe:dcba:9876::1/126")) }
                    putJsonArray("dns") { add(JsonPrimitive("172.19.0.2")) }
                    putJsonArray("autoSystemRoutingTable") {
                        add(JsonPrimitive("0.0.0.0/0"))
                        if (settings.ipv6) add(JsonPrimitive("::/0"))
                    }
                }
                put("sniffing", sniffing)
            })
            is Ingress.Proxy -> if (ingress.osHttpPort > 0) add(http("os-http-in", "127.0.0.1", ingress.osHttpPort, null, null))
        }
        ingress.proxy?.let { p ->
            add(socks("socks-in", p.listen, p.socksPort, p.user, p.pass))
            add(http("http-in", p.listen, p.httpPort, p.user, p.pass))
        }
        if (ingress.appPort > 0) add(socks("app-in", "127.0.0.1", ingress.appPort, LoopbackAuth.user, LoopbackAuth.pass))
    }

    /** Loopback SOCKS inbound of a throwaway ping core, behind [LoopbackAuth] like `app-in`. */
    fun pingInbound(port: Int, tag: String? = null): JsonObject = buildJsonObject {
        if (tag != null) put("tag", tag)
        put("listen", "127.0.0.1")
        put("port", port)
        put("protocol", "socks")
        putJsonObject("settings") {
            put("udp", false)
            put("auth", "password")
            putJsonArray("accounts") { add(buildJsonObject { put("user", LoopbackAuth.user); put("pass", LoopbackAuth.pass) }) }
        }
    }

    /** The user's local proxy from settings (auth applied when enabled). */
    fun localProxy(settings: AppSettings): LocalProxy = LocalProxy(
        socksPort = settings.socksPort,
        httpPort = settings.httpPort,
        listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
        user = settings.proxyUser.takeIf { settings.proxyAuth && it.isNotBlank() },
        pass = settings.proxyPass.takeIf { settings.proxyAuth && settings.proxyUser.isNotBlank() },
    )

    /** Make sure direct/block/dns-out exist, whatever the provider sent. */
    private fun ensureService(outbounds: List<JsonObject>): List<JsonObject> {
        val tags = outbounds.map { tag(it) }.toSet()
        val extra = buildList {
            if (DIRECT !in tags) add(buildJsonObject { put("tag", DIRECT); put("protocol", "freedom") })
            if (BLOCK !in tags) add(buildJsonObject { put("tag", BLOCK); put("protocol", "blackhole") })
            if (DNS_OUT !in tags) add(buildJsonObject { put("tag", DNS_OUT); put("protocol", "dns") })
        }
        return outbounds + extra
    }

    private fun applyMux(outbounds: List<JsonObject>, settings: AppSettings): List<JsonObject> {
        if (!settings.mux) return outbounds
        return outbounds.map { ob ->
            val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull
            val net = (ob["streamSettings"] as? JsonObject)?.get("network")?.jsonPrimitive?.contentOrNull ?: "tcp"
            val hasFlow = ob.toString().contains("\"flow\":\"xtls")
            val muxable = protocol in setOf("vless", "vmess", "trojan", "shadowsocks") &&
                net !in setOf("xhttp", "splithttp", "hysteria") && !hasFlow && "mux" !in ob
            if (!muxable) ob
            else JsonObject(ob + ("mux" to buildJsonObject { put("enabled", true); put("concurrency", 8); put("xudpConcurrency", 16) }))
        }
    }

    private fun applyFastOpen(outbounds: List<JsonObject>, settings: AppSettings): List<JsonObject> {
        if (!settings.tcpFastOpen) return outbounds
        return outbounds.map { ob ->
            val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull
            val stream = ob["streamSettings"] as? JsonObject
            val net = stream?.get("network")?.jsonPrimitive?.contentOrNull
            if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks") || net in setOf("hysteria", "kcp", "quic")) return@map ob
            val sockopt = (stream?.get("sockopt") as? JsonObject).orEmpty()
            if ("tcpFastOpen" in sockopt) return@map ob
            val newStream = JsonObject(stream.orEmpty() + ("sockopt" to JsonObject(sockopt + ("tcpFastOpen" to JsonPrimitive(true)))))
            JsonObject(ob + ("streamSettings" to newStream))
        }
    }

    /** TLS ClientHello fragmentation against DPI: TLS proxies dial through a fragmenting freedom. */
    private fun applyFragment(outbounds: List<JsonObject>, settings: AppSettings): List<JsonObject> {
        if (!settings.fragment) return outbounds
        var used = false
        val mapped = outbounds.map { ob ->
            val stream = ob["streamSettings"] as? JsonObject ?: return@map ob
            val sec = stream["security"]?.jsonPrimitive?.contentOrNull
            val net = stream["network"]?.jsonPrimitive?.contentOrNull
            val sockopt = stream["sockopt"] as? JsonObject
            if (sec != "tls" || net == "hysteria" || sockopt?.get("dialerProxy") != null) return@map ob
            used = true
            val newSockopt = JsonObject(sockopt.orEmpty() + ("dialerProxy" to JsonPrimitive(FRAGMENT)))
            JsonObject(ob + ("streamSettings" to JsonObject(stream + ("sockopt" to newSockopt))))
        }
        if (!used) return outbounds
        return mapped + buildJsonObject {
            put("tag", FRAGMENT)
            put("protocol", "freedom")
            putJsonObject("settings") {
                putJsonObject("fragment") {
                    put("packets", "tlshello")
                    put("length", "100-200")
                    put("interval", "10-20")
                }
            }
        }
    }

    private fun routing(original: JsonObject?, settings: AppSettings, fromProvider: Boolean): JsonObject {
        val src = original ?: buildJsonObject { }
        var rules = (src["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        if (settings.routingMode == RoutingMode.GLOBAL && fromProvider) {
            // Drop provider "direct" shortcuts except LAN/DNS, everything else goes through the tunnel.
            rules = rules.filter { r ->
                val out = r["outboundTag"]?.jsonPrimitive?.contentOrNull
                out != DIRECT || r.toString().contains("geoip:private")
            }
        }

        val userRules = buildList {
            if (settings.blockDomains.isNotEmpty()) add(rule(domain = settings.blockDomains.map(::domainRule), outbound = BLOCK))
            if (settings.blockAds) add(rule(domain = listOf("geosite:category-ads-all"), outbound = BLOCK))
            if (settings.blockQuic) add(buildJsonObject {
                put("type", "field"); put("network", "udp"); put("port", "443"); put("outboundTag", BLOCK)
            })
            if (settings.directDomains.isNotEmpty()) add(rule(domain = settings.directDomains.map(::domainRule), outbound = DIRECT))
            if (settings.proxyDomains.isNotEmpty()) {
                // Forced proxy: reuse whatever the catch-all rule points at (a balancer or "proxy").
                val catchAll = rules.lastOrNull { it["domain"] == null && it["ip"] == null && it["port"] == null }
                val target = catchAll?.filterKeys { it == "outboundTag" || it == "balancerTag" }
                    ?.takeIf { it.isNotEmpty() } ?: mapOf("outboundTag" to JsonPrimitive(PROXY))
                add(JsonObject(mapOf("type" to JsonPrimitive("field"), "domain" to JsonArray(settings.proxyDomains.map { JsonPrimitive(domainRule(it)) })) + target))
            }
        }
        // DNS hijack must stay first so port-53 packets never reach other rules.
        val (dnsRules, rest) = rules.partition { it["port"]?.jsonPrimitive?.contentOrNull == "53" }
        val out = src.toMutableMap()
        if (out["domainStrategy"] == null) out["domainStrategy"] = JsonPrimitive("IPIfNonMatch")
        out["rules"] = JsonArray(dnsRules + userRules + rest)
        return JsonObject(out)
    }

    /**
     * DNS: queries of the device are hijacked into the core (port 53 -> dns-out) and answered by these servers.
     * The remote one is reached through the tunnel (routing sends its IP into the proxy), so the TSPU can't
     * swap its answers; Russian domains may go to the "direct" one (+local = sent straight out).
     * A provider's JSON keeps its own DNS while the user hasn't changed any DNS option.
     */
    private fun dns(original: JsonObject?, settings: AppSettings): JsonObject {
        val src = original?.toMutableMap() ?: mutableMapOf()
        val keepProvider = original != null && settings.dnsDefaults && src["servers"] != null
        if (!keepProvider) {
            val remote = when (settings.dns) {
                DnsPreset.PROVIDER -> null
                DnsPreset.CUSTOM -> settings.customDns.trim().takeIf { it.isNotEmpty() }
                else -> settings.dns.address
            } ?: "https://1.1.1.1/dns-query"
            // The provider's own split entries (objects with "domains") stay, e.g. special resolvers for some sites.
            val providerSplit = (src["servers"] as? JsonArray).orEmpty().filter { it is JsonObject && "domains" in it }
            val servers = buildList {
                val direct = settings.directDnsAddress()
                if (settings.routingMode == RoutingMode.SMART && settings.dnsSplitRu && direct != null) add(buildJsonObject {
                    put("address", direct)
                    putJsonArray("domains") {
                        listOf("geosite:category-ru", "domain:ru", "domain:xn--p1ai", "domain:su").forEach { add(JsonPrimitive(it)) }
                    }
                    put("skipFallback", true)
                })
                addAll(providerSplit)
                add(JsonPrimitive(remote))
                add(JsonPrimitive("8.8.8.8"))
            }
            src["servers"] = JsonArray(servers)
        }
        src["queryStrategy"] = JsonPrimitive(settings.dnsStrategy.xray ?: if (settings.ipv6) "UseIP" else "UseIPv4")
        if (!settings.dnsCache) src["disableCache"] = JsonPrimitive(true)
        // Static answers: the user's lines, then Ghostly's own servers (a spoofed or blocked DNS answer
        // must not stop the connect). Provider hosts win over ours, the user's win over everything.
        val hosts = (src["hosts"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        OWN_HOSTS.forEach { (host, ip) -> if (host !in hosts) hosts[host] = JsonPrimitive(ip) }
        settings.hostsMap().forEach { (host, ip) -> hosts[host] = JsonPrimitive(ip) }
        src["hosts"] = JsonObject(hosts)
        return JsonObject(src)
    }

    // ------------------------------------------------------------------ helpers

    private fun rule(domain: List<String>? = null, ip: List<String>? = null, port: String? = null, outbound: String): JsonObject =
        buildJsonObject {
            put("type", "field")
            domain?.let { d -> putJsonArray("domain") { d.forEach { add(JsonPrimitive(it)) } } }
            ip?.let { i -> putJsonArray("ip") { i.forEach { add(JsonPrimitive(it)) } } }
            port?.let { put("port", it) }
            put("outboundTag", outbound)
        }

    /** "youtube.com" → "domain:youtube.com"; prefixed entries (geosite:, full:, regexp:) pass through. */
    private fun domainRule(entry: String): String {
        val e = entry.trim()
        return if (':' in e) e else "domain:$e"
    }

    private fun tag(ob: JsonObject) = ob["tag"]?.jsonPrimitive?.contentOrNull

    private fun stripMeta(ob: JsonObject): JsonObject = if ("meta" in ob) JsonObject(ob - "meta") else ob

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
