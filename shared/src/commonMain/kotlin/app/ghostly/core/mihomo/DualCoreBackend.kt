package app.ghostly.core.mihomo

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The platform's mihomo runner. Like [VpnBackend.connect], but it needs the whole profile (groups, other servers). */
interface MihomoCore : VpnBackend {
    /** REST API of the running core; null while down. */
    val api: MihomoApi?

    /** [stored] is the user's saved selector choices (group → member) for the profile. */
    suspend fun connect(server: Server, profile: Profile?, settings: AppSettings, stored: Map<String, String> = emptyMap())

    /** Real latency of Clash-profile proxies (they can't go through Xray); defaults to "unknown". */
    suspend fun pingProfile(servers: List<Server>, profile: Profile, url: String, onResult: (String, Long) -> Unit) {
        servers.forEach { onResult(it.id, -1) }
    }

    override suspend fun connect(server: Server, settings: AppSettings) = connect(server, null, settings)
}

/**
 * Two cores behind one [VpnBackend]: Xray-JSON servers run on Xray, Clash/mihomo profiles on mihomo,
 * plain links on whichever the user picked in settings. Only one core runs at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualCoreBackend(
    val xray: VpnBackend,
    val mihomo: MihomoCore,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VpnBackend {

    /** Set by the controller: the profile a server belongs to. */
    var profileOf: (serverId: String) -> Profile? = { null }

    /** Set by the controller: the user's saved selector choices of a profile (group → member). */
    var picksOf: (profileId: String) -> Map<String, String> = { emptyMap() }

    private val active = MutableStateFlow<VpnBackend>(xray)

    /** The core that runs (or last ran) the tunnel. */
    val current: VpnBackend get() = active.value

    val onMihomo: Boolean get() = active.value === mihomo

    override val state: StateFlow<VpnState> = active.flatMapLatest { it.state }.stateIn(scope, SharingStarted.Eagerly, VpnState.Idle)
    override val traffic: StateFlow<Traffic> = active.flatMapLatest { it.traffic }.stateIn(scope, SharingStarted.Eagerly, Traffic())

    override fun needsPermission(): Boolean = xray.needsPermission()

    override suspend fun requestPermission(): Boolean = xray.requestPermission()

    override suspend fun connect(server: Server, settings: AppSettings) {
        val profile = profileOf(server.id)
        val target = if (MihomoConfigBuilder.wants(server, profile, settings)) mihomo else xray
        val previous = active.value
        if (previous !== target) {
            if (previous.state.value !is VpnState.Idle && previous.state.value !is VpnState.Failed) previous.disconnect()
            active.value = target
        }
        if (target === mihomo) mihomo.connect(server, profile, settings, profile?.let { picksOf(it.id) } ?: emptyMap()) else xray.connect(server, settings)
    }

    override suspend fun disconnect() = active.value.disconnect()

    override suspend fun ping(server: Server, url: String): Long =
        if (server.mihomo != null) -1 else xray.ping(server, url)

    /** Link and Xray servers are measured by Xray (fast, batched); Clash-profile proxies by mihomo. */
    override suspend fun pingMany(servers: List<Server>, url: String, onResult: (serverId: String, ms: Long) -> Unit) {
        val (clash, rest) = servers.partition { it.mihomo != null }
        kotlinx.coroutines.coroutineScope {
            if (rest.isNotEmpty()) launch { xray.pingMany(rest, url, onResult) }
            clash.groupBy { profileOf(it.id) }.forEach { (profile, list) ->
                launch {
                    if (profile == null) list.forEach { onResult(it.id, -1) }
                    else mihomo.pingProfile(list, profile, url, onResult)
                }
            }
        }
    }

    override suspend fun healthCheck(url: String): Long = active.value.healthCheck(url)

    override val directProbesBypassTunnel: Boolean get() = active.value.directProbesBypassTunnel

    override val appPort: Int? get() = active.value.appPort

    /** Log of the core the user picked (the one [CoreType] names), not only the one that ran last. */
    suspend fun coreLogs(core: app.ghostly.core.model.CoreType): String? =
        if (core == app.ghostly.core.model.CoreType.MIHOMO) mihomo.coreLogs() else xray.coreLogs()

    override suspend fun coreLogs(): String? = active.value.coreLogs()

    override fun coreVersion(): String = "${xray.coreVersion()} · ${mihomo.coreVersion()}"

    override suspend fun switchInPlace(server: Server): Boolean {
        if (!onMihomo || state.value !is VpnState.Connected) return false
        val running = (state.value as VpnState.Connected).serverId
        val profile = profileOf(server.id) ?: return false
        if (profileOf(running)?.id != profile.id) return false
        val picks = MihomoConfigBuilder.picksFor(server, profile) ?: return false
        return mihomo.api?.applyPicks(picks) == true
    }
}
