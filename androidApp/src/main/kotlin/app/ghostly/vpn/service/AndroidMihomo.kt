package app.ghostly.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.ghostly.core.JsonX
import app.ghostly.core.mihomo.MihomoApi
import app.ghostly.core.mihomo.MihomoConfigBuilder
import app.ghostly.core.mihomo.MihomoCore
import app.ghostly.core.mihomo.MihomoIngress
import app.ghostly.core.mihomo.MihomoPick
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnState
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Android mihomo, main-process side. The core itself (legiz-ru's Prizrak-Core through the
 * ClashMetaForAndroid bridge) runs in [MihomoVpnService] in its own `:mihomo` process: two Go
 * runtimes (Xray's libv2ray and mihomo's libclash) must never share a process.
 *
 * Here we build the config and files, start/stop the service, follow its state broadcasts and talk
 * to the core over its REST controller on loopback (groups, selectors, traffic).
 */
object AndroidMihomo : MihomoCore {

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableState = MutableStateFlow<VpnState>(VpnState.Idle)
    override val state: StateFlow<VpnState> = mutableState.asStateFlow()
    private val mutableTraffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = mutableTraffic.asStateFlow()

    @Volatile override var api: MihomoApi? = null
        private set
    @Volatile override var appPort: Int? = null
        private set

    /** What the next "connected" broadcast belongs to. */
    private class Pending(val serverId: String, val controller: Int, val secret: String, val appPort: Int, val picks: List<MihomoPick>)
    @Volatile private var pending: Pending? = null
    private var trafficJob: Job? = null
    @Volatile private var version: String? = null

    /** mihomo's home in the `:mihomo` process (the bridge uses files/clash). */
    fun homeDir(context: Context) = File(context.filesDir, "clash")
    fun profileDir(context: Context) = File(homeDir(context), "ghostly")

    /** One log per run, written by the service process, read (and appended to) by the app process. */
    fun logFile(context: Context) = File(homeDir(context), "logs/core.log")

    /** App-process lines land in the same file: short appends survive the cross-process sharing. */
    private fun log(line: String) {
        runCatching {
            val f = logFile(app)
            f.parentFile?.mkdirs()
            f.appendText(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + " [app] " + line + "\n")
        }
    }

