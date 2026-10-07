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

/** White lists over the classic CDN route — the scarce pool the app leaves as soon as it can. */
const val POOL_WL = "wl"

/**
 * «Белые списки 2»: the same idea over Hysteria2 (UDP). Mobile operators throttle TCP far more
 * aggressively than QUIC, so this variant keeps working where the CDN front stops passing traffic.
 * It is a white-list pool, not a regular one: the guard uses it when normal traffic is blocked.
 */
const val POOL_WL2 = "wl2"

/** Regular servers (everything that is not a white list). */
const val POOL_REG = "reg"

/** A traffic pool: [POOL_WL], [POOL_WL2] or [POOL_REG]. Bytes; total 0 = unlimited. */
@Serializable
data class TrafficPool(val id: String, val used: Long, val total: Long, val topupLeftGb: Int = 0) {
    val title: String
        get() = when (id) {
            POOL_WL -> "Белые списки"
            POOL_WL2 -> "Белые списки 2"
            else -> "Обычные серверы"
        }

    /** White lists of either kind get the accent colour in the bars. */
    val isWhitelist: Boolean get() = id == POOL_WL || id == POOL_WL2
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
    /** Traffic pool: [POOL_WL] / [POOL_WL2] / [POOL_REG]; from the provider's meta or guessed by name. */
    val pool: String? = null,
    /** Proxy entry of a Clash/mihomo YAML subscription (the config itself is [Profile.mihomo]). */
    val mihomo: JsonObject? = null,
) {
    val isAuto: Boolean get() = protocol == "balancer" || protocol == MIHOMO_PROFILE
    /** A balancer is measured through its main proxy; a whole mihomo profile has no single endpoint. */
    val canPing: Boolean get() = protocol != MIHOMO_PROFILE
    /** A white list, either pool: the tunnel is only used where normal traffic is blocked. */
    val isWhitelist: Boolean get() = pool == POOL_WL || pool == POOL_WL2
    /**
     * «Белые списки 2» — the white list that runs on Hysteria2. The Ghostly server sends it as
     * plain [POOL_WL] (older apps only know that one), so the protocol tells the two apart.
     */
    val isWhitelistHysteria: Boolean get() = pool == POOL_WL2 || (pool == POOL_WL && isHysteria)
    /** Xray spells Hysteria2 as `hysteria` (the version lives in the settings). UDP, so it wins on mobile. */
    val isHysteria: Boolean get() = protocol == "hysteria"
}

/** Pseudo server for a mihomo profile whose proxies come only from proxy-providers. */
const val MIHOMO_PROFILE = "mihomo"

/** Guess the pool of a server from its name when the provider doesn't say. */
fun guessPool(name: String): String? {
    val n = name.lowercase()
    val whitelist = ("бел" in n && "спис" in n) || "whitelist" in n || "white list" in n
    if (!whitelist) return null
    // «Белые списки 2» is the Hysteria2 white list the provider ships next to the CDN route; the name
    // check needs the protocol in it, otherwise the CDN «Белые списки 2» would be mistaken for it.
    // Providers that care send an explicit `meta.pool` instead of relying on names at all.
    return if ("hysteria" in n || "hy2" in n) POOL_WL2 else POOL_WL
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
