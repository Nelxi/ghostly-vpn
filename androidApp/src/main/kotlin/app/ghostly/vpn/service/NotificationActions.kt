package app.ghostly.vpn.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ghostly.core.vpn.VpnState
import app.ghostly.vpn.GhostlyApplication
import app.ghostly.vpn.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Buttons of the VPN notifications. Runs in the main process (the controller lives there), so the
 * mihomo service's notification in `:mihomo` reaches it too. «Отключить» stays a direct stop of the
 * service. Also keeps the «VPN отключён» note in step with the state.
 */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = GhostlyApplication.instance.controller
        val done = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    RECONNECT -> controller.restart()
                    NEXT -> controller.nextFavorite()?.let { controller.select(it.id) }
                    CONNECT -> if (AndroidVpn.needsPermission() || controller.selectedServer() == null) {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        VpnNotification.clearFailure(context)
                        controller.connect()
                    }
                }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val RECONNECT = "app.ghostly.vpn.NOTIF_RECONNECT"
        const val NEXT = "app.ghostly.vpn.NOTIF_NEXT"
        const val CONNECT = "app.ghostly.vpn.NOTIF_CONNECT"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, NotificationActions::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * «VPN отключён» only for a tunnel that was up and then failed for good: the controller retries
         * a few times first (1–5 s apart, see ConnectRetry), so a failure must hold for a while.
         * A failed first connect is already on screen in the app, no note for it.
         */
        fun watchFailures(context: Context) {
            val app = context.applicationContext
            val controller = GhostlyApplication.instance.controller
            var wasUp = false
            scope.launch {
                controller.state.collectLatest { state ->
                    when (state) {
                        is VpnState.Connected -> { wasUp = true; VpnNotification.clearFailure(app) }
                        is VpnState.Failed -> if (wasUp) {
                            delay(FAILURE_HOLD_MS)
                            wasUp = false
                            VpnNotification.showFailure(app, controller.selectedServer()?.name, state.message)
                        }
                        VpnState.Idle -> { wasUp = false; VpnNotification.clearFailure(app) }
                        else -> Unit
                    }
                }
            }
        }

        private const val FAILURE_HOLD_MS = 8_000L
    }
}