    override suspend fun coreLogs(): String? = withContext(Dispatchers.IO) {
        runCatching { logFile(app).takeIf { it.isFile }?.readText()?.takeLast(64_000)?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun init(context: Context) {
        app = context.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onServiceState(intent)
        }, IntentFilter(MihomoVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override suspend fun connect(server: Server, profile: Profile?, settings: AppSettings, stored: Map<String, String>): Unit = withContext(Dispatchers.IO) {
        stopLocal()
        mutableState.value = VpnState.Connecting
        try {
            val controller = freePort()
            val secret = randomSecret()
            val appPort0 = freePort()
            val ingress = MihomoIngress(
                controllerPort = controller,
                secret = secret,
                appPort = appPort0,
                proxy = if (settings.localProxy) XrayConfigBuilder.localProxy(settings) else null,
            )
            val plan = MihomoConfigBuilder.build(server, profile, settings, ingress, stored = stored)
            val dir = profileDir(app).apply { mkdirs() }
            // The bridge moves provider files under <profile>/providers/.
            plan.links?.let { File(dir, "providers").apply { mkdirs() }.resolve(MihomoConfigBuilder.LINKS_FILE).writeText(it) }
            File(dir, "config.yaml").writeText(JsonX.encodeToString(JsonObject.serializer(), plan.config))
            copyGeoData()
            pending = Pending(server.id, controller, secret, appPort0, plan.picks)

            val intent = Intent(app, MihomoVpnService::class.java)
                .setAction(MihomoVpnService.ACTION_START)
                .putExtra(MihomoVpnService.EXTRA_NAME, server.name)
                .putExtra(MihomoVpnService.EXTRA_SUBSCRIPTION, profile?.name)
                .putExtra(MihomoVpnService.EXTRA_CONTROLLER, controller)
                .putExtra(MihomoVpnService.EXTRA_SECRET, secret)
                .putExtra(MihomoVpnService.EXTRA_MTU, settings.mtu)
                .putExtra(MihomoVpnService.EXTRA_IPV6, settings.ipv6)
                .putExtra(MihomoVpnService.EXTRA_SPLIT_MODE, settings.splitMode.name)
                .putExtra(MihomoVpnService.EXTRA_SPLIT_APPS, settings.splitApps.toTypedArray())
            ContextCompat.startForegroundService(app, intent)
            watchStart()
            Unit
        } catch (e: Exception) {
            mutableState.value = VpnState.Failed("Mihomo: ${e.message ?: "не удалось подготовить конфиг"}")
        }
    }

    override suspend fun disconnect() {
        if (state.value == VpnState.Idle) return
        mutableState.value = VpnState.Disconnecting
        app.startService(Intent(app, MihomoVpnService::class.java).setAction(MihomoVpnService.ACTION_STOP))
    }

    private fun onServiceState(intent: Intent) {
        when (intent.getStringExtra(MihomoVpnService.EXTRA_STATE)) {
            MihomoVpnService.STATE_CONNECTED -> {
                val p = pending ?: return
                scope.launch {
                    val client = MihomoApi(p.controller, p.secret)
                    client.log = { log("api: $it") }
                    // The core answers once the config is applied; normally right away.
                    var tries = 0
                    while (!client.ready() && tries++ < 50) delay(100)
                    log(if (tries >= 50) "controller NOT ready on 127.0.0.1:${p.controller} after ${tries * 100} ms" else "controller ready after ~${tries * 100} ms")
                    val applied = client.applyPicks(p.picks)
                    log("applyPicks(${p.picks.size}): " + (if (applied) "ok" else "FAILED") + " " + p.picks.joinToString { pk -> "${pk.group}→${pk.choice ?: "${pk.provider}#${pk.providerIndex}"}" })
                    // What the core actually routes by now — the one line that settles "did it apply".
                    log("groups now: " + client.groups().joinToString { g -> "${g.name}=${g.now}" })
                    version = client.version()
                    log("core version: $version")
                    api = client
                    appPort = p.appPort
                    mutableTraffic.value = Traffic()
                    mutableState.value = VpnState.Connected(System.currentTimeMillis(), p.serverId)
                    startTraffic(client)
                }
            }
            MihomoVpnService.STATE_FAILED -> {
                stopLocal()
                mutableState.value = VpnState.Failed(intent.getStringExtra(MihomoVpnService.EXTRA_MESSAGE) ?: "Mihomo не запустился")
            }
            MihomoVpnService.STATE_IDLE -> {
                // "Idle" of the session that was just replaced: a new connect is already under way.
                if (state.value == VpnState.Connecting) return
                stopLocal()
                if (state.value !is VpnState.Failed) mutableState.value = VpnState.Idle
            }
        }
    }

    private fun startTraffic(client: MihomoApi) {
        trafficJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            while (isActive) {
                runCatching {
                    client.traffic().collect { (up, down) ->
                        upTotal += up
                        downTotal += down
                        mutableTraffic.value = Traffic(up, down, upTotal, downTotal)
                    }
                }
                delay(1000)
            }
        }
    }

    private var startWatch: kotlinx.coroutines.Job? = null

    /** The service lives in another process; if it never answers, don't sit on "connecting" forever. */
    private fun watchStart() {
        startWatch?.cancel()
        startWatch = scope.launch {
            delay(START_TIMEOUT_MS)
            if (state.value == VpnState.Connecting) {
                log("no answer from the service in ${START_TIMEOUT_MS / 1000} s")
                stopLocal()
                mutableState.value = VpnState.Failed("Mihomo не ответил — пробую ещё раз")
            }
        }
    }

    private const val START_TIMEOUT_MS = 30_000L

    private fun stopLocal() {
        trafficJob?.cancel()
        trafficJob = null
        api?.close()
        api = null
        appPort = null
        mutableTraffic.value = Traffic()
    }

    /** Xray's geo files (already unpacked by [AndroidVpn]) are what mihomo reads in geodata mode. */
    private fun copyGeoData() {
        val src = File(app.filesDir, "assets")
        val home = homeDir(app).apply { mkdirs() }
        for ((from, to) in listOf("geoip.dat" to "GeoIP.dat", "geosite.dat" to "GeoSite.dat")) {
            val s = File(src, from)
            val d = File(home, to)
            if (s.isFile && (!d.isFile || d.length() != s.length())) runCatching { s.copyTo(d, overwrite = true) }
        }
    }

    override suspend fun healthCheck(url: String): Long = withContext(Dispatchers.IO) {
        val port = appPort ?: return@withContext -1L
        AndroidHttpProbe.socks(port, url)
    }

    override suspend fun ping(server: Server, url: String): Long = -1

    private val pingLock = kotlinx.coroutines.sync.Mutex()

    /** A ping-only config (proxies + MATCH,DIRECT) loads here, apart from the tunnel's profile. */
    fun pingDir(context: Context) = File(homeDir(context), "ping")

    /**
     * Clash-profile proxies can only be measured by a core. With the tunnel up that is the running one;
     * without it the `:mihomo` process loads a ping-only config (no TUN, no VPN) for the duration, the
     * same way desktop runs a throwaway mihomo — so pings work before connecting, not only after.
     */
    override suspend fun pingProfile(servers: List<Server>, profile: Profile, url: String, onResult: (String, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        api?.let { client ->
            measure(client, servers, url, onResult)
            return@withContext
        }
        val proxies = servers.mapNotNull { it.mihomo }
        if (proxies.isEmpty()) {
            servers.forEach { onResult(it.id, -1) }
            return@withContext
        }
        pingLock.withLock {
            val controller = freePort()
            val secret = randomSecret()
            val dir = pingDir(app).apply { mkdirs() }
            val cfg = kotlinx.serialization.json.buildJsonObject {
                put("log-level", kotlinx.serialization.json.JsonPrimitive("silent"))
                put("proxies", kotlinx.serialization.json.JsonArray(proxies))
                put("rules", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("MATCH,DIRECT"))))
            }
            File(dir, "config.yaml").writeText(JsonX.encodeToString(JsonObject.serializer(), cfg))
            // A plain start (not foreground, no VPN): only ever asked while the app is on screen.
            val started = runCatching {
                app.startService(
                    Intent(app, MihomoVpnService::class.java).setAction(MihomoVpnService.ACTION_PING)
                        .putExtra(MihomoVpnService.EXTRA_CONTROLLER, controller)
                        .putExtra(MihomoVpnService.EXTRA_SECRET, secret),
                )
            }.isSuccess
            val client = MihomoApi(controller, secret)
            try {
                var ready = false
                val deadline = System.currentTimeMillis() + 10_000
                while (started && System.currentTimeMillis() < deadline) {
                    if (client.ready()) { ready = true; break }
                    delay(100)
                }
                if (ready) measure(client, servers, url, onResult, timeoutMs = 6000)
                else servers.forEach { onResult(it.id, -1) }
            } finally {
                client.close()
                runCatching { app.startService(Intent(app, MihomoVpnService::class.java).setAction(MihomoVpnService.ACTION_PING_DONE)) }
            }
        }
    }

    private suspend fun measure(client: MihomoApi, servers: List<Server>, url: String, onResult: (String, Long) -> Unit, timeoutMs: Int = 5000) {
        val gate = kotlinx.coroutines.sync.Semaphore(12)
        kotlinx.coroutines.coroutineScope {
            servers.forEach { s -> launch { gate.withPermit { onResult(s.id, client.delay(s.name, url, timeoutMs)) } } }
        }
    }

    private fun randomSecret(): String {
        val rnd = java.security.SecureRandom()
        return (1..24).map { "abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)] }.joinToString("")
    }

    override fun coreVersion(): String = version?.let { "Mihomo $it" } ?: "Mihomo (Prizrak-Core)"

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
