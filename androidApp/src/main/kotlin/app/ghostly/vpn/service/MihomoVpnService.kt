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
import android.util.Log
import androidx.core.app.ServiceCompat
import app.ghostly.core.model.SplitMode
import app.ghostly.vpn.MainActivity
import app.ghostly.vpn.R
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground VpnService running mihomo (Prizrak-Core via the ClashMetaForAndroid bridge) in the
 * separate `:mihomo` process. It owns the TUN fd and the core; everything else — config, groups,
 * traffic — is done by [AndroidMihomo] in the app process over the core's REST controller.
 * When the tunnel stops, the process ends, so the core never lingers.
 */
class MihomoVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var running = false
    private var serverName = ""
    private var subscriptionName: String? = null
    private var connectedAt: Long? = null
    private var statsJob: kotlinx.coroutines.Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var logcatJob: kotlinx.coroutines.Job? = null

    // ------------------------------------------------------------------ core log
    // Everything the core and this service do lands in files/clash/logs/core.log, one file per
    // start, so the app can show/copy it when mihomo misbehaves (the :mihomo process dies with the
    // tunnel and takes stdout with it — a file is the only trace that survives). The app process
    // appends its own lines to the same file, so every write here must be an O_APPEND append too:
    // a writer that keeps its own offset would silently overwrite the other process's lines.

    private val logTime = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    @Synchronized
    private fun log(line: String) {
        Log.i(TAG, line)
        runCatching { AndroidMihomo.logFile(this).appendText(logTime.format(java.util.Date()) + " " + line + "\n") }
    }

    private fun startLog() {
        runCatching {
            val f = AndroidMihomo.logFile(this)
            f.parentFile?.mkdirs()
            f.writeText("")
        }
        if (logcatJob == null) logcatJob = scope.launch {
            for (m in Clash.subscribeLogcat()) log("[${m.level.name.lowercase()}] ${m.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stop() }
            ACTION_START -> {
                // A start right after a stop: the stop's delayed process kill must not take this start with it.
                killJob?.cancel()
                killJob = null
                serverName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                subscriptionName = intent.getStringExtra(EXTRA_SUBSCRIPTION)
                connectedAt = null
                goForeground(getString(R.string.notif_connecting))
                scope.launch { start(intent) }
            }
            ACTION_PING -> {
                killJob?.cancel()
                killJob = null
                scope.launch { pingCore(intent) }
            }
            ACTION_PING_DONE -> scope.launch { pingDone() }
            // Restarted by the system without our request (START_STICKY after a kill): nothing to resume.
            else -> scope.launch { stop() }
        }
        return START_NOT_STICKY
    }

    private suspend fun start(intent: Intent) = lock.withLock {
        try {
            startLog()
            pinging = false
            if (running) runCatching { Clash.stopTun() }
            val mtu = intent.getIntExtra(EXTRA_MTU, 1500)
            val ipv6 = intent.getBooleanExtra(EXTRA_IPV6, false)
            val split = runCatching { SplitMode.valueOf(intent.getStringExtra(EXTRA_SPLIT_MODE) ?: "") }.getOrDefault(SplitMode.OFF)
            val apps = intent.getStringArrayExtra(EXTRA_SPLIT_APPS).orEmpty()
            log("start: server=$serverName mtu=$mtu ipv6=$ipv6 split=$split apps=${apps.size}")

            // The bridge erases external-controller from the profile itself (patchExternalController
            // in Prizrak-Box's config processor) — the controller only survives through the override
            // slot, the way ClashMetaForAndroid sets it. Without this the REST API never listens:
            // selector picks, groups and traffic stats silently die while the tunnel "connects".
            val controller = intent.getIntExtra(EXTRA_CONTROLLER, 0)
            val secret = intent.getStringExtra(EXTRA_SECRET)
            if (controller > 0) {
                Clash.patchOverride(
                    Clash.OverrideSlot.Session,
                    com.github.kr328.clash.core.model.ConfigurationOverride().apply {
                        externalController = "127.0.0.1:$controller"
                        this.secret = secret
                        // The core's own lines (config, rules, dials) are the diagnostics we keep.
                        logLevel = com.github.kr328.clash.core.model.LogMessage.Level.Info
                    },
                )
                log("controller override set: 127.0.0.1:$controller")
            } else log("no controller port in intent — REST API will be down")

            // Config first: a broken profile fails here, before the VPN takes over the network.
            Clash.load(AndroidMihomo.profileDir(this)).await()
            log("config loaded from ${AndroidMihomo.profileDir(this)}")

            val builder = Builder()
                .setSession("Ghostly · $serverName")
                .setMtu(mtu)
                .addAddress(TUN_GATEWAY, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS)
                .setBlocking(false)
                // Like Prizrak Box: apps that legitimately manage their own sockets may bypass.
                .allowBypass()
                .setConfigureIntent(openAppIntent())
            if (ipv6) {
                builder.addAddress(TUN_GATEWAY6, 126)
                builder.addRoute("::", 0)
                builder.addDnsServer(TUN_DNS6)
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            // Our own package must stay INSIDE the tunnel, exactly like Prizrak Box's TunService
            // (`allInclude + packageName` / `allExclude - packageName`). The "system" stack answers
            // every app connection from a socket this process listens on at the TUN gateway; if our
            // UID is excluded, Android routes those replies past the tunnel and no connection ever
            // completes — "connected", but no traffic. The core's own uplinks don't loop back: each
            // one is protect()-ed through markSocket below.
            when (split) {
                SplitMode.ONLY_SELECTED -> (apps.toSet() + packageName).forEach { runCatching { builder.addAllowedApplication(it) } }
                SplitMode.BYPASS_SELECTED -> (apps.toSet() - packageName).forEach { runCatching { builder.addDisallowedApplication(it) } }
                SplitMode.OFF -> Unit
            }
            val fd = builder.establish()?.detachFd() ?: throw IllegalStateException("Нет разрешения на VPN")

            val connectivity = getSystemService(ConnectivityManager::class.java)
            Clash.startTun(
                fd = fd,
                stack = "system",
                gateway = "$TUN_GATEWAY/30" + if (ipv6) ",$TUN_GATEWAY6/126" else "",
                portal = TUN_DNS + if (ipv6) ",$TUN_DNS6" else "",
                dns = "0.0.0.0",
                markSocket = { protect(it) },
                querySocketUid = { protocol, source, target ->
                    if (Build.VERSION.SDK_INT < 29) -1
                    else runCatching { connectivity.getConnectionOwnerUid(protocol, source, target) }.getOrDefault(-1)
                },
            )
            running = true
            watchNetwork()
            log("tun up (fd=$fd, stack=system, split=$split), own package inside the tunnel; reporting connected")
            report(STATE_CONNECTED)
            connectedAt = System.currentTimeMillis()
            updateNotification()
            if (controller > 0 && secret != null) startStats(controller, secret)
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            log("start failed: " + Log.getStackTraceString(e))
            report(STATE_FAILED, e.message?.takeIf { it.isNotBlank() }?.let { "Mihomo: $it" } ?: "Mihomo не запустился")
            shutdown()
        }
    }

    // ------------------------------------------------------------------ ping-only core
    // Pings of Clash-profile proxies need a core. With no tunnel up the app asks this process to load
    // a proxies-only config with a controller (no TUN, no VPN, no foreground), measures over REST and
    // then lets it go. A real connect simply loads its own profile over it.

    private var pinging = false

    private suspend fun pingCore(intent: Intent): Unit = lock.withLock {
        if (running) return@withLock
        try {
            Clash.patchOverride(
                Clash.OverrideSlot.Session,
                com.github.kr328.clash.core.model.ConfigurationOverride().apply {
                    externalController = "127.0.0.1:" + intent.getIntExtra(EXTRA_CONTROLLER, 0)
                    secret = intent.getStringExtra(EXTRA_SECRET)
                },
            )
            Clash.load(AndroidMihomo.pingDir(this)).await()
            pinging = true
        } catch (e: Throwable) {
            Log.w(TAG, "ping core failed", e)
        }
        Unit
    }

    private suspend fun pingDone(): Unit = lock.withLock {
        if (running || !pinging) return@withLock
        pinging = false
        runCatching { Clash.reset() }
        runCatching { Clash.clearOverride(Clash.OverrideSlot.Session) }
        stopSelf()
        killJob = scope.launch {
            delay(300)
            if (!running) android.os.Process.killProcess(android.os.Process.myPid())
        }
        Unit
    }

    private suspend fun stop() = lock.withLock {
        report(STATE_IDLE)
        shutdown()
    }

    /** Stop the core and end the process: the next start loads a fresh one. */
    private fun shutdown() {
        log("shutdown")
        statsJob?.cancel()
        statsJob = null
        connectedAt = null
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        if (running) {
            runCatching { Clash.stopTun() }
            runCatching { Clash.reset() }
        }
        runCatching { Clash.clearOverride(Clash.OverrideSlot.Session) }
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        killJob = scope.launch {
            delay(300)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun report(state: String, message: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    /** Follow the physical network: correct transport for Android, and fresh connections after a switch. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var first = true
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                if (!first) runCatching { Clash.notifyNetworkChanged(true) }
                first = false
            }
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
        // Another VPN took over (also our own Xray service when the user switches cores).
        scope.launch { stop() }
    }

    override fun onDestroy() {
        if (running) {
            runCatching { Clash.stopTun() }
            report(STATE_IDLE)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(status: String) {
        VpnNotification.ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(status = status),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    /** Live speed for the notification, read from the core's own controller (the app reads it separately). */
    private fun startStats(port: Int, secret: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            val api = app.ghostly.core.mihomo.MihomoApi(port, secret)
            try {
                while (isActive && running) {
                    runCatching {
                        api.traffic().collect { (up, down) ->
                            upTotal += up
                            downTotal += down
                            if (running) updateNotification(VpnNotification.Stats(up, down, upTotal, downTotal))
                        }
                    }
                    delay(1000)
                }
            } finally {
                api.close()
            }
        }
    }

    private fun updateNotification(stats: VpnNotification.Stats? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(stats))
    }

    private fun notification(stats: VpnNotification.Stats? = null, status: String? = null): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MihomoVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return VpnNotification.build(
            this, VpnNotification.Core.MIHOMO, serverName, subscriptionName,
            connectedAt = if (status == null) connectedAt else null,
            stats = stats, open = openAppIntent(), stop = stop, status = status,
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** The pending "end this process" of the last stop; a new start cancels it. */
        @Volatile private var killJob: kotlinx.coroutines.Job? = null

        const val ACTION_START = "app.ghostly.vpn.MIHOMO_START"
        const val ACTION_STOP = "app.ghostly.vpn.MIHOMO_STOP"
        const val ACTION_STATE = "app.ghostly.vpn.MIHOMO_STATE"
        const val ACTION_PING = "app.ghostly.vpn.MIHOMO_PING"
        const val ACTION_PING_DONE = "app.ghostly.vpn.MIHOMO_PING_DONE"
        const val EXTRA_NAME = "name"
        const val EXTRA_SUBSCRIPTION = "subscription"
        const val EXTRA_CONTROLLER = "controller"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_IPV6 = "ipv6"
        const val EXTRA_SPLIT_MODE = "split_mode"
        const val EXTRA_SPLIT_APPS = "split_apps"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
        const val STATE_IDLE = "idle"
        private const val TAG = "GhostlyMihomo"
        private const val NOTIFICATION_ID = 8
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_DNS = "172.19.0.2"
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_DNS6 = "fdfe:dcba:9876::2"
    }
}
