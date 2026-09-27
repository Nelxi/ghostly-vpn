package app.ghostly.desktop

import app.ghostly.core.JsonX
import app.ghostly.core.mihomo.MihomoApi
import app.ghostly.core.mihomo.MihomoConfigBuilder
import app.ghostly.core.mihomo.MihomoCore
import app.ghostly.core.mihomo.MihomoIngress
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.DesktopMode
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Desktop mihomo: runs Prizrak-Core (legiz-ru's mihomo build) as a child process next to Xray.
 * TUN mode: the core makes the adapter and routes (admin/root). Proxy modes: listeners + OS proxy.
 * Groups, selectors, traffic and delays go through the core's REST controller on loopback.
 */
class DesktopMihomoBackend(private val platform: DesktopPlatform, private val xray: DesktopXrayBackend) : MihomoCore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    override val state: StateFlow<VpnState> = _state.asStateFlow()
    private val _traffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private var process: Process? = null
    private var watchJob: Job? = null
    private var proxyEnabled = false
    @Volatile private var tunMode = false
    private val osPortFile get() = File(platform.dataDir, "run/os-proxy-port")

    @Volatile override var api: MihomoApi? = null
        private set
    @Volatile override var appPort: Int? = null
        private set
    private val log = ArrayDeque<String>()

    override suspend fun coreLogs(): String? =
        synchronized(log) { log.toList() }.takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** mihomo's home: config, provider files, geo data, cache. */
    private val home: File get() = File(platform.dataDir, "mihomo").apply { mkdirs() }

    val exe: File get() = File(xray.coreDir, exeName)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stopBlocking() })
    }

    override suspend fun connect(server: Server, profile: Profile?, settings: AppSettings, stored: Map<String, String>): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            stopBlocking()
            _state.value = VpnState.Connecting
            if (!exe.isFile) {
                _state.value = VpnState.Failed("Не найдено ядро Mihomo (${exe.absolutePath})")
                return@withContext
            }
            val tun = settings.desktopMode == DesktopMode.TUN
            tunMode = tun
            if (tun && !xray.isElevated()) {
                _state.value = VpnState.Failed("Режиму TUN нужны права администратора. Запусти Ghostly от имени администратора или выбери «Системный прокси» в настройках.")
                return@withContext
            }
            val appPort0 = DesktopXrayBackend.freePort()
            val osPort = if (settings.desktopMode == DesktopMode.SYSTEM_PROXY) DesktopXrayBackend.freePort() else 0
            val controller = DesktopXrayBackend.freePort()
            val secret = randomSecret()
            val ingress = MihomoIngress(
                controllerPort = controller,
                secret = secret,
                appPort = appPort0,
                proxy = XrayConfigBuilder.localProxy(settings),
                osHttpPort = osPort,
                tun = if (tun) MihomoIngress.Tun(if (hostOs == HostOs.MACOS) "utun199" else "Ghostly", settings.mtu) else null,
            )
            val plan = MihomoConfigBuilder.build(server, profile, settings, ingress, stored = stored)
            val dir = home
            copyGeoData(dir)
            plan.links?.let { File(dir, MihomoConfigBuilder.LINKS_FILE).writeText(it) }
            val file = File(dir, "config.yaml")
            file.writeText(JsonX.encodeToString(JsonObject.serializer(), plan.config))

            val p = try {
                ProcessBuilder(exe.absolutePath, "-d", dir.absolutePath, "-f", file.absolutePath)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                _state.value = VpnState.Failed("Не удалось запустить Mihomo: ${e.message}")
                return@withContext
            }
            process = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line); while (log.size > 300) log.removeFirst() }
                }
            }.apply { isDaemon = true }.start()

            val client = MihomoApi(controller, secret)
            // Providers (and a first geo download) can take a moment; mihomo exits fast on a bad config.
            val deadline = System.currentTimeMillis() + 15_000
            var ready = false
            while (System.currentTimeMillis() < deadline && p.isAlive) {
                if (client.ready()) { ready = true; break }
                delay(60)
            }
            if (ready) ready = DesktopXrayBackend.waitForPort(appPort0, 5000) { p.isAlive }
            if (!p.isAlive || !ready) {
                if (p.isAlive) p.destroyForcibly()
                client.close()
                val tail = synchronized(log) { log.takeLast(4).joinToString("\n") }
                _state.value = VpnState.Failed(tail.ifBlank { "Mihomo не запустился" }.take(300))
                process = null
                return@withContext
            }
            client.applyPicks(plan.picks)
            if (!tun && settings.desktopMode == DesktopMode.SYSTEM_PROXY) {
                osPortFile.parentFile.mkdirs()
                osPortFile.writeText(osPort.toString())
                runCatching { SystemProxy.enable(osPort) }
                proxyEnabled = true
            }
            api = client
            appPort = appPort0
            _state.value = VpnState.Connected(System.currentTimeMillis(), server.id)
            watch(p, client)
        }
    }

    override suspend fun disconnect() = lock.withLock {
        withContext(Dispatchers.IO) {
            _state.value = VpnState.Disconnecting
            stopBlocking()
            _state.value = VpnState.Idle
        }
    }

    private fun stopBlocking() {
        appPort = null
        watchJob?.cancel()
        watchJob = null
        api?.close()
        api = null
        if (proxyEnabled) {
            SystemProxy.disable()
            proxyEnabled = false
        }
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        _traffic.value = Traffic()
    }

    /** Speed from the controller's /traffic stream; also notices the core dying. */
    private fun watch(p: Process, client: MihomoApi) {
        watchJob = scope.launch {
            launch {
                var upTotal = 0L
                var downTotal = 0L
                while (isActive) {
                    runCatching {
                        client.traffic().collect { (up, down) ->
                            upTotal += up
                            downTotal += down
                            _traffic.value = Traffic(up, down, upTotal, downTotal)
                        }
                    }
                    delay(1000)
                }
            }
            while (isActive) {
                delay(1000)
                if (!p.isAlive) {
                    val tail = synchronized(log) { log.takeLast(3).joinToString("\n") }
                    stopBlocking()
                    _state.value = VpnState.Failed("Mihomo остановился" + if (tail.isNotBlank()) ":\n$tail" else "")
                    break
                }
            }
        }
    }

    /** Xray's geoip.dat/geosite.dat are the same format mihomo reads in geodata mode: no download needed. */
    private fun copyGeoData(dir: File) {
        for ((from, to) in listOf("geoip.dat" to "GeoIP.dat", "geosite.dat" to "GeoSite.dat")) {
            val src = File(xray.coreDir, from)
            val dst = File(dir, to)
            if (src.isFile && (!dst.isFile || dst.length() != src.length())) runCatching { src.copyTo(dst, overwrite = true) }
        }
    }

    @Volatile private var coreVersionCache: String? = null

    override fun coreVersion(): String = coreVersionCache ?: runCatching {
        exec(exe.absolutePath, "-v").lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { coreVersionCache = it } ?: "Mihomo (не найдено)"

    override val directProbesBypassTunnel: Boolean get() = !tunMode

    override suspend fun healthCheck(url: String): Long = withContext(Dispatchers.IO) {
        val port = appPort ?: return@withContext -1L
        measure(port, url)
    }

    override suspend fun ping(server: Server, url: String): Long = -1

    /**
     * Clash-profile proxies: one throwaway core with just the proxies and a controller, then the
     * controller's delay test per proxy (mihomo measures through each proxy itself).
     */
    override suspend fun pingProfile(servers: List<Server>, profile: Profile, url: String, onResult: (String, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val proxies = servers.mapNotNull { it.mihomo }
        if (!exe.isFile || proxies.isEmpty()) {
            servers.forEach { onResult(it.id, -1) }
            return@withContext
        }
        val controller = DesktopXrayBackend.freePort()
        val secret = randomSecret()
        val dir = File(home, "ping").apply { mkdirs() }
        val cfg = buildJsonObject {
            put("log-level", "silent")
            put("external-controller", "127.0.0.1:$controller")
            put("secret", secret)
            put("proxies", JsonArray(proxies))
            put("rules", JsonArray(listOf(JsonPrimitive("MATCH,DIRECT"))))
        }
        val file = File(dir, "config.yaml").apply { writeText(JsonX.encodeToString(JsonObject.serializer(), cfg)) }
        val p = ProcessBuilder(exe.absolutePath, "-d", dir.absolutePath, "-f", file.absolutePath)
            .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val client = MihomoApi(controller, secret)
        try {
            val deadline = System.currentTimeMillis() + 6000
            var ready = false
            while (System.currentTimeMillis() < deadline && p.isAlive) {
                if (client.ready()) { ready = true; break }
                delay(50)
            }
            if (!ready) {
                servers.forEach { onResult(it.id, -1) }
                return@withContext
            }
            val gate = Semaphore(12)
            kotlinx.coroutines.coroutineScope {
                servers.forEach { s ->
                    launch { gate.withPermit { onResult(s.id, client.delay(s.name, url, 6000)) } }
                }
            }
        } finally {
            client.close()
            p.destroyForcibly()
        }
    }

    val recentLog: List<String> get() = synchronized(log) { log.toList() }

    private fun measure(port: Int, url: String): Long {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        var best = -1L
        repeat(2) {
            val t0 = System.nanoTime()
            val ok = runCatching {
                val c = URI(url).toURL().openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = false
                c.requestMethod = app.ghostly.core.vpn.Probe.httpMethod
                val code = c.responseCode
                c.disconnect()
                code in 200..399
            }.getOrDefault(false)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ok && (best < 0 || ms < best)) best = ms
        }
        return best
    }

    private val rng = java.security.SecureRandom()
    private fun randomSecret(): String = (1..24).map { "abcdefghijkmnpqrstuvwxyz23456789"[rng.nextInt(32)] }.joinToString("")

    companion object {
        val exeName = if (hostOs == HostOs.WINDOWS) "prizrak-core.exe" else "prizrak-core"
    }
}
