package app.ghostly.core.link

import app.ghostly.core.JsonX
import app.ghostly.core.model.Server
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Share links → Xray proxy outbounds (tag "proxy").
 * Xray: vless://, vmess://, trojan://, ss://, hysteria2:// / hy2://, wireguard:// / wg://.
 * mihomo only (no Xray outbound, the link is handed to mihomo as is): tuic://, anytls://, mierus:// (mieru).
 */
object LinkParser {

    val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "wireguard", "wg", "tuic", "anytls", "mierus", "mieru")

    /** Protocols only mihomo runs: such servers have a link but no Xray outbound. */
    val MIHOMO_ONLY = setOf("tuic", "anytls", "mieru")

    /** Protocols only Xray runs (mihomo's link parser has no WireGuard links). */
    val XRAY_ONLY = setOf("wireguard")

    fun looksLikeLink(line: String): Boolean {
        val l = line.trim().lowercase()
        return SCHEMES.any { l.startsWith("$it://") }
    }

    fun parse(link: String, id: String): Server? = runCatching {
        when (link.trim().substringBefore("://").lowercase()) {
            "vless" -> vless(link, id)
            "vmess" -> vmess(link, id)
            "trojan" -> trojan(link, id)
            "ss" -> shadowsocks(link, id)
            "hysteria2", "hy2" -> hysteria2(link, id)
            "wireguard", "wg" -> wireguard(link, id)
            "tuic" -> mihomoOnly(link, id, "tuic", "quic")
            "anytls" -> mihomoOnly(link, id, "anytls", "tcp")
            // mieru's share scheme is mierus://; accept the short form too
            "mierus", "mieru" -> mihomoOnly("mierus://" + link.trim().substringAfter("://"), id, "mieru", "tcp")
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- vless

    private fun vless(link: String, id: String): Server? {
        val u = ShareUri.parse(link) ?: return null
        val uuid = u.userInfo ?: return null
        val net = normalizeNet(u.q("type") ?: "tcp")
        val security = u.q("security") ?: "none"
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        putJsonArray("users") {
                            add(buildJsonObject {
                                put("id", uuid)
                                put("encryption", u.q("encryption") ?: "none")
                                u.q("flow")?.let { put("flow", it) }
                            })
                        }
                    })
                }
            }
            put("streamSettings", stream(net, security, u.query, u.host))
        }
        return Server(
            id = id,
            name = u.fragment?.takeIf { it.isNotBlank() } ?: "${u.host}:${u.port}",
            protocol = "vless", transport = net, security = security,
            host = u.host, port = u.port, outbound = outbound, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- trojan

    private fun trojan(link: String, id: String): Server? {
        val u = ShareUri.parse(link) ?: return null
        val password = u.userInfo ?: return null
        val net = normalizeNet(u.q("type") ?: "tcp")
        val security = u.q("security") ?: "tls"
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "trojan")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        put("password", password)
                    })
                }
            }
            put("streamSettings", stream(net, security, u.query, u.host))
        }
        return Server(
            id = id, name = u.fragment?.takeIf { it.isNotBlank() } ?: "${u.host}:${u.port}",
            protocol = "trojan", transport = net, security = security,
            host = u.host, port = u.port, outbound = outbound, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- vmess

    private fun vmess(link: String, id: String): Server? {
        val body = link.trim().substringAfter("://").substringBefore('#')
        val json = decodeBase64Lenient(body)?.let { JsonX.parseToJsonElement(it).jsonObject } ?: return null
        fun s(key: String) = json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val host = s("add") ?: return null
        val port = json["port"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return null
        val uuid = s("id") ?: return null
        val net = normalizeNet(s("net") ?: "tcp")
        val security = if (s("tls") == "tls") "tls" else if (s("tls") == "reality") "reality" else "none"
        val query = buildMap {
            s("host")?.let { put("host", it) }
            s("path")?.let { put("path", it) }
            s("type")?.let { put("headerType", it); if (net == "grpc") put("mode", it) }
            s("sni")?.let { put("sni", it) }
            s("alpn")?.let { put("alpn", it) }
            s("fp")?.let { put("fp", it) }
            if (net == "grpc") s("path")?.let { put("serviceName", it) }
        }
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vmess")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        putJsonArray("users") {
                            add(buildJsonObject {
                                put("id", uuid)
                                put("alterId", s("aid")?.toIntOrNull() ?: 0)
                                put("security", s("scy") ?: "auto")
                            })
                        }
                    })
                }
            }
            put("streamSettings", stream(net, security, query, host))
        }
        return Server(
            id = id, name = s("ps") ?: "$host:$port",
            protocol = "vmess", transport = net, security = security,
            host = host, port = port, outbound = outbound, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- shadowsocks

    private fun shadowsocks(link: String, id: String): Server? {
        val trimmed = link.trim()
        val name = trimmed.substringAfter('#', "").let { percentDecode(it) }
        var body = trimmed.substringAfter("://").substringBefore('#').substringBefore('?')
        // Legacy form: ss://base64(method:password@host:port)
        if ('@' !in body) body = decodeBase64Lenient(body) ?: return null
        val userPart = body.substringBeforeLast('@')
        val hostPart = body.substringAfterLast('@').trimEnd('/')
        val creds = if (':' in percentDecode(userPart)) percentDecode(userPart) else decodeBase64Lenient(userPart) ?: return null
        val method = creds.substringBefore(':')
        val password = creds.substringAfter(':')
        val host = hostPart.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hostPart.substringAfterLast(':').toIntOrNull() ?: return null
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                    })
                }
            }
        }
        return Server(
            id = id, name = name.ifBlank { "$host:$port" },
            protocol = "shadowsocks", transport = "tcp", security = method,
            host = host, port = port, outbound = outbound, link = trimmed,
        )
    }

    // ---------------------------------------------------------------- hysteria2

    private fun hysteria2(link: String, id: String): Server? {
        val u = ShareUri.parse(link) ?: return null
        // Salamander is the only obfuscation Hysteria2 has; Xray applies it as a UDP "finalmask".
        val obfs = u.q("obfs")?.takeIf { it != "none" }
        if (obfs != null && obfs != "salamander") return null
        val obfsPassword = u.q("obfs-password")
        if (obfs != null && obfsPassword.isNullOrEmpty()) return null
        val auth = u.userInfo ?: ""
        val sni = u.q("sni") ?: u.host
        val pin = u.q("pinSHA256")?.replace(":", "")?.lowercase()
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "hysteria")
            putJsonObject("settings") {
                put("version", 2)
                put("address", u.host)
                put("port", if (u.port > 0) u.port else 443)
            }
            putJsonObject("streamSettings") {
                put("network", "hysteria")
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    put("serverName", sni)
                    putJsonArray("alpn") { add(JsonPrimitive("h3")) }
                    put("allowInsecure", u.q("insecure") == "1")
                    pin?.let { put("pinnedPeerCertSha256", it) }
                }
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    put("auth", auth)
                }
                if (obfs != null) putJsonObject("finalmask") {
                    putJsonArray("udp") {
                        add(buildJsonObject {
                            put("type", "salamander")
                            putJsonObject("settings") { put("password", obfsPassword) }
                        })
                    }
                }
            }
        }
        return Server(
            id = id, name = u.fragment?.takeIf { it.isNotBlank() } ?: "${u.host}:${u.port}",
            protocol = "hysteria", transport = "hysteria", security = "tls",
            host = u.host, port = u.port, outbound = outbound, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- wireguard

    /** `wireguard://<private key>@host:port?publickey=…&address=10.0.0.2/32,fd00::2/128&mtu=1280&reserved=1,2,3&presharedkey=…#name` */
    private fun wireguard(link: String, id: String): Server? {
        val u = ShareUri.parse(link) ?: return null
        val secret = u.userInfo?.takeIf { it.isNotBlank() } ?: u.q("privatekey") ?: return null
        val peer = u.q("publickey") ?: u.q("peer") ?: return null
        val port = if (u.port > 0) u.port else 51820
        val addresses = (u.q("address") ?: u.q("ip") ?: "10.0.0.2/32").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
        val reserved = u.q("reserved")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }
        val endpoint = if (':' in u.host) "[${u.host}]:$port" else "${u.host}:$port"
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", secret)
                put("address", JsonArray(addresses.map(::JsonPrimitive)))
                putJsonArray("peers") {
                    add(buildJsonObject {
                        put("publicKey", peer)
                        u.q("presharedkey")?.let { put("preSharedKey", it) }
                        put("endpoint", endpoint)
                        put("keepAlive", u.q("keepalive")?.toIntOrNull() ?: 25)
                    })
                }
                put("mtu", u.q("mtu")?.toIntOrNull() ?: 1420)
                reserved?.let { r -> put("reserved", JsonArray(r.map(::JsonPrimitive))) }
            }
        }
        return Server(
            id = id, name = u.fragment?.takeIf { it.isNotBlank() } ?: "${u.host}:$port",
            protocol = "wireguard", transport = "udp", security = "wireguard",
            host = u.host, port = port, outbound = outbound, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- tuic / anytls / mieru (mihomo)

    private fun mihomoOnly(link: String, id: String, protocol: String, transport: String): Server? {
        val u = ShareUri.parse(link) ?: return null
        if (u.host.isBlank()) return null
        return Server(
            id = id, name = u.fragment?.takeIf { it.isNotBlank() } ?: "${u.host}:${u.port}",
            protocol = protocol, transport = transport, security = if (protocol == "mieru") "mieru" else "tls",
            host = u.host, port = u.port, outbound = null, link = link.trim(),
        )
    }

    // ---------------------------------------------------------------- stream settings

    private fun normalizeNet(net: String): String = when (net.lowercase()) {
        "raw", "tcp" -> "tcp"
        "h2", "http" -> "http"
        "splithttp" -> "xhttp"
        else -> net.lowercase()
    }

    private fun stream(net: String, security: String, q: Map<String, String>, address: String): JsonObject {
        fun v(k: String) = q[k]?.takeIf { it.isNotEmpty() }
        val host = v("host")
        val path = v("path")
        return buildJsonObject {
            put("network", net)
            put("security", security)
            when (net) {
                "tcp" -> if (v("headerType") == "http") putJsonObject("tcpSettings") {
                    putJsonObject("header") {
                        put("type", "http")
                        putJsonObject("request") {
                            putJsonArray("path") { add(JsonPrimitive(path ?: "/")) }
                            host?.let { h -> putJsonObject("headers") { putJsonArray("Host") { h.split(',').forEach { add(JsonPrimitive(it.trim())) } } } }
                        }
                    }
                }
                "ws" -> putJsonObject("wsSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                }
                "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                }
                "grpc" -> putJsonObject("grpcSettings") {
                    put("serviceName", v("serviceName") ?: path?.trimStart('/') ?: "")
                    put("multiMode", v("mode") == "multi")
                    v("authority")?.let { put("authority", it) }
                }
                "xhttp" -> putJsonObject("xhttpSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                    v("mode")?.let { put("mode", it) }
                    v("extra")?.let { extra ->
                        runCatching { JsonX.parseToJsonElement(extra).jsonObject }.getOrNull()?.let { put("extra", it) }
                    }
                }
                "kcp" -> putJsonObject("kcpSettings") {
                    putJsonObject("header") { put("type", v("headerType") ?: "none") }
                    v("seed")?.let { put("seed", it) }
                }
                "http" -> putJsonObject("httpSettings") {
                    put("path", path ?: "/")
                    host?.let { h -> putJsonArray("host") { h.split(',').forEach { add(JsonPrimitive(it.trim())) } } }
                }
            }
            val alpn = v("alpn")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            when (security) {
                "tls" -> putJsonObject("tlsSettings") {
                    put("serverName", v("sni") ?: host ?: address)
                    put("fingerprint", v("fp") ?: "chrome")
                    put("allowInsecure", v("allowInsecure") == "1" || v("insecure") == "1")
                    alpn?.let { list -> put("alpn", JsonArray(list.map(::JsonPrimitive))) }
                }
                "reality" -> putJsonObject("realitySettings") {
                    put("serverName", v("sni") ?: "")
                    put("fingerprint", v("fp") ?: "chrome")
                    put("publicKey", v("pbk") ?: "")
                    put("shortId", v("sid") ?: "")
                    put("spiderX", v("spx") ?: "")
                    v("pqv")?.let { put("mldsa65Verify", it) }
                }
            }
        }
    }

    /** Best-effort summary of an outbound for UI badges. */
    fun describe(outbound: JsonObject): Triple<String, String?, String?> {
        val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull ?: "?"
        val stream = outbound["streamSettings"] as? JsonObject
        val net = stream?.get("network")?.jsonPrimitive?.contentOrNull?.let(::normalizeNet) ?: "tcp"
        val sec = stream?.get("security")?.jsonPrimitive?.contentOrNull
        return Triple(protocol, net, sec)
    }

    /** Server address of an outbound (vnext / servers / hysteria settings). */
    fun endpoint(outbound: JsonObject): Pair<String?, Int> {
        val settings = outbound["settings"] as? JsonObject ?: return null to 0
        val first = ((settings["vnext"] ?: settings["servers"]) as? JsonArray)?.firstOrNull() as? JsonObject
        val node = first ?: settings
        val host = node["address"]?.jsonPrimitive?.contentOrNull
        val port = node["port"]?.jsonPrimitive?.let { runCatching { it.int }.getOrNull() } ?: 0
        return host to port
    }

    internal fun jsonArrayOf(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }
}
