package app.ghostly.vpn.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.ghostly.core.vpn.VpnState
import app.ghostly.i18n.tr
import app.ghostly.vpn.GhostlyApplication
import app.ghostly.vpn.MainActivity
import app.ghostly.vpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home screen widget: the ghost, the state and the server; one tap anywhere connects or disconnects
 * (like the Quick Settings tile). Without VPN permission or a chosen server the tap opens the app.
 */
class GhostlyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val controller = GhostlyApplication.instance.controller
        when (controller.state.value) {
            is VpnState.Connected, VpnState.Connecting -> {
                val done = goAsync()
                scope.launch { try { controller.disconnect() } finally { done.finish() } }
            }
            else -> if (AndroidVpn.needsPermission() || controller.selectedServer() == null) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val done = goAsync()
                scope.launch { try { controller.connect() } finally { done.finish() } }
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "app.ghostly.vpn.WIDGET_TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** Keeps placed widgets in step with the VPN state while the app process lives. */
        fun watch(context: Context) {
            val app = context.applicationContext
            scope.launch {
                GhostlyApplication.instance.controller.state.collect { render(app) }
            }
        }

        fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GhostlyWidget::class.java))
            if (ids.isEmpty()) return
            val controller = GhostlyApplication.instance.controller
            val state = controller.state.value
            val on = state is VpnState.Connected
            val server = (state as? VpnState.Connected)?.let { controller.server(it.serverId) } ?: controller.selectedServer()
            val views = RemoteViews(context.packageName, R.layout.widget_ghostly).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
                setInt(R.id.widget_button, "setBackgroundResource", if (on) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
                setInt(R.id.widget_icon, "setColorFilter", if (on) 0xFF7B5CFF.toInt() else 0xFFFFFFFF.toInt())
                setTextViewText(
                    R.id.widget_title,
                    when (state) {
                        is VpnState.Connected -> tr("Защищено")
                        VpnState.Connecting -> tr("Подключаюсь…")
                        VpnState.Disconnecting -> tr("Отключаюсь…")
                        is VpnState.Failed -> tr("Не удалось подключиться")
                        else -> tr("Выключен")
                    },
                )
                setTextViewText(
                    R.id.widget_subtitle,
                    when {
                        on && server != null -> server.name
                        server != null -> tr("Нажми, чтобы подключить · {0}", server.name)
                        else -> tr("Выбери сервер в приложении")
                    },
                )
                val toggle = Intent(context, GhostlyWidget::class.java).setAction(ACTION_TOGGLE)
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getBroadcast(context, 0, toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
