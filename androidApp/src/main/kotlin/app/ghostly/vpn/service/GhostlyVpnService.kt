package app.ghostly.vpn.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import app.ghostly.core.JsonX
import app.ghostly.core.model.SplitMode
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnState
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.XrayConfigBuilder
import app.ghostly.vpn.GhostlyApplication
import app.ghostly.vpn.MainActivity
import app.ghostly.vpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Foreground VpnService: establishes the TUN interface and runs Xray on its fd
 * (Xray's native `tun` inbound — no tun2socks in between).
 */
class GhostlyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    private var statsJob: Job? = null
    private var serverName: String = ""
    private var subscriptionName: String? = null
    private var connectedAt: Long? = null
    /** Outbounds that are not the tunnel (freedom/blackhole/dns): excluded from the speed meter. */
    private var serviceTags: Set<String> = emptySet()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stop(userInitiated = true, startId) }
            else -> {
                // ACTION_START from the app, or android.net.VpnService from always-on VPN / reboot.
                goForeground(getString(R.string.notif_connecting))
                scope.launch { start(startId) }
            }
        }
        return START_STICKY
    }

    private suspend fun start(startId: Int) = lock.withLock {
        val request = AndroidVpn.request ?: restoreRequest()
        if (request == null) {
            AndroidVpn.mutableState.value = VpnState.Failed("Нет выбранного сервера", retryable = false)
            stopSelfResult(startId)
            return@withLock
        }
        AndroidVpn.mutableState.value = VpnState.Connecting
        serverName = request.server.name
        subscriptionName = runCatching {
            GhostlyApplication.instance.controller.profiles.value.firstOrNull { p -> p.servers.any { it.id == request.server.id } }?.name
        }.getOrNull()
        connectedAt = null
        shutdownCore()

        // A fresh log per run: the core appends to it, support gets only this attempt.
        runCatching { AndroidVpn.logFile().apply { parentFile?.mkdirs() }.writeText("") }
        AndroidVpn.log("start · ${request.server.name} · ${request.server.protocol}/${request.server.transport ?: "tcp"}/${request.server.security ?: "none"}")

        try {
            AndroidVpn.ensureCore()
            val settings = request.settings
            val builder = Builder()
                .setSession("Ghostly · ${request.server.name}")
                .setMtu(settings.mtu)
                .addAddress(TUN_V4, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_V4)
                .setConfigureIntent(openAppIntent())
            if (settings.ipv6) {
                builder.addAddress(TUN_V6, 126)
                builder.addRoute("::", 0)
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            when (settings.splitMode) {
                SplitMode.ONLY_SELECTED -> settings.splitApps.forEach { runCatching { builder.addAllowedApplication(it) } }
                SplitMode.BYPASS_SELECTED -> {
                    builder.addDisallowedApplication(packageName)
                    settings.splitApps.forEach { runCatching { builder.addDisallowedApplication(it) } }
                }
                // Our own sockets (the core's uplinks) must bypass the tunnel, or they would loop into it.
                SplitMode.OFF -> builder.addDisallowedApplication(packageName)
            }
            val fd = builder.establish() ?: throw IllegalStateException("Нет разрешения на VPN")
            tun = fd

            val appPort = freePort()
            val ingress = Ingress.TunFd(
                settings.mtu,
                proxy = if (settings.localProxy) XrayConfigBuilder.localProxy(settings) else null,
                appPort = appPort,
            )
            val config = XrayConfigBuilder.build(request.server, settings, ingress, AndroidVpn.logFile().absolutePath)
            serviceTags = (config["outbounds"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { (it["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.content in setOf("freedom", "blackhole", "dns") }
                .mapNotNull { (it["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .toSet()
            val controller = Libv2ray.newCoreController(callback)
            controller.startLoop(JsonX.encodeToString(JsonObject.serializer(), config), fd.fd)
            if (!controller.isRunning) throw IllegalStateException("Ядро не запустилось")
            core = controller
            AndroidVpn.liveCore = controller

            AndroidVpn.appPort = appPort
            AndroidVpn.mutableTraffic.value = Traffic()
            val now = System.currentTimeMillis()
            connectedAt = now
            AndroidVpn.mutableState.value = VpnState.Connected(now, request.server.id)
            updateNotification()
            watchNetwork()
            startStats()
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            AndroidVpn.log("start failed: ${e.message ?: e::class.simpleName}")
            shutdownCore()
            AndroidVpn.mutableState.value = VpnState.Failed(humanError(e))
            // Only when no newer command is waiting: a quick off-on (or a retry) queues its start behind
            // this one, and a plain stopSelf() would destroy the service with that start still pending —
            // the app then sat on "disconnected" although the user had asked to connect.
            if (stopSelfResult(startId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /** After a reboot / always-on start there is no in-memory request: rebuild it from saved state. */
    private fun restoreRequest(): TunnelRequest? {
        val controller = GhostlyApplication.instance.controller
        val server = controller.selectedServer() ?: return null
        return TunnelRequest(server, controller.settings.value).also { AndroidVpn.request = it }
    }

    /** [startId]: the command that asked for the stop; null = stop whatever is running (revoked by the system). */
    private suspend fun stop(userInitiated: Boolean, startId: Int? = null) = lock.withLock {
        shutdownCore()
        AndroidVpn.mutableTraffic.value = Traffic()
        // A start that arrived after this stop (quick off-on) is already waiting for the lock: leave the
        // service and the "connecting" state to it instead of tearing both down under its feet.
        if (startId != null && !stopSelfResult(startId)) return@withLock
        AndroidVpn.mutableState.value = VpnState.Idle
        if (userInitiated) AndroidVpn.request = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf()
    }

    private fun shutdownCore() {
        statsJob?.cancel()
        statsJob = null
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        AndroidVpn.appPort = null
        AndroidVpn.liveCore = null
        core?.let { runCatching { it.stopLoop() } }
        core = null
        tun?.let { runCatching { it.close() } }
        tun = null
    }

    /** Traffic counters → speed; also refreshes the notification once a second. */
    private fun startStats() {
        statsJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            while (isActive) {
                delay(1000)
                val raw = core?.queryAllOutboundTrafficStats().orEmpty()
                var up = 0L
                var down = 0L
                raw.split(';').forEach { entry ->
                    val p = entry.split(',')
                    if (p.size == 3 && p[0] !in serviceTags) {
                        val v = p[2].toLongOrNull() ?: 0L
                        if (p[1] == "uplink") up += v else down += v
                    }
                }
                upTotal += up
                downTotal += down
                AndroidVpn.mutableTraffic.value = Traffic(up, down, upTotal, downTotal)
                updateNotification(VpnNotification.Stats(up, down, upTotal, downTotal))
            }
        }
    }

    /** Point the tunnel at the current physical network so Android shows the right transport/metering. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = setUnderlyingNetworks(arrayOf(network)).let { }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = setUnderlyingNetworks(arrayOf(network)).let { }
            override fun onLost(network: Network) = setUnderlyingNetworks(null).let { }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.requestNetwork(req, cb) }.onSuccess { networkCallback = cb }
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked us in system settings.
        scope.launch { stop(userInitiated = true) }
    }

    override fun onDestroy() {
        shutdownCore()
        if (AndroidVpn.state.value !is VpnState.Failed) AndroidVpn.mutableState.value = VpnState.Idle
        scope.cancel()
        super.onDestroy()
    }

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            Log.i(TAG, "core: $message")
            if (!message.isNullOrBlank()) AndroidVpn.log("core: $message")
            return 0
        }
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(status: String) {
        VpnNotification.ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(status = status),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun updateNotification(stats: VpnNotification.Stats? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(stats))
    }

    private fun notification(stats: VpnNotification.Stats? = null, status: String? = null): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GhostlyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return VpnNotification.build(
            this, VpnNotification.Core.XRAY, serverName, subscriptionName,
            connectedAt = if (status == null) connectedAt else null,
            stats = stats, open = openAppIntent(), stop = stop, status = status,
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun humanError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "config" in m.lowercase() -> "Ошибка конфигурации: $m"
            m.isNotBlank() -> m
            else -> "Не удалось подключиться"
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    companion object {
        const val ACTION_START = "app.ghostly.vpn.START"
        const val ACTION_STOP = "app.ghostly.vpn.STOP"
        private const val TAG = "GhostlyVpn"
        private const val NOTIFICATION_ID = 7
        private const val TUN_V4 = "172.19.0.1"
        private const val TUN_V6 = "fdfe:dcba:9876::1"
        private const val DNS_V4 = "1.1.1.1"
    }
}
