package app.ghostly.core

import app.ghostly.core.model.AppSettings
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.core.vpn.VpnState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportReportTest {
    private val platform = object : PlatformInfo {
        override val os = "Android"
        override val osVersion = "16"
        override val appVersion = "0.3.62"
        override val deviceModel get() = error("Device identity must not be read")
        override val hwid get() = error("HWID must not be read")
        override val dataDir get() = error("Private path must not be read")
        override fun openUrl(url: String) {}
        override fun copyToClipboard(text: String) {}
        override fun readClipboard(): String? = null
    }

    @Test fun privateIdsAndRawErrorsNeverEnterTheSupportSnapshot() {
        for (state in listOf(VpnState.Connected(0, "private-subscription-id"), VpnState.Failed("https://example/sub/private-token"))) {
            val report = supportReport(platform, AppSettings(), state)
            assertTrue(report.contains("0.3.62"))
            assertFalse(report.contains("private"))
            assertFalse(report.contains("https://"))
        }
    }
}
