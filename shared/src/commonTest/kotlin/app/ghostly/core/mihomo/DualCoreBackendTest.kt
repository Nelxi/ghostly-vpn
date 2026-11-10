package app.ghostly.core.mihomo

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DualCoreBackendTest {
    private open class FakeCore(initial: VpnState = VpnState.Idle) : VpnBackend {
        override val state = MutableStateFlow(initial)
        override val traffic = MutableStateFlow(Traffic())
        /** Set before [connect] runs, so a test can look at the state flow in between. */
        var onConnect: suspend () -> Unit = {}

        override suspend fun connect(server: Server, settings: AppSettings) {
            onConnect()
            state.value = VpnState.Connecting
        }

        override suspend fun disconnect() {
            state.value = VpnState.Idle
        }

        override suspend fun ping(server: Server, url: String): Long = -1
        override fun coreVersion(): String = "fake"
    }

    private class FakeMihomo(initial: VpnState) : FakeCore(initial), MihomoCore {
        override val api: MihomoApi? = null
        override suspend fun connect(server: Server, profile: Profile?, settings: AppSettings, stored: Map<String, String>) =
            connect(server, settings)
        override suspend fun connect(server: Server, settings: AppSettings) = super<FakeCore>.connect(server, settings)
    }

    private val link = Server(id = "s1", name = "🇳🇱 Vision", protocol = "vless", host = "nl.example", port = 443, link = "vless://x@nl.example:443")

    @Test
    fun anOldFailureOfTheOtherCoreIsNotShownAsNew() = runTest {
        val old = VpnState.Failed("Mihomo: старая ошибка")
        val xray = FakeCore()
        val mihomo = FakeMihomo(old)
        val dual = DualCoreBackend(xray, mihomo, backgroundScope)
        val seen = mutableListOf<VpnState>()
        backgroundScope.launch { dual.state.collect { seen += it } }
        runCurrent()
        // Between the switch and the new core's own connect, the collector gets a chance to run.
        mihomo.onConnect = { yield(); runCurrent() }

        dual.connect(link, AppSettings(core = CoreType.MIHOMO))
        runCurrent()

        assertTrue(seen.none { it === old }, "stale failure leaked: $seen")
        assertEquals(VpnState.Connecting, dual.state.value)
    }

    @Test
    fun aNewFailureAfterTheSwitchStillComesThrough() = runTest {
        val xray = FakeCore()
        val mihomo = FakeMihomo(VpnState.Failed("Mihomo: старая ошибка"))
        val dual = DualCoreBackend(xray, mihomo, backgroundScope)
        backgroundScope.launch { dual.state.collect {} }
        dual.connect(link, AppSettings(core = CoreType.MIHOMO))
        runCurrent()

        val fresh = VpnState.Failed("Mihomo: та же ошибка снова")
        mihomo.state.value = fresh
        runCurrent()
        assertTrue(dual.state.value === fresh)
    }
}
