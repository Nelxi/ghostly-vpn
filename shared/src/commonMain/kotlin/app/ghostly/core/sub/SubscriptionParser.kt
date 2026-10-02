package app.ghostly.core.sub

import app.ghostly.core.JsonX
import app.ghostly.core.link.LinkParser
import app.ghostly.core.link.decodeBase64Lenient
import app.ghostly.core.mihomo.MihomoProfiles
import app.ghostly.core.mihomo.MihomoYaml
import app.ghostly.core.model.Server
import app.ghostly.core.model.SubscriptionInfo
import app.ghostly.core.model.TrafficPool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Result of parsing a subscription body + headers. */
data class ParsedSubscription(
    val title: String?,
    val info: SubscriptionInfo?,
    val supportUrl: String?,
    val webPageUrl: String?,
    /** `announce`: the provider's note for the user (plain or `base64:`). */
    val announce: String? = null,
    /** `sub-expire-button-link`: where renewing the subscription happens. */
    val renewUrl: String? = null,
    /** `sub-info-*`: the provider's info block. */
    val notice: app.ghostly.core.model.ProviderNotice? = null,
    val updateIntervalHours: Int?,
    val servers: List<Server>,
    /** Clash/mihomo config when the body was YAML (see [app.ghostly.core.model.Profile.mihomo]). */
    val mihomo: JsonObject? = null,
    /** The address that actually answered (the link itself or one of Ghostly's mirror domains). */
    val fetchedFrom: String? = null,
    /** Every attempt on the link's own address failed (DNS, block, timeout) before a mirror answered. */
    val ownAddressFailed: Boolean = false,
)

