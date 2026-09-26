package app.ghostly.core.vpn

import kotlinx.coroutines.launch

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.Server
import kotlinx.coroutines.flow.StateFlow

sealed interface VpnState {
    data object Idle : VpnState
    data object Connecting : VpnState
    data class Connected(val since: Long, val serverId: String) : VpnState
    data object Disconnecting : VpnState
    data class Failed(val message: String) : VpnState
}

/** Bytes per second through the proxy (sum over proxy outbounds) and totals for the session. */
data class Traffic(
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

/** Platform part: owns the tunnel and the core. Implemented by Android VpnService, desktop process, iOS NE. */
interface VpnBackend {
    val state: StateFlow<VpnState>
    val traffic: StateFlow<Traffic>

    /** Needs a one-time OS consent (Android VPN dialog, macOS/iOS profile)? */
    fun needsPermission(): Boolean = false

    /** Ask for it; the result arrives through [state] or the returned flag. */
    suspend fun requestPermission(): Boolean = true

    suspend fun connect(server: Server, settings: AppSettings)

    suspend fun disconnect()

    /** Latency of one server through the core, millis; negative on failure. */
    suspend fun ping(server: Server, url: String): Long

    /** Real latency for many servers; reports each result as it arrives. Backends may batch. */
    suspend fun pingMany(servers: List<Server>, url: String, onResult: (serverId: String, ms: Long) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(8)
            servers.forEach { s ->
                launch {
                    gate.acquire()
                    try {
                        onResult(s.id, runCatching { ping(s, url) }.getOrDefault(-1))
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    /** Round-trip through the RUNNING tunnel, ms; negative when traffic doesn't pass. */
    suspend fun healthCheck(url: String): Long = -1

    /**
     * True when the app's own sockets bypass the tunnel (Android excludes the app; desktop proxy mode),
     * so a direct TCP probe tells us about the real network, not the VPN.
     */
    val directProbesBypassTunnel: Boolean get() = true

    /** Loopback SOCKS port into the running core for the app's own requests; null when down. */
    val appPort: Int? get() = null

    /** Version string of the embedded core. */
    fun coreVersion(): String

    /** What the core prints (the "Core log" screen); [CoreLog.NONE] when the platform can't read it. */
    val coreLog: CoreLog get() = CoreLog.NONE

    /** Switch the running tunnel to [server] without reconnecting (mihomo selectors); false = reconnect instead. */
    suspend fun switchInPlace(server: Server): Boolean = false
}

/** Things only the host platform knows. */
interface PlatformInfo {
    val os: String
    val osVersion: String
    val deviceModel: String
    /** Stable device id, sent as x-hwid to subscription servers (Happ-compatible). */
    val hwid: String
    val appVersion: String
    val dataDir: String
    val supportsPerAppSplit: Boolean get() = false
    val isDesktop: Boolean get() = false

    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    fun readClipboard(): String?
    fun share(text: String) = copyToClipboard(text)
    fun haptic() {}

    /** Release file name for this platform/ABI ("Ghostly-Android.apk", "Ghostly-Windows.exe"); null = no self-update. */
    val updateAsset: String? get() = null

    /**
     * Downloads the first reachable of [urls], hashing while it streams; returns the local path only
     * if SHA-256 equals [sha256], otherwise deletes it and throws.
     */
    suspend fun downloadVerified(urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit): String =
        throw UnsupportedOperationException("updates are not supported here")

    /** May an update be installed without a tap first (Android: only once "install unknown apps" is granted). */
    fun canAutoInstall(): Boolean = true

    /** Hands a verified update to the OS installer (Android package installer / Windows setup). */
    fun installUpdate(path: String) {}

    /** Local time zone offset from UTC, minutes (greetings, local clock). */
    fun utcOffsetMinutes(): Int = 180

    /** The physical network under the tunnel (drives white-list decisions). */
    fun networkType(): NetType = NetType.UNKNOWN

    /** This device's address in the local network (Wi-Fi/Ethernet), for sharing the proxy. */
    fun lanAddress(): String? = null

    /** Can we listen on this local port? (Another VPN client often sits on 10808/10809.) */
    fun isPortFree(port: Int, listen: String): Boolean = true

    /** System ping (ICMP) of [host] in ms, negative on failure or when the platform can't. */
    suspend fun icmpPing(host: String, timeoutMs: Int): Long = -1

    /** TCP handshake time to host:port in ms, negative on failure (the "quick ping"). */
    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int): Long = -1

    /** Launch at login / on boot (desktop autostart entry; Android uses a boot receiver). */
    fun setStartOnBoot(enabled: Boolean) {}

    /** QR scanning (camera). Null when the platform has none. */
    val qrScanner: ((onResult: (String) -> Unit) -> Unit)? get() = null

    /** Installed apps for per-app split tunnelling (Android). */
    suspend fun installedApps(): List<AppEntry> = emptyList()

    /** Music on the computer: audio analysis + now playing (desktop "stage mode"). */
    val stage: app.ghostly.core.stage.StageSource? get() = null

    /** App-level kill switch (desktop); Android uses the system one via [systemVpnSettings]. */
    val killSwitch: KillSwitch? get() = null

    /** Opens the OS page with always-on VPN / "block connections without VPN" (kill switch). */
    val systemVpnSettings: (() -> Unit)? get() = null
}

/** Blocks traffic outside the tunnel while it is down (desktop firewall based). */
interface KillSwitch {
    val engaged: Boolean
    /** Null when it can work right now, otherwise a human reason. */
    fun unavailableReason(): String?
    fun engage(): Boolean
    fun release()
}

enum class NetType { WIFI, CELLULAR, ETHERNET, UNKNOWN }

data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean)
