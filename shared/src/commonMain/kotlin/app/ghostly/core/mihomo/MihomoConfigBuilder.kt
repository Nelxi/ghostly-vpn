package app.ghostly.core.mihomo

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.DnsPreset
import app.ghostly.core.model.Profile
import app.ghostly.core.model.RoutingMode
import app.ghostly.core.model.Server
import app.ghostly.core.xray.LocalProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How mihomo receives traffic and how we talk to it. */
data class MihomoIngress(
    /** REST API (groups, selectors, traffic) on 127.0.0.1. */
    val controllerPort: Int,
    val secret: String,
    /** Loopback mixed port without auth for the app's own requests; 0 = none. */
    val appPort: Int = 0,
    /** User-facing SOCKS5 + HTTP (with auth when set). */
    val proxy: LocalProxy? = null,
    /** Desktop system proxy: loopback HTTP without auth the OS setting points at; 0 = none. */
    val osHttpPort: Int = 0,
    /** Desktop TUN (the core creates the adapter); null on Android, where the VpnService owns the TUN. */
    val tun: Tun? = null,
) {
    data class Tun(val name: String, val mtu: Int)
}

/** Selecting [choice] in [group] after the core is up; [providerIndex] picks a proxy of a provider by position. */
data class MihomoPick(val group: String, val choice: String? = null, val provider: String? = null, val providerIndex: Int = -1)

/** A ready config plus the links file for link-based profiles and the selection to apply after start. */
data class MihomoPlan(val config: JsonObject, val links: String?, val picks: List<MihomoPick>)

/**
 * Builds the mihomo config.
 *
 * A Clash/mihomo subscription is kept as the provider made it (groups, rules, DNS) — only the
 * listeners, the controller, TUN and the user's own rules are put on top. Plain links get our own
 * template: a provider with every server of the profile, groups «Ghostly» (manual pick),
 * «Авто» (fastest) and «Резерв» (first alive), and the smart Russian routing.
 */
object MihomoConfigBuilder {

    const val MAIN_GROUP = "Ghostly"
    const val AUTO_GROUP = "⚡ Авто"
    const val FALLBACK_GROUP = "🛟 Резерв"
    const val LINKS_PROVIDER = "ghostly"
    const val LINKS_FILE = "ghostly-links.txt"

    /** Does this server run on mihomo with these settings? */
    fun wants(server: Server, profile: Profile?, settings: AppSettings): Boolean = when {
        server.mihomo != null || profile?.mihomo != null -> true
        server.config != null -> false
        else -> settings.core == CoreType.MIHOMO && server.link != null
    }

    fun build(
        server: Server,
        profile: Profile?,
        settings: AppSettings,
        ingress: MihomoIngress,
        linksPath: String = LINKS_FILE,
        /** The user's saved selector choices (group → member): they win over the selected server. */
        stored: Map<String, String> = emptyMap(),
    ): MihomoPlan {
        val provided = profile?.mihomo
        return if (provided != null) fromProvider(provided, server, settings, ingress, stored)
        else fromLinks(server, profile, settings, ingress, linksPath, stored)
    }

