package app.ghostly.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode {
    /** Provider rules as sent; for plain links: Russian sites + LAN direct, rest via VPN. */
    SMART,
    /** Everything through the VPN except LAN. */
    GLOBAL,
}

/** Which core runs link servers. Xray-JSON servers always use Xray, Clash/mihomo YAML always mihomo. */
@Serializable
enum class CoreType { XRAY, MIHOMO }

/** How server latency is measured (like Happ / Incy). */
@Serializable
enum class PingMethod(val title: String) {
    /** Full HTTP GET to the ping URL through the server: the real round-trip of the tunnel. */
    PROXY_GET("via proxy GET"),
    /** The same with HEAD: no response body, a bit faster and lighter. */
    PROXY_HEAD("via proxy HEAD"),
    /** TCP handshake with the server itself; does not prove the tunnel works. */
    TCP("TCP"),
    /** System ping (ICMP) of the server's host; many servers drop it. */
    ICMP("ICMP"),
}

@Serializable
enum class SplitMode { OFF, ONLY_SELECTED, BYPASS_SELECTED }

@Serializable
enum class DesktopMode {
    /** Whole system through a virtual adapter (needs admin). */
    TUN,
    /** Local proxy + the OS proxy setting (browsers and most apps). */
    SYSTEM_PROXY,
    /** Local SOCKS5/HTTP only; nothing in the OS is touched — point apps at it yourself. */
    PROXY_ONLY,
}

@Serializable
enum class DnsPreset(val title: String, val address: String?) {
    PROVIDER("Как у провайдера", null),
    CLOUDFLARE("Cloudflare", "https://1.1.1.1/dns-query"),
    GOOGLE("Google", "https://8.8.8.8/dns-query"),
    QUAD9("Quad9", "https://9.9.9.9/dns-query"),
    ADGUARD("AdGuard (без рекламы)", "https://94.140.14.14/dns-query"),
    CUSTOM("Свой", null),
}

/** DNS for sites that open WITHOUT the VPN (Russian ones in smart routing). */
@Serializable
enum class DirectDns(val title: String, val address: String?) {
    /** Yandex over DoH, sent straight out (not through the tunnel): encrypted, so the ISP can't swap answers. */
    YANDEX("Яндекс (зашифрованный)", "https+local://77.88.8.8/dns-query"),
    /** The resolver of the network / the ISP. */
    SYSTEM("Системный (провайдера)", "localhost"),
    CUSTOM("Свой", null),
}

/** Which address families DNS returns. AUTO follows the IPv6 switch. */
@Serializable
enum class DnsStrategy(val title: String, val xray: String?) {
    AUTO("Как IPv6 в туннеле", null),
    IPV4("Только IPv4", "UseIPv4"),
    IPV4_FIRST("IPv4, затем IPv6", "UseIPv4v6"),
    IPV6_FIRST("IPv6, затем IPv4", "UseIPv6v4"),
    BOTH("IPv4 и IPv6", "UseIP"),
}

@Serializable
enum class ThemeAccent(val argb: Long) {
    GHOST(0xFFA88DFF),
    MINT(0xFF8FE3C0),
    SAKURA(0xFFFF9AC8),
    SKY(0xFF8CC8FF),
    SUNSET(0xFFFFB38A),
}

