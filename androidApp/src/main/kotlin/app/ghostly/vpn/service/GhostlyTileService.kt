package app.ghostly.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.ghostly.core.vpn.VpnState
import app.ghostly.vpn.GhostlyApplication
import app.ghostly.vpn.MainActivity
import app.ghostly.vpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings toggle. */
class GhostlyTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var watch: Job? = null

    override fun onStartListening() {
        watch = scope.launch { GhostlyApplication.instance.controller.state.collect { render(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
    }

    override fun onClick() {
        val controller = GhostlyApplication.instance.controller
        when (controller.state.value) {
            is VpnState.Connected, VpnState.Connecting -> scope.launch { controller.disconnect() }
            else -> if (AndroidVpn.needsPermission() || controller.selectedServer() == null) openApp()
            else scope.launch { controller.connect() }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    /**
     * Off: "Ghostly". On: the subscription in use as the label, like Prizrak-Box, with the server
     * (Xray) or the core (Mihomo, where selectors decide the route) underneath.
     */
    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        val controller = GhostlyApplication.instance.controller
        tile.state = when (state) {
            is VpnState.Connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val server = (state as? VpnState.Connected)?.let { controller.server(it.serverId) }
        val profile = server?.let { s -> controller.profiles.value.firstOrNull { p -> p.servers.any { it.id == s.id } } }
        val mihomo = controller.settings.value.core == app.ghostly.core.model.CoreType.MIHOMO
        tile.label = when {
            state !is VpnState.Connected -> getString(R.string.app_name)
            profile?.url != null -> profile.name
            else -> server?.name ?: getString(R.string.app_name)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (state) {
                is VpnState.Connected -> when {
                    mihomo -> "Mihomo"
                    profile?.url != null && server != null -> server.name
                    else -> getString(R.string.tile_on)
                }
                VpnState.Connecting -> getString(R.string.notif_connecting)
                else -> getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }
}