    /** In-place switch to [server] inside the running profile (no reconnect), or null when impossible. */
    fun picksFor(server: Server, profile: Profile?): List<MihomoPick>? {
        val provided = profile?.mihomo
        return when {
            provided != null && server.mihomo != null ->
                MihomoProfiles.selectPath(provided, server.name).map { (g, c) -> MihomoPick(g, c) }.ifEmpty { null }
            provided == null && server.link != null && profile != null -> {
                val index = profile.servers.filter { it.link != null && it.config == null }.indexOfFirst { it.id == server.id }
                if (index < 0) null else listOf(MihomoPick(MAIN_GROUP, provider = LINKS_PROVIDER, providerIndex = index))
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ provider config

    private val DROP_KEYS = setOf(
        "port", "socks-port", "redir-port", "tproxy-port", "mixed-port", "authentication", "listeners",
        "external-controller", "external-controller-tls", "external-controller-unix", "external-controller-pipe",
        "external-ui", "external-ui-url", "external-ui-name", "secret", "tun", "interface-name", "routing-mark",
        "allow-lan", "bind-address", "lan-allowed-ips", "lan-disallowed-ips",
    )

    private fun fromProvider(provided: JsonObject, server: Server, settings: AppSettings, ingress: MihomoIngress, stored: Map<String, String>): MihomoPlan {
        val cfg = provided.filterKeys { it !in DROP_KEYS }.toMutableMap()
        common(cfg, settings, ingress)
        if (cfg["dns"] == null || (cfg["dns"] as? JsonObject)?.get("enable")?.bool() != true || !settings.dnsDefaults) {
            // the provider's DNS is kept only while the user hasn't touched any DNS option
            cfg["dns"] = defaultDns(settings)
        }
        cfg["hosts"] = hosts(cfg["hosts"] as? JsonObject, settings)
        if (cfg["sniffer"] == null && settings.sniffing) cfg["sniffer"] = sniffer()
        if (cfg["geox-url"] == null) cfg["geox-url"] = geoxMirrors()

        var rules = (cfg["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val target = MihomoProfiles.matchTarget(provided) ?: MihomoProfiles.groups(provided).keys.firstOrNull() ?: "DIRECT"
        if (settings.routingMode == RoutingMode.GLOBAL) {
            // Drop the provider's "direct" shortcuts except LAN, like the Xray side does.
            rules = rules.filter { r -> !isDirect(r) || isLan(r) }
        }
        cfg["rules"] = strings(userRules(settings, target) + rules)

        // The user's saved selector choices win; the selected server only fills groups they never touched.
        val groups = MihomoProfiles.groups(provided)
        val storedPicks = stored.mapNotNull { (g, c) ->
            val members = groups[g]?.takeIf { it.type == "select" }?.members ?: return@mapNotNull null
            if (c in members) MihomoPick(g, c) else null
        }
        val derived = if (server.mihomo != null) MihomoProfiles.selectPath(provided, server.name).map { (g, c) -> MihomoPick(g, c) } else emptyList()
        val picks = derived.filter { d -> storedPicks.none { it.group == d.group } } + storedPicks
        return MihomoPlan(JsonObject(cfg), links = null, picks = picks)
    }

    // ------------------------------------------------------------------ link template

    private fun fromLinks(server: Server, profile: Profile?, settings: AppSettings, ingress: MihomoIngress, linksPath: String, stored: Map<String, String>): MihomoPlan {
        val linkServers = (profile?.servers ?: listOf(server)).filter { it.link != null && it.config == null }
            .ifEmpty { listOf(server) }
        val links = linkServers.mapNotNull { it.link }.joinToString("\n")
        // A saved «Ghostly» choice (a server, «Авто» or «Резерв») wins over the selected server.
        val storedChoice = stored[MAIN_GROUP]
        val storedIndex = if (storedChoice == null) -1 else linkServers.indexOfFirst { it.name == storedChoice }
        val index = if (storedIndex >= 0) storedIndex else linkServers.indexOfFirst { it.id == server.id }.coerceAtLeast(0)

        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["mode"] = JsonPrimitive("rule")
        cfg["unified-delay"] = JsonPrimitive(true)
        cfg["tcp-concurrent"] = JsonPrimitive(true)
        cfg["geodata-mode"] = JsonPrimitive(true)
        cfg["geo-auto-update"] = JsonPrimitive(false)
        cfg["geox-url"] = geoxMirrors()
        common(cfg, settings, ingress)
        cfg["dns"] = defaultDns(settings)
        cfg["hosts"] = hosts(null, settings)
        if (settings.sniffing) cfg["sniffer"] = sniffer()

        val healthCheck = buildJsonObject {
            put("enable", true)
            put("url", settings.mihomoPingUrl)
            put("interval", 600)
            put("lazy", true)
        }
        cfg["proxy-providers"] = buildJsonObject {
            putJsonObject(LINKS_PROVIDER) {
                put("type", "file")
                put("path", linksPath)
                put("health-check", healthCheck)
            }
        }
        cfg["proxy-groups"] = buildJsonArray {
            add(buildJsonObject {
                put("name", MAIN_GROUP)
                put("type", "select")
                putJsonArray("proxies") { add(JsonPrimitive(AUTO_GROUP)); add(JsonPrimitive(FALLBACK_GROUP)) }
                putJsonArray("use") { add(JsonPrimitive(LINKS_PROVIDER)) }
            })
            add(buildJsonObject {
                put("name", AUTO_GROUP)
                put("type", "url-test")
                putJsonArray("use") { add(JsonPrimitive(LINKS_PROVIDER)) }
                put("url", settings.mihomoPingUrl)
                put("interval", 300)
                put("tolerance", 50)
                put("lazy", true)
            })
            add(buildJsonObject {
                put("name", FALLBACK_GROUP)
                put("type", "fallback")
                putJsonArray("use") { add(JsonPrimitive(LINKS_PROVIDER)) }
                put("url", settings.mihomoPingUrl)
                put("interval", 300)
                put("lazy", true)
            })
        }
        val rules = buildList {
            addAll(userRules(settings, MAIN_GROUP))
            addAll(LAN_RULES)
            if (settings.routingMode == RoutingMode.SMART) {
                add("GEOSITE,category-ru,DIRECT")
                add("DOMAIN-SUFFIX,ru,DIRECT")
                add("DOMAIN-SUFFIX,xn--p1ai,DIRECT")
                add("DOMAIN-SUFFIX,su,DIRECT")
                add("GEOIP,ru,DIRECT")
            }
            add("MATCH,$MAIN_GROUP")
        }
        cfg["rules"] = strings(rules)
        val picks = when (storedChoice) {
            AUTO_GROUP, FALLBACK_GROUP -> listOf(MihomoPick(MAIN_GROUP, choice = storedChoice))
            else -> listOf(MihomoPick(MAIN_GROUP, provider = LINKS_PROVIDER, providerIndex = index))
        }
        return MihomoPlan(JsonObject(cfg), links, picks)
    }

    // ------------------------------------------------------------------ pieces

    /** Listeners, controller, logging, TUN — the same for both kinds of config. */
    private fun common(cfg: MutableMap<String, JsonElement>, settings: AppSettings, ingress: MihomoIngress) {
        cfg["allow-lan"] = JsonPrimitive(settings.allowLan)
        cfg["bind-address"] = JsonPrimitive("*")
        cfg["ipv6"] = JsonPrimitive(settings.ipv6)
        cfg["log-level"] = JsonPrimitive(
            when (settings.mihomoLogLevel) {
                "none" -> "silent"
                "debug", "info", "warning", "error" -> settings.mihomoLogLevel
                else -> "warning"
            },
        )
        cfg["external-controller"] = JsonPrimitive("127.0.0.1:${ingress.controllerPort}")
        cfg["secret"] = JsonPrimitive(ingress.secret)
        if (cfg["profile"] == null) cfg["profile"] = buildJsonObject { put("store-selected", false); put("store-fake-ip", true) }

        cfg["listeners"] = buildJsonArray {
            if (ingress.appPort > 0) add(listener("app-in", "mixed", "127.0.0.1", ingress.appPort, null))
            if (ingress.osHttpPort > 0) add(listener("os-http-in", "http", "127.0.0.1", ingress.osHttpPort, null))
            ingress.proxy?.let { p ->
                add(listener("socks-in", "socks", p.listen, p.socksPort, p))
                add(listener("http-in", "http", p.listen, p.httpPort, p))
            }
        }
        cfg["tun"] = ingress.tun?.let { t ->
            buildJsonObject {
                put("enable", true)
                put("stack", "mixed")
                put("device", t.name)
                put("mtu", t.mtu)
                put("auto-route", true)
                put("auto-detect-interface", true)
                put("strict-route", false)
                putJsonArray("dns-hijack") { add(JsonPrimitive("any:53")); add(JsonPrimitive("tcp://any:53")) }
            }
        } ?: buildJsonObject { put("enable", false) }
    }

    private fun listener(name: String, type: String, listen: String, port: Int, auth: LocalProxy?) = buildJsonObject {
        put("name", name)
        put("type", type)
        put("listen", listen)
        put("port", port)
        if (type != "http") put("udp", true)
        if (auth?.user != null && auth.pass != null) {
            putJsonArray("users") { add(buildJsonObject { put("username", auth.user); put("password", auth.pass) }) }
        }
    }

    private fun chosenDns(settings: AppSettings): String? = when (settings.dns) {
        DnsPreset.PROVIDER -> null
        DnsPreset.CUSTOM -> settings.customDns.trim().takeIf { it.isNotEmpty() }
        else -> settings.dns.address
    }

    private fun defaultDns(settings: AppSettings): JsonObject = buildJsonObject {
        val v6 = when (settings.dnsStrategy) {
            app.ghostly.core.model.DnsStrategy.AUTO -> settings.ipv6
            app.ghostly.core.model.DnsStrategy.IPV4 -> false
            else -> true
        }
        val boot = settings.dnsBootstrap.trim().ifEmpty { "77.88.8.8" }
        put("enable", true)
        put("ipv6", v6)
        put("cache-algorithm", "arc")
        if (!settings.dnsCache) put("cache", false)
        put("use-hosts", true)
        if (settings.dnsFakeIp) {
            put("enhanced-mode", "fake-ip")
            put("fake-ip-range", "198.18.0.1/16")
            put("fake-ip-filter", strings(listOf("*.lan", "*.local", "+.msftconnecttest.com", "+.msftncsi.com", "+.stun.*.*", "+.stun.*.*.*", "time.*.com", "ntp.*.com", "+.market.xiaomi.com")))
        } else {
            put("enhanced-mode", "redir-host")
        }
        put("respect-rules", true)
        put("default-nameserver", strings(listOf(boot, "1.1.1.1")))
        put("proxy-server-nameserver", strings(listOf("https://$boot/dns-query", "https://1.1.1.1/dns-query")))
        put("nameserver", strings(listOf(chosenDns(settings) ?: "https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")))
        val direct = when (settings.dnsDirect) {
            app.ghostly.core.model.DirectDns.YANDEX -> "https://77.88.8.8/dns-query"
            app.ghostly.core.model.DirectDns.SYSTEM -> "system"
            app.ghostly.core.model.DirectDns.CUSTOM -> settings.dnsDirectCustom.trim().removePrefix("https+local://").let {
                if (it.startsWith("http") || it.contains("://")) it else it.ifEmpty { null }
            }
        }
        if (settings.routingMode == RoutingMode.SMART && settings.dnsSplitRu && direct != null) {
            putJsonObject("nameserver-policy") { put("geosite:category-ru", strings(listOf(direct))) }
        }
    }

    /** Static hosts: the user's lines and Ghostly's own servers (see XrayConfigBuilder.OWN_HOSTS). */
    private fun hosts(original: JsonObject?, settings: AppSettings): JsonObject {
        val out = original?.toMutableMap() ?: mutableMapOf()
        app.ghostly.core.xray.XrayConfigBuilder.OWN_HOSTS.forEach { (h, ip) -> if (h !in out) out[h] = JsonPrimitive(ip) }
        settings.hostsMap().forEach { (h, ip) -> out[h] = JsonPrimitive(ip) }
        return JsonObject(out)
    }

    private fun sniffer() = buildJsonObject {
        put("enable", true)
        put("parse-pure-ip", true)
        putJsonObject("sniff") {
            putJsonObject("HTTP") { put("ports", strings(listOf("80", "8080-8880"))); put("override-destination", true) }
            putJsonObject("TLS") { put("ports", strings(listOf("443", "8443"))) }
            putJsonObject("QUIC") { put("ports", strings(listOf("443", "8443"))) }
        }
    }

    /** GitHub is often slow or blocked here; the jsDelivr mirror of the same files is not. */
    private fun geoxMirrors() = buildJsonObject {
        val base = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release"
        put("geoip", "$base/geoip.dat")
        put("geosite", "$base/geosite.dat")
        put("mmdb", "$base/country.mmdb")
        put("asn", "$base/GeoLite2-ASN.mmdb")
    }

    private val LAN_RULES = listOf(
        "DOMAIN-SUFFIX,local,DIRECT",
        "DOMAIN-SUFFIX,lan,DIRECT",
        "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
        "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
        "IP-CIDR,169.254.0.0/16,DIRECT,no-resolve",
        "IP-CIDR6,fc00::/7,DIRECT,no-resolve",
        "IP-CIDR6,fe80::/10,DIRECT,no-resolve",
    )

    /** The user's switches and domain lists as mihomo rules; forced-proxy entries go to [proxyTarget]. */
    private fun userRules(settings: AppSettings, proxyTarget: String): List<String> = buildList {
        settings.blockDomains.mapNotNull { domainRule(it, "REJECT") }.let(::addAll)
        if (settings.blockAds) add("GEOSITE,category-ads-all,REJECT")
        if (settings.blockQuic) add("AND,((NETWORK,UDP),(DST-PORT,443)),REJECT")
        settings.directDomains.mapNotNull { domainRule(it, "DIRECT") }.let(::addAll)
        settings.proxyDomains.mapNotNull { domainRule(it, proxyTarget) }.let(::addAll)
    }

    /** Our domain syntax (Xray style) → a mihomo rule. */
    private fun domainRule(entry: String, target: String): String? {
        val e = entry.trim().takeIf { it.isNotEmpty() } ?: return null
        val value = e.substringAfter(':')
        return when (e.substringBefore(':', "").lowercase()) {
            "" -> "DOMAIN-SUFFIX,$e,$target"
            "domain" -> "DOMAIN-SUFFIX,$value,$target"
            "full" -> "DOMAIN,$value,$target"
            "keyword" -> "DOMAIN-KEYWORD,$value,$target"
            "regexp" -> "DOMAIN-REGEX,$value,$target"
            "geosite" -> "GEOSITE,$value,$target"
            "geoip" -> "GEOIP,$value,$target,no-resolve"
            else -> null
        }
    }

    private fun isDirect(rule: String): Boolean {
        val parts = rule.split(',').map { it.trim() }
        val target = if (parts.lastOrNull().equals("no-resolve", true)) parts.getOrNull(parts.size - 2) else parts.lastOrNull()
        return target.equals("DIRECT", true)
    }

    private fun isLan(rule: String): Boolean {
        val r = rule.lowercase()
        return "private" in r || "lan" in r || "10.0.0.0/8" in r || "192.168." in r || "172.16." in r || "127.0.0.0" in r
    }

    private fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
