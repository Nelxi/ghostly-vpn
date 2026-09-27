package app.ghostly.core.vpn

import app.ghostly.core.model.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a server that fails the ping doesn't answer, found by a direct connection (outside the tunnel):
 * RKN's TSPU either drops the IP, kills the TLS handshake by SNI, or lets the connection start and
 * freezes it after the first ~16 KB (the "16 KB block" of foreign hosting).
 */
@Serializable
enum class BlockVerdict(val short: String, val title: String) {
    /** The server itself answers directly — the problem is in the proxy, not a block. */
    REACHABLE("нет", "Сервер доступен напрямую — не отвечает сам прокси"),
    /** No TCP to host:port while the internet works. */
    IP_BLOCKED("IP блок", "IP сервера недоступен — похоже на блокировку РКН"),
    /** TCP opens, the TLS handshake is reset or never finishes. */
    TLS_BLOCKED("DPI", "Соединение рвётся на TLS — ТСПУ режет по SNI"),
    /** Data flows, then freezes after ~16 KB. */
    TSPU_16KB("16 КБ", "Трафик замирает после ~16 КБ — блок ТСПУ по объёму"),
    /** No internet at all: nothing to say about the server. */
    OFFLINE("нет", "Нет интернета"),
}

/** What the direct check connects to: the server's address and the SNI it presents (null = no TLS). */
data class BlockTarget(val host: String, val port: Int, val sni: String?)

object BlockCheck {
    private val UDP = setOf("hysteria", "hysteria2", "tuic", "wireguard")

    /** Direct check target of a server; null when it can't be checked (UDP, balancer, no host). */
    fun target(server: Server): BlockTarget? {
        val host = server.host ?: return null
        if (server.isAuto || server.port <= 0 || server.protocol in UDP) return null
        val outbound = server.outbound ?: (server.config?.get("outbounds") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["tag"] as? JsonPrimitive)?.content == "proxy" }
        val stream = outbound?.get("streamSettings") as? JsonObject
        val sni = listOf("realitySettings", "tlsSettings").firstNotNullOfOrNull { key ->
            ((stream?.get(key) as? JsonObject)?.get("serverName") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } ?: listOf("servername", "sni").firstNotNullOfOrNull { key ->
            (server.mihomo?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
        val tls = server.security == "tls" || server.security == "reality" || sni != null
        return BlockTarget(host, server.port, if (tls) (sni ?: host) else null)
    }
}
