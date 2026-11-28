package app.ghostly.core.vpn

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.PingMethod
import app.ghostly.core.model.SplitMode

/** Only configuration that changes a running core belongs in its restart key. */
fun AppSettings.tunnelSettings(): AppSettings = copy(
    accent = AppSettings().accent, haptics = true, reduceMotion = false, language = "",
    autoUpdateSubs = true, autoConnect = false, startOnBoot = false, pingUrl = "",
    hapticStrength = 0f, monet = false, blockCheck = false, sounds = false, soundVolume = 0f, stageMode = false,
    snow = true, tcpTipDismissed = false, wallpaperAccent = false, customAccent = 0,
    pingMethod = PingMethod.PROXY_GET, mihomoPingMethod = PingMethod.PROXY_GET,
    autoCheckUpdates = true, autoInstallUpdates = true, autoUpdateWifiOnly = true,
    autoReconnect = true, autoFailover = true, smartGuard = true, saveWhitelist = true, qualityReports = true,
    closeToTray = true, coreDefaultApplied = false, coreXrayRestored = false, ruAppsBypassApplied = false,
    customAppPaths = if (splitMode == SplitMode.OFF) emptySet() else customAppPaths.intersect(splitApps),
    splitApps = if (splitMode == SplitMode.OFF) emptySet() else splitApps,
    proxyUser = if (proxyAuth) proxyUser else "", proxyPass = if (proxyAuth) proxyPass else "",
    extraProxies = extraProxies.filter { it.ready }.map {
        it.copy(profileId = null, serverName = null, user = if (it.auth) it.user else "", pass = if (it.auth) it.pass else "")
    },
)