@Serializable
data class AppSettings(
    // --- routing
    val routingMode: RoutingMode = RoutingMode.SMART,
    val blockAds: Boolean = false,
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
    val blockDomains: List<String> = emptyList(),
    // --- dns
    val dns: DnsPreset = DnsPreset.PROVIDER,
    val customDns: String = "",
    // --- dns, for advanced users (defaults = the behaviour before these settings existed)
    /** Russian domains resolve through [dnsDirect] (only in smart routing). */
    val dnsSplitRu: Boolean = true,
    val dnsDirect: DirectDns = DirectDns.YANDEX,
    val dnsDirectCustom: String = "",
    /** Plain-IP resolver for the names of DoH servers and VPN servers before the tunnel is up (mihomo). */
    val dnsBootstrap: String = "77.88.8.8",
    val dnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    val dnsCache: Boolean = true,
    /** mihomo: answer with fake addresses and resolve on the server side (fast, no DNS leaks). */
    val dnsFakeIp: Boolean = true,
    /** "domain ip" lines: static answers, applied before any DNS server. */
    val dnsHosts: List<String> = emptyList(),
    val ipv6: Boolean = false,
    // --- tunnel
    /** Xray by default: one "Авто" button is what most people want; mihomo (selectors) is opt-in. */
    val core: CoreType = CoreType.XRAY,
    /** Legacy flag of 0.2.1 (it moved everyone to mihomo once). */
    val coreDefaultApplied: Boolean = false,
    /** Set once 0.2.2 moved everyone back to Xray; a manual choice made after that sticks. */
    val coreXrayRestored: Boolean = false,
    val mtu: Int = 1500,
    val mux: Boolean = false,
    val fragment: Boolean = false,
    val sniffing: Boolean = true,
    /** Block UDP 443: browsers/YouTube fall back from QUIC to TCP, which many mobile networks handle better. */
    val blockQuic: Boolean = false,
    /** TCP Fast Open on proxy connections: one round-trip less per new connection. */
    val tcpFastOpen: Boolean = false,
    // --- per-app (Android): Russian apps go around the VPN out of the box (see [RuApps])
    val splitMode: SplitMode = SplitMode.BYPASS_SELECTED,
    val splitApps: Set<String> = RuApps.PACKAGES,
    /** Set once 0.3.18 put [RuApps] around the VPN for everyone; later choices stick. */
    val ruAppsBypassApplied: Boolean = false,
    // --- desktop
    val desktopMode: DesktopMode = DesktopMode.SYSTEM_PROXY,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val allowLan: Boolean = false,
    /** Desktop: the window's ✕ hides Ghostly to the tray instead of quitting. */
    val closeToTray: Boolean = true,
    /** Desktop: block the internet while the VPN is unexpectedly down (Windows Firewall, TUN mode). */
    val killSwitch: Boolean = false,
    /** Desktop: the ghost sings along with the music playing on the PC, the UI plays like a stage. */
    val stageMode: Boolean = true,
    /** Local SOCKS/HTTP inbounds on phones (for Telegram proxy etc.); always on in desktop proxy mode. */
    val localProxy: Boolean = false,
    /** Login/password on the local proxies. Generated on first run: ghostly_xxxxxx + random password. */
    val proxyAuth: Boolean = true,
    val proxyUser: String = "",
    val proxyPass: String = "",
    // --- behaviour
    val autoConnect: Boolean = false,
    /** Bring the tunnel up when the device boots (Android BOOT_COMPLETED / desktop autostart). */
    val startOnBoot: Boolean = false,
    val autoReconnect: Boolean = true,
    val autoFailover: Boolean = true,
    /** Watch the tunnel while connected; switch servers when traffic stops passing. */
    val smartGuard: Boolean = true,
    /** Leave white-list servers as soon as normal internet is back (their traffic is scarce). */
    val saveWhitelist: Boolean = true,
    val autoUpdateSubs: Boolean = true,
    /** Look for a new app version in the background (every 6 hours). */
    val autoCheckUpdates: Boolean = true,
    /** Download and install a found update by itself (desktop: silent + relaunch; Android: opens the installer). */
    val autoInstallUpdates: Boolean = true,
    // Ping and log settings are kept per core: [pingUrl]/[pingMethod]/[logLevel] are Xray's.
    val pingUrl: String = "https://www.gstatic.com/generate_204",
    val pingMethod: PingMethod = PingMethod.PROXY_GET,
    val logLevel: String = "warning",
    val mihomoPingUrl: String = "https://www.gstatic.com/generate_204",
    val mihomoPingMethod: PingMethod = PingMethod.PROXY_GET,
    val mihomoLogLevel: String = "warning",
    /** A server that doesn't answer the ping gets a direct check: is its IP blocked, or does the TSPU cut it at ~16 KB. */
    val blockCheck: Boolean = true,
    // --- look & feel
    val accent: ThemeAccent = ThemeAccent.GHOST,
    val haptics: Boolean = true,
    /** 0..1: how strong the vibration is (motors differ a lot between phones). */
    val hapticStrength: Float = 0.6f,
    /** Soft UI sounds on actions (connect, connected, error, picks). */
    val sounds: Boolean = true,
    /** 0..1 */
    val soundVolume: Float = 0.5f,
    /** Android 12+: take the accent from the wallpaper (Material You / Monet) instead of [accent]. */
    val monet: Boolean = false,
    val reduceMotion: Boolean = false,
    val language: String = "system",
) {

    /** One-time move to [RuApps] around the VPN. An "only these apps" list is the user's own and stays. */
    fun withRuAppsBypass(): AppSettings = when (splitMode) {
        // apps left in the list from an earlier mode were inactive — don't revive them
        SplitMode.OFF -> copy(splitMode = SplitMode.BYPASS_SELECTED, splitApps = RuApps.PACKAGES, ruAppsBypassApplied = true)
        SplitMode.BYPASS_SELECTED -> copy(splitApps = splitApps + RuApps.PACKAGES, ruAppsBypassApplied = true)
        SplitMode.ONLY_SELECTED -> copy(ruAppsBypassApplied = true)
    }

    /** Switching the per-app mode: Russian apps are "around the VPN" picks, never "only through VPN" ones. */
    fun withSplitMode(mode: SplitMode): AppSettings = when {
        mode == splitMode -> this
        mode == SplitMode.ONLY_SELECTED -> copy(splitMode = mode, splitApps = splitApps - RuApps.PACKAGES)
        mode == SplitMode.BYPASS_SELECTED -> copy(splitMode = mode, splitApps = splitApps + RuApps.PACKAGES)
        else -> copy(splitMode = mode)
    }

    fun pingMethodOf(core: CoreType) = if (core == CoreType.MIHOMO) mihomoPingMethod else pingMethod
    fun pingUrlOf(core: CoreType) = if (core == CoreType.MIHOMO) mihomoPingUrl else pingUrl
    fun logLevelOf(core: CoreType) = if (core == CoreType.MIHOMO) mihomoLogLevel else logLevel

    fun withPing(core: CoreType, method: PingMethod? = null, url: String? = null) =
        if (core == CoreType.MIHOMO) copy(mihomoPingMethod = method ?: mihomoPingMethod, mihomoPingUrl = url ?: mihomoPingUrl)
        else copy(pingMethod = method ?: pingMethod, pingUrl = url ?: pingUrl)

    fun withLogLevel(core: CoreType, level: String) =
        if (core == CoreType.MIHOMO) copy(mihomoLogLevel = level) else copy(logLevel = level)

    /** True while every DNS option is at its default: provider configs keep their own DNS then. */
    val dnsDefaults: Boolean
        get() = dns == DnsPreset.PROVIDER && dnsSplitRu && dnsDirect == DirectDns.YANDEX && dnsStrategy == DnsStrategy.AUTO &&
            dnsCache && dnsHosts.isEmpty()

    fun directDnsAddress(): String? = when (dnsDirect) {
        DirectDns.CUSTOM -> dnsDirectCustom.trim().takeIf { it.isNotEmpty() }
        else -> dnsDirect.address
    }

    /** User hosts parsed from "domain ip" lines (anything malformed is skipped). */
    fun hostsMap(): Map<String, String> = dnsHosts.mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size >= 2 && p[0].contains('.')) p[0].lowercase() to p[1] else null
    }.toMap()
}
