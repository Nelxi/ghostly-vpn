package app.ghostly.core

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.ExtraProxy
import app.ghostly.core.model.SplitMode
import app.ghostly.core.vpn.tunnelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TunnelSettingsTest {
    @Test fun behaviorAndUpdatePreferencesNeverRebuildTheTunnel() {
        val s = AppSettings()
        assertEquals(s.tunnelSettings(), s.copy(autoCheckUpdates = false, autoInstallUpdates = false,
            autoUpdateWifiOnly = false, closeToTray = false, autoReconnect = false, autoFailover = false,
            smartGuard = false, saveWhitelist = false, qualityReports = false).tunnelSettings())
    }

    @Test fun inactiveEntriesAndUncheckedCustomPathsDoNotReconnect() {
        val s = AppSettings(splitMode = SplitMode.OFF)
        assertEquals(s.tunnelSettings(), s.copy(splitApps = setOf("anything"), customAppPaths = setOf("unused.exe"),
            extraProxies = listOf(ExtraProxy("off", port = 11080, enabled = false))).tunnelSettings())
    }

    @Test fun actualCoreOptionsAndActivePathClassificationStillReconnect() {
        val s = AppSettings()
        assertNotEquals(s.tunnelSettings(), s.copy(blockQuic = !s.blockQuic).tunnelSettings())
        assertNotEquals(s.tunnelSettings(), s.copy(dnsCache = !s.dnsCache).tunnelSettings())
        assertNotEquals(s.tunnelSettings(), s.copy(socksPort = 12000).tunnelSettings())
        val path = s.copy(splitMode = SplitMode.BYPASS_SELECTED, splitApps = setOf("C:/app.exe"))
        assertNotEquals(path.tunnelSettings(), path.copy(customAppPaths = setOf("C:/app.exe")).tunnelSettings())
    }
}
