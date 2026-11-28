package app.ghostly.core

import app.ghostly.core.model.AppSettings
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.core.vpn.VpnState

/** An allowlisted support snapshot: never include profiles, URLs, device identity, configs or raw errors. */
fun supportReport(platform: PlatformInfo, settings: AppSettings, state: VpnState): String = buildString {
    appendLine("Ghostly support report")
    appendLine("App: ${platform.appVersion}")
    appendLine("OS: ${platform.os} ${platform.osVersion}")
    appendLine("Network: ${platform.networkType()}")
    appendLine("Core: ${settings.core}")
    if (platform.isDesktop) appendLine("Mode: ${settings.desktopMode}")
    appendLine("VPN: " + when (state) {
        VpnState.Idle -> "idle"
        VpnState.Connecting -> "connecting"
        is VpnState.Connected -> "connected"
        VpnState.Disconnecting -> "disconnecting"
        is VpnState.Failed -> "failed (retryable=${state.retryable})"
    })
    appendLine("Reconnect: ${settings.autoReconnect}")
    appendLine("Failover: ${settings.autoFailover}")
    appendLine("Guard: ${settings.smartGuard}")
    appendLine("Auto update: ${settings.autoInstallUpdates}")
}
