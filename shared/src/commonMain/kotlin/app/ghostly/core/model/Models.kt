package app.ghostly.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A subscription (or a manually added group of links). */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Subscription URL; null for a profile made of pasted links. */
    val url: String? = null,
    val info: SubscriptionInfo? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    /** Provider's note for the user (Remnawave/Happ `announce` header): shown with the subscription. */
    val announce: String? = null,
    /** Where "Продлить" leads: Happ's `sub-expire-button-link` (a bot, a payment page). */
    val renewUrl: String? = null,
    /** Happ's info block (`sub-info-text`, `-color`, `-button-text`, `-button-link`). */
    val notice: ProviderNotice? = null,
    /** Update interval the provider asked for (`profile-update-interval`), hours. */
    val updateIntervalHours: Int = 12,
    val updatedAt: Long = 0,
    val servers: List<Server> = emptyList(),
    /** Whole Clash/mihomo config (YAML subscription, kept as JSON): runs on mihomo with its groups and rules. */
    val mihomo: JsonObject? = null,
)

/** The provider's info block with an optional button (Happ `sub-info-*` headers). */
@Serializable
data class ProviderNotice(val text: String, val color: String? = null, val buttonText: String? = null, val buttonUrl: String? = null)

/** `subscription-userinfo` header: bytes and a unix-seconds expiry (0 = unlimited). */
@Serializable
data class SubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    /** Ghostly servers split traffic into pools (header `ghostly-pools`); empty for other providers. */
    val pools: List<TrafficPool> = emptyList(),
    /** When the pools reset (unix seconds), 0 if unknown. */
    val cycleEnd: Long = 0,
) {
    val used: Long get() = upload + download
    val unlimitedTraffic: Boolean get() = total <= 0
    val unlimitedTime: Boolean get() = expire <= 0
}

/** A traffic pool: "wl" (white lists) or "reg" (regular servers). Bytes; total 0 = unlimited. */
@Serializable
data class TrafficPool(val id: String, val used: Long, val total: Long, val topupLeftGb: Int = 0) {
    val title: String get() = if (id == "wl") "Белые списки" else "Обычные серверы"
}

/**
 * One connectable entry. Either a full Xray config handed over by the provider (Happ-style JSON
 * subscription, keeps the provider's own routing/DNS/balancer) or a single proxy outbound parsed
 * from a share link, which we wrap into our own config.
 */
@Serializable
data class Server(
    val id: String,
    val name: String,
    /** vless / vmess / trojan / shadowsocks / hysteria / balancer */
    val protocol: String,
    /** tcp / grpc / ws / xhttp / hysteria / … — for the UI badge. */
    val transport: String? = null,
    /** reality / tls / none */
    val security: String? = null,
    val host: String? = null,
    val port: Int = 0,
    /** Proxy outbound (tag "proxy") for link-based servers. */
    val outbound: JsonObject? = null,
    /** Complete Xray config for JSON-subscription servers. */
    val config: JsonObject? = null,
    /** Original share link, if the server came from one (for "copy link"). */
    val link: String? = null,
    /** Traffic pool: "wl" (white lists) or "reg"; from the provider's meta or guessed by name. */
    val pool: String? = null,
    /** Proxy entry of a Clash/mihomo YAML subscription (the config itself is [Profile.mihomo]). */
    val mihomo: JsonObject? = null,
) {
    val isAuto: Boolean get() = protocol == "balancer" || protocol == MIHOMO_PROFILE
    /** A balancer is measured through its main proxy; a whole mihomo profile has no single endpoint. */
    val canPing: Boolean get() = protocol != MIHOMO_PROFILE
    val isWhitelist: Boolean get() = pool == "wl"
}

/** Pseudo server for a mihomo profile whose proxies come only from proxy-providers. */
const val MIHOMO_PROFILE = "mihomo"

/** Guess the pool of a server from its name when the provider doesn't say. */
fun guessPool(name: String): String? {
    val n = name.lowercase()
    return if (("бел" in n && "спис" in n) || "whitelist" in n || "white list" in n) "wl" else null
}

/** A latency result: millis, or a negative code. */
@Serializable
data class Ping(
    val ms: Long,
    val at: Long,
    val quick: Boolean = false,
    /** Why a failed server doesn't answer, from the direct check (null = not checked). */
    val block: app.ghostly.core.vpn.BlockVerdict? = null,
) {
    val ok: Boolean get() = ms > 0
}
