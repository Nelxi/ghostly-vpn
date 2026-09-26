package app.ghostly.vpn.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Measures Clash-profile proxies while no mihomo tunnel runs (or it runs another profile): the core
 * is loaded in its own short-lived `:mihomoping` process with just those proxies and a controller —
 * no VPN, no listeners — and [AndroidMihomo] asks the controller for each proxy's delay.
 * The process ends on [ACTION_STOP] or by itself after a minute.
 */
class MihomoPingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null
    /** Bumped by every start: a pending self-kill only happens if no new ping came in meanwhile. */
    @Volatile private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                generation++
                val dir = intent.getStringExtra(EXTRA_DIR)
                val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
                scope.launch { load(dir, session) }
                watchdog?.cancel()
                watchdog = scope.launch {
                    delay(60_000)
                    finish()
                }
            }
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private suspend fun load(dir: String?, session: String) {
        val ok = runCatching {
            Clash.load(File(requireNotNull(dir))).await()
        }.onFailure { Log.w(TAG, "ping core failed", it) }.isSuccess
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_SESSION, session)
                .putExtra(EXTRA_OK, ok),
        )
    }

    private fun finish() {
        val gen = generation
        scope.launch {
            delay(200)
            if (gen != generation) return@launch
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        const val ACTION_START = "app.ghostly.vpn.MIHOMO_PING_START"
        const val ACTION_STOP = "app.ghostly.vpn.MIHOMO_PING_STOP"
        const val ACTION_STATE = "app.ghostly.vpn.MIHOMO_PING_STATE"
        const val EXTRA_DIR = "dir"
        const val EXTRA_SESSION = "session"
        const val EXTRA_OK = "ok"
        private const val TAG = "GhostlyMihomoPing"
    }
}