object SubscriptionParser {

    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "socks", "http", "wireguard")

    /**
     * @param headers lower-cased response headers
     * @param idPrefix stable prefix for server ids (profile id), so selections survive refreshes
     */
    fun parse(body: String, headers: Map<String, String>, idPrefix: String): ParsedSubscription {
        val text = body.trim().removePrefix("﻿")
        val inlineHeaders = mutableMapOf<String, String>()
        var mihomo: JsonObject? = null

        val servers: List<Server> = when {
            MihomoYaml.looksLikeClash(text) -> {
                text.lineSequence().map { it.trim() }.filter { it.startsWith("#") && ':' in it }.forEach { line ->
                    inlineHeaders[line.removePrefix("#").substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                }
                val cfg = MihomoYaml.parse(text)
                mihomo = cfg
                MihomoProfiles.servers(cfg, idPrefix, inlineHeaders)
            }
            text.startsWith("[") || text.startsWith("{") -> parseXrayJson(text, idPrefix)
            else -> {
                val plain = if (text.lines().any { LinkParser.looksLikeLink(it) }) text
                else decodeBase64Lenient(text) ?: text
                val links = mutableListOf<String>()
                plain.lineSequence().map { it.trim() }.forEach { line ->
                    when {
                        line.startsWith("#") && ':' in line -> {
                            // Happ/Incy style inline headers: "#profile-title: ...".
                            val k = line.removePrefix("#").substringBefore(':').trim().lowercase()
                            inlineHeaders[k] = line.substringAfter(':').trim()
                        }
                        LinkParser.looksLikeLink(line) -> links += line
                    }
                }
                links.mapIndexedNotNull { i, l -> LinkParser.parse(l, "$idPrefix:$i")?.let { s -> s.copy(pool = app.ghostly.core.model.guessPool(s.name)) } }
            }
        }

        val h = inlineHeaders + headers
        return ParsedSubscription(
            title = h["profile-title"]?.let(::decodeTitle),
            info = h["subscription-userinfo"]?.let(::parseUserInfo)?.let { info ->
                h["ghostly-pools"]?.let { withPools(info, it) } ?: info
            },
            supportUrl = h["support-url"],
            webPageUrl = h["profile-web-page-url"],
            announce = h["announce"]?.let(::decodeTitle)?.trim()?.takeIf { it.isNotEmpty() }?.take(1000),
            renewUrl = h["sub-expire-button-link"]?.trim()?.takeIf { it.isNotEmpty() },
            notice = h["sub-info-text"]?.let(::decodeTitle)?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                app.ghostly.core.model.ProviderNotice(
                    text = text.take(1000),
                    color = h["sub-info-color"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                    buttonText = h["sub-info-button-text"]?.let(::decodeTitle)?.trim()?.takeIf { it.isNotEmpty() },
                    buttonUrl = h["sub-info-button-link"]?.trim()?.takeIf { it.isNotEmpty() },
                )
            },
            updateIntervalHours = h["profile-update-interval"]?.trim()?.toIntOrNull(),
            servers = dedupeIds(servers),
            mihomo = mihomo,
        )
    }

    /** Happ-style subscription: a JSON array of complete Xray configs (or a single one). */
    private fun parseXrayJson(text: String, idPrefix: String): List<Server> {
        val root = JsonX.parseToJsonElement(text)
        val configs = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return configs.mapIndexedNotNull { i, cfg -> serverFromConfig(cfg, "$idPrefix:$i") }
    }

    fun serverFromConfig(cfg: JsonObject, id: String): Server? {
        val outbounds = (cfg["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val proxies = outbounds.filter { it["protocol"]?.jsonPrimitive?.contentOrNull in PROXY_PROTOCOLS }
        if (proxies.isEmpty()) return null
        val routing = cfg["routing"] as? JsonObject
        val hasBalancer = (routing?.get("balancers") as? JsonArray)?.isNotEmpty() == true
        val name = remarks(cfg) ?: "Server ${id.substringAfterLast(':')}"
        val main = proxies.firstOrNull { it["tag"]?.jsonPrimitive?.contentOrNull == "proxy" } ?: proxies.first()
        val (protocol, net, sec) = LinkParser.describe(main)
        val (host, port) = LinkParser.endpoint(main)
        return Server(
            id = id,
            name = name,
            protocol = if (hasBalancer && proxies.size > 1) "balancer" else protocol,
            transport = if (hasBalancer && proxies.size > 1) "${proxies.size}" else net,
            security = sec,
            host = host,
            port = port,
            config = cfg,
            pool = ((cfg["meta"] as? JsonObject)?.get("pool")?.jsonPrimitive?.contentOrNull) ?: app.ghostly.core.model.guessPool(name),
        )
    }

    private fun remarks(cfg: JsonObject): String? =
        cfg["remarks"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (cfg["meta"] as? JsonObject)?.get("serverDescription")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun dedupeIds(list: List<Server>): List<Server> {
        val seen = HashSet<String>()
        return list.map { s ->
            var id = s.id
            var n = 1
            while (!seen.add(id)) id = "${s.id}#${n++}"
            if (id == s.id) s else s.copy(id = id)
        }
    }

    fun decodeTitle(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("base64:")) decodeBase64Lenient(t.removePrefix("base64:")) ?: t else t
    }

    /** `wl_used=..; wl_total=..; wl_topup=..; reg_used=..; ...; cycle_end=..; unlimited=0` */
    fun withPools(info: SubscriptionInfo, raw: String): SubscriptionInfo {
        val m = raw.split(';').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
        }.toMap()
        if (m["unlimited"] == "1") return info.copy(cycleEnd = m["cycle_end"]?.toLongOrNull() ?: 0)
        val pools = listOf("reg", "wl").mapNotNull { id ->
            val total = m["${id}_total"]?.toLongOrNull() ?: return@mapNotNull null
            TrafficPool(id, m["${id}_used"]?.toLongOrNull() ?: 0, total, m["${id}_topup"]?.toIntOrNull() ?: 0)
        }
        return info.copy(pools = pools, cycleEnd = m["cycle_end"]?.toLongOrNull() ?: 0)
    }

    fun parseUserInfo(raw: String): SubscriptionInfo {
        val map = raw.split(';').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to (kv[1].trim().toDoubleOrNull()?.toLong() ?: 0L) else null
        }.toMap()
        return SubscriptionInfo(
            upload = map["upload"] ?: 0,
            download = map["download"] ?: 0,
            total = map["total"] ?: 0,
            expire = map["expire"] ?: 0,
        )
    }
}
