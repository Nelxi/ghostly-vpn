package app.ghostly.desktop

import app.ghostly.core.JsonX
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.DesktopMode
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import app.ghostly.core.xray.Ingress
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Desktop [VpnBackend]: runs the official Xray binary as a child process.
 * TUN mode: Xray creates a Wintun/utun/tun adapter and installs routes itself (needs admin/root).
 * System proxy mode: local SOCKS/HTTP inbounds + the OS proxy setting.
 */
class DesktopXrayBackend(private val platform: DesktopPlatform) : VpnBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    override val state: StateFlow<VpnState> = _state.asStateFlow()
    private val _traffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private var process: Process? = null
    private var statsJob: Job? = null
    private var proxyEnabled = false
    private var apiPort = 0
    private val osPortFile get() = File(platform.dataDir, "run/os-proxy-port")

    @Volatile override var appPort: Int? = null
        private set
    private val log = ArrayDeque<String>()

    /** Folder with xray(.exe), wintun.dll and geo files. */
    val coreDir: File = run {
        System.getProperty("compose.application.resources.dir")?.let { File(it) }?.takeIf { File(it, exeName).isFile }
            ?: listOf("desktopApp/core", "core").map { File(it) }.firstNotNullOfOrNull { root ->
                root.listFiles()?.firstOrNull { File(it, exeName).isFile }
            }
            ?: File(".")
    }

    private val exe: File get() = File(coreDir, exeName)

    init {
        // A previous run that crashed may have left the OS proxy pointing at a dead port.
        scope.launch {
            val last = osPortFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (last != null && SystemProxy.isOurs(last)) SystemProxy.disable()
        }
        Runtime.getRuntime().addShutdownHook(Thread { stopBlocking() })
    }

    fun isElevated(): Boolean = runCatching {
        when (hostOs) {
            HostOs.WINDOWS -> ProcessBuilder("net", "session").redirectErrorStream(true).start().let { it.inputStream.readAllBytes(); it.waitFor() == 0 }
            else -> exec("id", "-u").trim() == "0"
        }
    }.getOrDefault(false)

    override suspend fun connect(server: Server, settings: AppSettings) = lock.withLock {
        withContext(Dispatchers.IO) {
            stopBlocking()
            killOrphanCores(exe)
            _state.value = VpnState.Connecting
            if (!exe.isFile) {
                _state.value = VpnState.Failed("Не найдено ядро Xray (${exe.absolutePath})")
                return@withContext
            }
            val tun = settings.desktopMode == DesktopMode.TUN
            tunMode = tun
            if (tun && !isElevated()) {
                _state.value = VpnState.Failed("Режиму TUN нужны права администратора. Запусти Ghostly от имени администратора или выбери «Системный прокси» в настройках.")
                return@withContext
            }
            val proxy = XrayConfigBuilder.localProxy(settings)
            val appPort0 = freePort()
            val osPort = if (settings.desktopMode == DesktopMode.SYSTEM_PROXY) freePort() else 0
            val ingress = if (tun) Ingress.TunSystem(settings.mtu, if (hostOs == HostOs.MACOS) "utun99" else "ghostly", proxy, appPort0)
            else Ingress.Proxy(proxy, osPort, appPort0)

            apiPort = freePort()
            val config = withStatsApi(XrayConfigBuilder.build(server, settings, ingress), apiPort)
            val file = File(platform.dataDir, "run").apply { mkdirs() }.resolve("config.json")
            file.writeText(JsonX.encodeToString(JsonObject.serializer(), config))

            val p = try {
                ProcessBuilder(exe.absolutePath, "run", "-c", file.absolutePath)
                    .directory(coreDir)
                    .redirectErrorStream(true)
                    .apply { environment()["XRAY_LOCATION_ASSET"] = coreDir.absolutePath }
                    .start()
            } catch (e: Exception) {
                _state.value = VpnState.Failed("Не удалось запустить ядро: ${e.message}")
                return@withContext
            }
            WindowsJob.adopt(p)  // the core ends with Ghostly, even if the app crashes
            process = p
            synchronized(log) { log.clear() }
            Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line); while (log.size > 2000) log.removeFirst() }
                }
            }.apply { isDaemon = true }.start()

            // Ready as soon as the local inbound answers (usually ~100 ms); Xray dies fast on a bad config.
            val ready = waitForPort(appPort0, 5000) { p.isAlive }
            if (!p.isAlive || !ready) {
                if (p.isAlive) p.destroyForcibly()
                val tail = synchronized(log) { log.takeLast(4).joinToString("\n") }
                _state.value = VpnState.Failed(tail.ifBlank { "Ядро не запустилось" }.take(300))
                process = null
                return@withContext
            }
            if (!tun && settings.desktopMode == DesktopMode.SYSTEM_PROXY) {
                osPortFile.writeText(osPort.toString())
                runCatching { SystemProxy.enable(osPort) }
                proxyEnabled = true
            }
            appPort = appPort0
            _state.value = VpnState.Connected(System.currentTimeMillis(), server.id)
            startStats(p)
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
        statsJob?.cancel()
        statsJob = null
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

    /** Adds Xray's StatsService on a loopback port so we can read traffic counters. */
    private fun withStatsApi(config: JsonObject, port: Int): JsonObject {
        val inbounds = config["inbounds"]!!.jsonArray + buildJsonObject {
            put("tag", "api-in")
            put("listen", "127.0.0.1")
            put("port", port)
            put("protocol", "dokodemo-door")
            putJsonObject("settings") { put("address", "127.0.0.1") }
        }
        val routing = config["routing"]!!.jsonObject
        val rules = buildJsonObject {
            put("type", "field")
            putJsonArray("inboundTag") { add(JsonPrimitive("api-in")) }
            put("outboundTag", "api")
        }
        val newRouting = JsonObject(routing + ("rules" to JsonArray(listOf(rules) + routing["rules"]!!.jsonArray)))
        return JsonObject(
            config + mapOf(
                "inbounds" to JsonArray(inbounds),
                "routing" to newRouting,
                "api" to buildJsonObject { put("tag", "api"); putJsonArray("services") { add(JsonPrimitive("StatsService")) } },
            ),
        )
    }

    private fun startStats(p: Process) {
        val serviceTags = setOf("direct", "block", "dns-out", "fragment", "api")
        statsJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            while (isActive) {
                delay(1000)
                if (!p.isAlive) {
                    val tail = synchronized(log) { log.takeLast(3).joinToString("\n") }
                    stopBlocking()
                    _state.value = VpnState.Failed("Ядро остановилось" + if (tail.isNotBlank()) ":\n$tail" else "")
                    break
                }
                val out = runCatching {
                    exec(exe.absolutePath, "api", "statsquery", "--server=127.0.0.1:$apiPort", "-pattern", "outbound>>>", "-reset")
                }.getOrNull() ?: continue
                var up = 0L
                var down = 0L
                runCatching {
                    JsonX.parseToJsonElement(out.substring(out.indexOf('{'))).jsonObject["stat"]?.jsonArray?.forEach { s ->
                        val name = s.jsonObject["name"]?.jsonPrimitive?.content ?: return@forEach
                        val v = s.jsonObject["value"]?.jsonPrimitive?.long ?: 0L
                        val parts = name.split(">>>")
                        if (parts.size == 4 && parts[1] !in serviceTags && !parts[1].endsWith("-direct")) {
                            if (parts[3] == "uplink") up += v else down += v
                        }
                    }
                }
                upTotal += up
                downTotal += down
                _traffic.value = Traffic(up, down, upTotal, downTotal)
            }
        }
    }

    /** One throwaway Xray per server: SOCKS inbound → that server, then time an HTTP request through it. */
    override suspend fun ping(server: Server, url: String): Long = withContext(Dispatchers.IO) {
        val base = XrayConfigBuilder.buildPing(server) ?: return@withContext -1L
        if (!exe.isFile) return@withContext -1L
        val port = freePort()
        val config = JsonObject(
            base + ("inbounds" to JsonArray(listOf(buildJsonObject {
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                putJsonObject("settings") { put("udp", false) }
            }))),
        )
        val file = File.createTempFile("ghostly-ping", ".json")
        file.writeText(JsonX.encodeToString(JsonObject.serializer(), config))
        val p = ProcessBuilder(exe.absolutePath, "run", "-c", file.absolutePath)
            .directory(coreDir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["XRAY_LOCATION_ASSET"] = coreDir.absolutePath }
            .start()
        try {
            if (!waitForPort(port, 4000)) return@withContext -1L
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
            best
        } finally {
            p.destroyForcibly()
            file.delete()
        }
    }

    /**
     * All servers through ONE Xray process: a SOCKS inbound per server, each routed to its own
     * outbound. Far faster than a process per server (the default path).
     */
    override suspend fun pingMany(servers: List<Server>, url: String, onResult: (String, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (!exe.isFile || servers.isEmpty()) return@withContext
        // Only self-contained outbounds can share a process (no dialerProxy chains between tags).
        val batch = servers.mapNotNull { s ->
            val ob = (XrayConfigBuilder.buildPing(s)?.get("outbounds") as? JsonArray)?.firstOrNull() as? JsonObject
            if (ob == null || ob.toString().contains("dialerProxy")) null else s to ob
        }
        val rest = servers.filter { s -> batch.none { it.first.id == s.id } }
        if (rest.isNotEmpty()) super.pingMany(rest, url, onResult)
        if (batch.isEmpty()) return@withContext

        val ports = batch.map { freePort() }
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", "none") }
            put("inbounds", JsonArray(batch.indices.map { i ->
                buildJsonObject {
                    put("tag", "in$i"); put("listen", "127.0.0.1"); put("port", ports[i]); put("protocol", "socks")
                    putJsonObject("settings") { put("udp", false) }
                }
            }))
            put("outbounds", JsonArray(batch.mapIndexed { i, (_, ob) -> JsonObject(ob + ("tag" to JsonPrimitive("out$i"))) }))
            putJsonObject("routing") {
                put("rules", JsonArray(batch.indices.map { i ->
                    buildJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") { add(JsonPrimitive("in$i")) }
                        put("outboundTag", "out$i")
                    }
                }))
            }
        }
        val file = File.createTempFile("ghostly-ping", ".json")
        file.writeText(JsonX.encodeToString(JsonObject.serializer(), config))
        val p = ProcessBuilder(exe.absolutePath, "run", "-c", file.absolutePath)
            .directory(coreDir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["XRAY_LOCATION_ASSET"] = coreDir.absolutePath }
            .start()
        try {
            if (!waitForPort(ports.last(), 5000) { p.isAlive }) {
                batch.forEach { onResult(it.first.id, -1) }
                return@withContext
            }
            kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(12)
                batch.forEachIndexed { i, (s, _) ->
                    launch {
                        gate.acquire()
                        try {
                            onResult(s.id, measure(ports[i], url))
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        } finally {
            p.destroyForcibly()
            file.delete()
        }
    }

    @Volatile private var tunMode = false

    override val directProbesBypassTunnel: Boolean get() = !tunMode

    override suspend fun healthCheck(url: String): Long = withContext(Dispatchers.IO) {
        val port = appPort ?: return@withContext -1L
        measure(port, url)
    }

    /** Best of two HTTP round-trips through a local SOCKS port. */
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

    override fun coreVersion(): String = runCatching {
        exec(exe.absolutePath, "version").lineSequence().firstOrNull()?.trim()
    }.getOrNull() ?: "Xray (не найдено)"

    val recentLog: List<String> get() = synchronized(log) { log.toList() }

    override suspend fun coreLogs(): String? = synchronized(log) { log.toList() }.takeIf { it.isNotEmpty() }?.joinToString("\n")

    companion object {
        val exeName = if (hostOs == HostOs.WINDOWS) "xray.exe" else "xray"

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun waitForPort(port: Int, timeoutMs: Long, alive: () -> Boolean = { true }): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && alive()) {
                val ok = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
                if (ok) return true
                Thread.sleep(25)
            }
            return false
        }
    }
}
