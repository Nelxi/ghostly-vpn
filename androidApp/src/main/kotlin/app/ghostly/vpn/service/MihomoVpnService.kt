package app.ghostly.vpn.service

import android.app.Notification
import android.app.NotificationChannel
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
import androidx.core.app.NotificationCompat
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Bumped by every start: a pending self-kill only happens if no new start came in meanwhile. */
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stop() }
            ACTION_START -> {
                generation++
                serverName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                goForeground(getString(R.string.notif_connecting))
                scope.launch { start(intent) }
            }
            // Restarted by the system without our request (START_STICKY after a kill): nothing to resume.
            else -> scope.launch { stop() }
        }
        return START_NOT_STICKY
    }

    private suspend fun start(intent: Intent) = lock.withLock {
        try {
            if (running) runCatching { Clash.stopTun() }
            val mtu = intent.getIntExtra(EXTRA_MTU, 1500)
            val ipv6 = intent.getBooleanExtra(EXTRA_IPV6, false)
            val split = runCatching { SplitMode.valueOf(intent.getStringExtra(EXTRA_SPLIT_MODE) ?: "") }.getOrDefault(SplitMode.OFF)
            val apps = intent.getStringArrayExtra(EXTRA_SPLIT_APPS).orEmpty()

            // Config first: a broken profile fails here, before the VPN takes over the network.
            Clash.load(AndroidMihomo.profileDir(this)).await()

            val builder = Builder()
                .setSession("Ghostly · $serverName")
                .setMtu(mtu)
                .addAddress(TUN_GATEWAY, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS)
                .setBlocking(false)
                .setConfigureIntent(openAppIntent())
            if (ipv6) {
                builder.addAddress(TUN_GATEWAY6, 126)
                builder.addRoute("::", 0)
                builder.addDnsServer(TUN_DNS6)
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            when (split) {
                SplitMode.ONLY_SELECTED -> apps.forEach { runCatching { builder.addAllowedApplication(it) } }
                SplitMode.BYPASS_SELECTED -> {
                    builder.addDisallowedApplication(packageName)
                    apps.forEach { runCatching { builder.addDisallowedApplication(it) } }
                }
                // Our own sockets (the core's uplinks, the app's controller calls) stay outside the tunnel.
                SplitMode.OFF -> builder.addDisallowedApplication(packageName)
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
            report(STATE_CONNECTED)
            updateNotification("mihomo")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            report(STATE_FAILED, e.message?.takeIf { it.isNotBlank() }?.let { "mihomo: $it" } ?: "mihomo не запустился")
            shutdown()
        }
    }

    private suspend fun stop() = lock.withLock {
        report(STATE_IDLE)
        shutdown()
    }

    /** Stop the core and end the process: the next start loads a fresh one. */
    private fun shutdown() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        if (running) {
            runCatching { Clash.stopTun() }
            runCatching { Clash.reset() }
        }
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        val gen = generation
        scope.launch {
            delay(300)
            if (gen == generation && !running) android.os.Process.killProcess(android.os.Process.myPid())
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

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MihomoVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ghost)
            .setColor(0xFFA88DFF.toInt())
            .setContentTitle(serverName.ifEmpty { getString(R.string.app_name) })
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "app.ghostly.vpn.MIHOMO_START"
        const val ACTION_STOP = "app.ghostly.vpn.MIHOMO_STOP"
        const val ACTION_STATE = "app.ghostly.vpn.MIHOMO_STATE"
        const val EXTRA_NAME = "name"
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
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 8
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_DNS = "172.19.0.2"
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_DNS6 = "fdfe:dcba:9876::2"
    }
}
