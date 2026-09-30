package app.ghostly.vpn

import app.ghostly.ui.components.AmbientMotion
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.ghostly.ui.GhostlyApp
import app.ghostly.vpn.service.AndroidVpn
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private val app get() = application as GhostlyApplication

    private var pendingConsent: CompletableDeferred<Boolean>? = null
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingConsent?.complete(result.resultCode == RESULT_OK)
        pendingConsent = null
    }

    private var pendingScan: ((String) -> Unit)? = null
    private val qrScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)?.let { pendingScan?.invoke(it) }
        pendingScan = null
    }

    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        AndroidVpn.permissionLauncher = { deferred ->
            val intent = VpnService.prepare(this)
            if (intent == null) deferred.complete(true)
            else {
                pendingConsent = deferred
                vpnConsent.launch(intent)
                askNotificationsOnce()
            }
        }
        app.platform.activityQrScanner = { onResult ->
            pendingScan = onResult
            qrScan.launch(Intent(this, QrScanActivity::class.java))
        }

        setContent { GhostlyApp(app.controller) }
        app.platform.hapticView = java.lang.ref.WeakReference(window.decorView)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** ghostly:// deep links and "Share → Ghostly". */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val payload = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!payload.isNullOrBlank()) {
            app.controller.import(payload)
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Looping decorations stop while the app is in the background (see Ambient.kt).
    override fun onStart() {
        super.onStart()
        AmbientMotion.visible.value = true
    }

    override fun onStop() {
        AmbientMotion.visible.value = false
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AndroidVpn.permissionLauncher = null
            app.platform.activityQrScanner = null
        }
        super.onDestroy()
    }
}
