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
import app.ghostly.core.vpn.CoreLog
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnState
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import app.ghostly.core.mihomo.MihomoProfiles
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** A running (or starting) tunnel: enough to reattach to it from a fresh app process. */
    private data class Session(
        val serverId: String,
        val profileId: String?,
        val controller: Int,
        val secret: String,
        val appPort: Int,
        val since: Long = 0,
    ) {
        fun toJson(): String = buildJsonObject {
            put("serverId", serverId)
            put("profileId", profileId)
            put("controller", controller)
            put("secret", secret)
            put("appPort", appPort)
            put("since", since)
        }.toString()

        companion object {
            fun fromJson(text: String): Session {
                val o = JsonX.parseToJsonElement(text).jsonObject
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                return Session(
                    serverId = str("serverId")!!,
                    profileId = str("profileId"),
                    controller = str("controller")!!.toInt(),
                    secret = str("secret")!!,
                    appPort = str("appPort")!!.toInt(),
                    since = str("since")?.toLongOrNull() ?: 0,
                )
            }
        }
    }

    @Volatile private var pending: Session? = null
    @Volatile private var pendingPicks: List<MihomoPick> = emptyList()
    @Volatile private var running: Session? = null
    private var trafficJob: Job? = null
    private var logJob: Job? = null
    @Volatile private var version: String? = null
    /** mihomo log level of the current tunnel ("none" = off). */
    @Volatile private var logLevel: String = "warning"

    /** The core runs in another process: its log comes over the controller's /logs stream. */
    override val coreLog = CoreLog()

    /** mihomo's home in the `:mihomo` processes (the bridge uses files/clash). */
    fun homeDir(context: Context) = File(context.filesDir, "clash")
    fun profileDir(context: Context) = File(homeDir(context), "ghostly")
    private fun pingDir(context: Context) = File(homeDir(context), "ping")
    private val sessionFile get() = File(profileDir(app), "session.json")

    fun init(context: Context) {
        app = context.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onServiceState(intent)
        }, IntentFilter(MihomoVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(MihomoPingService.EXTRA_SESSION) == pingSession) {
                    pingReady?.complete(intent.getBooleanExtra(MihomoPingService.EXTRA_OK, false))
                }
            }
        }, IntentFilter(MihomoPingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { reattach() }
    }

    /**
     * The app process was restarted while the tunnel kept running in `:mihomo` (Android freed memory,
     * or the app was updated): pick the running core up again instead of showing "disconnected".
     */
    private suspend fun reattach() {
        val saved = runCatching { Session.fromJson(sessionFile.readText()) }.getOrNull() ?: return
        val client = MihomoApi(saved.controller, saved.secret)
        if (!client.ready()) {
            client.close()
            runCatching { sessionFile.delete() }
            return
        }
        if (state.value !is VpnState.Idle) {
            client.close()
            return
        }
        attach(saved, client)
    }

    override suspend fun connect(server: Server, profile: Profile?, settings: AppSettings): Unit = withContext(Dispatchers.IO) {
        stopLocal()
        mutableState.value = VpnState.Connecting
        logLevel = settings.logLevel
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
            val plan = MihomoConfigBuilder.build(server, profile, settings, ingress)
            val dir = profileDir(app).apply { mkdirs() }
            // The bridge moves provider files under <profile>/providers/.
            plan.links?.let { File(dir, "providers").apply { mkdirs() }.resolve(MihomoConfigBuilder.LINKS_FILE).writeText(it) }
            File(dir, "config.yaml").writeText(JsonX.encodeToString(JsonObject.serializer(), plan.config))
            copyGeoData()
            pending = Session(server.id, profile?.id, controller, secret, appPort0)
            pendingPicks = plan.picks

            val intent = Intent(app, MihomoVpnService::class.java)
                .setAction(MihomoVpnService.ACTION_START)
                .putExtra(MihomoVpnService.EXTRA_NAME, server.name)
                .putExtra(MihomoVpnService.EXTRA_MTU, settings.mtu)
                .putExtra(MihomoVpnService.EXTRA_IPV6, settings.ipv6)
                .putExtra(MihomoVpnService.EXTRA_SPLIT_MODE, settings.splitMode.name)
                .putExtra(MihomoVpnService.EXTRA_SPLIT_APPS, settings.splitApps.toTypedArray())
            ContextCompat.startForegroundService(app, intent)
            Unit
        } catch (e: Exception) {
            mutableState.value = VpnState.Failed("mihomo: ${e.message ?: "не удалось подготовить конфиг"}")
        }
    }

    override suspend fun disconnect() {
        if (state.value == VpnState.Idle) return
        mutableState.value = VpnState.Disconnecting
        runCatching { app.startService(Intent(app, MihomoVpnService::class.java).setAction(MihomoVpnService.ACTION_STOP)) }
            .onFailure {
                // The service can't be reached (process gone): nothing left to stop.
                stopLocal()
                mutableState.value = VpnState.Idle
            }
    }

    private fun onServiceState(intent: Intent) {
        when (intent.getStringExtra(MihomoVpnService.EXTRA_STATE)) {
            MihomoVpnService.STATE_CONNECTED -> {
                val p = pending ?: return
                val picks = pendingPicks
                scope.launch {
                    val client = MihomoApi(p.controller, p.secret)
                    // The core answers once the config is applied; normally right away.
                    var tries = 0
                    while (!client.ready() && tries++ < 50) delay(100)
                    client.applyPicks(picks)
                    attach(p.copy(since = System.currentTimeMillis()), client)
                }
            }
            MihomoVpnService.STATE_FAILED -> {
                stopLocal()
                val message = intent.getStringExtra(MihomoVpnService.EXTRA_MESSAGE) ?: "mihomo не запустился"
                coreLog.add("error: $message")
                mutableState.value = VpnState.Failed(message)
            }
            MihomoVpnService.STATE_IDLE -> {
                stopLocal()
                if (state.value !is VpnState.Failed) mutableState.value = VpnState.Idle
            }
        }
    }

    private suspend fun attach(session: Session, client: MihomoApi) {
        version = client.version()
        api = client
        appPort = session.appPort
        running = session
        pending = null
        runCatching { sessionFile.writeText(session.toJson()) }
        mutableTraffic.value = Traffic()
        mutableState.value = VpnState.Connected(session.since.takeIf { it > 0 } ?: System.currentTimeMillis(), session.serverId)
        startWatch(client)
        startLog(client)
    }

    private fun startLog(client: MihomoApi) {
        logJob?.cancel()
        val level = when (logLevel) {
            "none" -> return
            "debug", "info", "warning", "error" -> logLevel
            else -> "warning"
        }
        logJob = scope.launch {
            while (isActive) {
                runCatching { client.logs(level).collect { coreLog.add(it) } }
                delay(2000)
            }
        }
    }

    /**
     * Speed from the controller's /traffic stream. When the stream breaks and the controller stops
     * answering, the `:mihomo` process is gone (killed by the system): report it, so the controller
     * reconnects or fails over instead of showing a dead tunnel as connected.
     */
    private fun startWatch(client: MihomoApi) {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            var misses = 0
            while (isActive) {
                runCatching {
                    client.traffic().collect { (up, down) ->
                        misses = 0
                        upTotal += up
                        downTotal += down
                        mutableTraffic.value = Traffic(up, down, upTotal, downTotal)
                    }
                }
                if (!isActive) break
                if (client.ready()) misses = 0 else misses++
                if (misses >= 2) {
                    coreLog.add("error: процесс ядра mihomo закрыт системой")
                    stopLocal()
                    mutableState.value = VpnState.Failed("mihomo остановился (система закрыла процесс ядра)")
                    break
                }
                delay(1000)
            }
        }
    }

    private fun stopLocal() {
        trafficJob?.cancel()
        trafficJob = null
        logJob?.cancel()
        logJob = null
        api?.close()
        api = null
        appPort = null
        running = null
        runCatching { sessionFile.delete() }
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

    /**
     * Clash-profile proxies are measured by mihomo itself: through the running core when it runs this
     * very profile, otherwise by a throwaway core in the `:mihomoping` process (no VPN needed).
     */
    override suspend fun pingProfile(servers: List<Server>, profile: Profile, url: String, onResult: (String, Long) -> Unit) {
        val live = api
        if (live != null && running?.profileId == profile.id) {
            measure(live, servers, url, onResult)
            return
        }
        pingOffline(servers, url, onResult)
    }

    private val pingLock = Mutex()
    @Volatile private var pingSession: String? = null
    @Volatile private var pingReady: CompletableDeferred<Boolean>? = null

    private suspend fun pingOffline(servers: List<Server>, url: String, onResult: (String, Long) -> Unit) = pingLock.withLock {
        withContext(Dispatchers.IO) {
            val controller = freePort()
            val secret = randomSecret()
            val dir = pingDir(app).apply { mkdirs() }
            File(dir, "config.yaml").writeText(JsonX.encodeToString(JsonObject.serializer(), MihomoProfiles.pingConfig(servers, controller, secret)))
            val session = randomSecret()
            val ready = CompletableDeferred<Boolean>()
            pingSession = session
            pingReady = ready
            val started = runCatching {
                app.startService(
                    Intent(app, MihomoPingService::class.java)
                        .setAction(MihomoPingService.ACTION_START)
                        .putExtra(MihomoPingService.EXTRA_DIR, dir.absolutePath)
                        .putExtra(MihomoPingService.EXTRA_SESSION, session),
                )
            }.isSuccess
            val client = MihomoApi(controller, secret)
            try {
                // A start from the background is refused by Android 8+: then there is nothing to measure with.
                var ok = started && withTimeoutOrNull(12_000) { ready.await() } == true
                if (ok) {
                    var tries = 0
                    while (!client.ready() && tries < 30) { tries++; delay(100) }
                    ok = tries < 30
                }
                if (!ok) servers.forEach { onResult(it.id, -1) }
                else measure(client, servers, url, onResult)
            } finally {
                client.close()
                pingReady = null
                runCatching { app.startService(Intent(app, MihomoPingService::class.java).setAction(MihomoPingService.ACTION_STOP)) }
            }
        }
    }

    private suspend fun measure(client: MihomoApi, servers: List<Server>, url: String, onResult: (String, Long) -> Unit) {
        val gate = Semaphore(12)
        coroutineScope {
            servers.forEach { s -> launch { gate.withPermit { onResult(s.id, client.delayRetry(s.name, url, 6000)) } } }
        }
    }

    override fun coreVersion(): String = version?.let { "mihomo $it" } ?: "mihomo (Prizrak-Core)"

    private fun randomSecret(): String {
        val rnd = java.security.SecureRandom()
        return (1..24).map { "abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)] }.joinToString("")
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
