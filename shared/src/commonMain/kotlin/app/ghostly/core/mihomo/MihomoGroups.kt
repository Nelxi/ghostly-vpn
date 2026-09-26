package app.ghostly.core.mihomo

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Ping
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.store.FileStore
import app.ghostly.core.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Proxy groups (selectors) for the UI.
 *
 * While mihomo runs they come from its controller. Before that — as soon as mihomo is the chosen
 * core — they are built from the profile itself, so selectors can be set without connecting; their
 * delays are the server pings. Choices are remembered per profile and applied when the core starts.
 */
class MihomoGroups(
    private val backend: DualCoreBackend?,
    private val scope: CoroutineScope,
    private val pingUrl: () -> String,
    private val store: FileStore,
    profiles: StateFlow<List<Profile>>,
    selectedId: StateFlow<String?>,
    pings: StateFlow<Map<String, Ping>>,
    pinging: StateFlow<Set<String>>,
    settings: StateFlow<AppSettings>,
    /** Picks a server in the list (selector member that is a server of the profile). */
    private val selectServer: (String) -> Unit,
    /** Pings servers the usual way (offline group test). */
    private val pingServers: (List<Server>) -> Unit,
) {
    /** profile id → (group → member). */
    private val choices = MutableStateFlow(
        store.load(CHOICES, MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))) ?: emptyMap(),
    )

    /** Groups of the running core; null while it doesn't run. */
    private val live = MutableStateFlow<List<ProxyGroupInfo>?>(null)
    private val liveTesting = MutableStateFlow<Set<String>>(emptySet())

    /** The profile whose selectors are shown before connecting. */
    private val offlineProfile: StateFlow<Profile?> = combine(profiles, selectedId, settings) { list, sel, s ->
        if (s.core != CoreType.MIHOMO) null
        else list.firstOrNull { p -> p.servers.any { it.id == sel } } ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val offline: StateFlow<List<ProxyGroupInfo>> = combine(offlineProfile, pings, choices) { profile, p, ch ->
        if (profile == null) emptyList() else fill(profile, MihomoProfiles.offlineGroups(profile), p, ch[profile.id].orEmpty())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<ProxyGroupInfo>> = combine(live, offline) { l, o -> l ?: o }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Groups being tested: live tests, or offline groups whose servers are being pinged. */
    val testing: StateFlow<Set<String>> = combine(live, liveTesting, offline, offlineProfile, pinging) { l, lt, o, profile, busy ->
        if (l != null) lt
        else {
            val ids = profile?.servers.orEmpty().associate { it.name to it.id }
            o.filter { g -> g.members.any { ids[it] in busy } }.map { it.name }.toSet()
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var poll: Job? = null

    init {
        if (backend != null) scope.launch {
            backend.state.collect { s ->
                if (s is VpnState.Connected && backend.onMihomo) start(s.serverId) else stop()
            }
        }
    }

    private fun api(): MihomoApi? = backend?.takeIf { it.onMihomo && it.state.value is VpnState.Connected }?.mihomo?.api

    private fun runningProfile(): Profile? {
        val b = backend ?: return null
        val s = b.state.value as? VpnState.Connected ?: return null
        return b.profileOf(s.serverId)
    }

    private fun start(serverId: String) {
        if (poll?.isActive == true) return
        poll = scope.launch(Dispatchers.Default) {
            // Selectors set before connecting (or last time) come back.
            val api = api()
            val profile = backend?.profileOf(serverId)
            if (api != null && profile != null) {
                val saved = choices.value[profile.id].orEmpty()
                var changed = false
                saved.forEach { (group, member) -> if (api.select(group, member)) changed = true }
                if (changed) api.closeConnections()
            }
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
    }

    private fun stop() {
        poll?.cancel()
        poll = null
        live.value = null
    }

    suspend fun refresh() {
        val api = api() ?: return
        live.value = runCatching { api.groups() }.getOrNull() ?: live.value
    }

    fun select(group: String, member: String) {
        val live = live.value
        val profile = if (live != null) runningProfile() else offlineProfile.value
        profile?.let { remember(it.id, mapOf(group to member)) }
        if (live == null) {
            // Not running: a member that is a server of the profile becomes the chosen server too.
            profile?.servers?.firstOrNull { it.name == member }?.let { selectServer(it.id) }
            return
        }
        scope.launch(Dispatchers.Default) {
            val api = api() ?: return@launch
            // Optimistic: the row moves at once, the next refresh confirms.
            this@MihomoGroups.live.update { list -> list?.map { if (it.name == group) it.copy(now = member) else it } }
            if (api.select(group, member)) api.closeConnections()
            refresh()
        }
    }

    /** The server list picked [server]: remember the selector path to it for its profile. */
    fun rememberServer(server: Server, profile: Profile?) {
        profile ?: return
        val picks = MihomoConfigBuilder.picksFor(server, profile) ?: return
        remember(profile.id, picks.associate { it.group to (it.choice ?: server.name) })
    }

    fun test(group: String) {
        if (live.value == null) {
            val profile = offlineProfile.value ?: return
            val g = offline.value.firstOrNull { it.name == group } ?: return
            val servers = profile.servers.filter { it.name in g.members }
            if (servers.isNotEmpty()) pingServers(servers)
            return
        }
        if (group in liveTesting.value) return
        scope.launch(Dispatchers.Default) {
            val api = api() ?: return@launch
            liveTesting.update { it + group }
            try {
                api.testGroup(group, pingUrl())
                refresh()
            } finally {
                liveTesting.update { it - group }
            }
        }
    }

    private fun remember(profileId: String, picks: Map<String, String>) {
        if (picks.isEmpty()) return
        choices.update { all -> all + (profileId to (all[profileId].orEmpty() + picks)) }
        runCatching {
            store.save(CHOICES, MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())), choices.value)
        }
    }

    /** What each offline group points at and its members' delays (from server pings). */
    private fun fill(profile: Profile, groups: List<ProxyGroupInfo>, pings: Map<String, Ping>, saved: Map<String, String>): List<ProxyGroupInfo> {
        val byName = profile.servers.associateBy { it.name }
        fun delay(member: String): Int? = byName[member]?.let { pings[it.id] }?.let { if (it.ok) it.ms.toInt() else 0 }
        return groups.map { g ->
            val delays = g.members.mapNotNull { m -> delay(m)?.let { m to it } }.toMap()
            val now = when {
                g.selectable -> saved[g.name]?.takeIf { it in g.members } ?: g.members.firstOrNull()
                g.type == "URLTest" -> delays.filterValues { it > 0 }.minByOrNull { it.value }?.key ?: g.members.firstOrNull()
                g.type == "Fallback" -> g.members.firstOrNull { (delays[it] ?: 1) > 0 } ?: g.members.firstOrNull()
                else -> g.members.firstOrNull()
            }
            g.copy(now = now, delays = delays)
        }
    }

    private companion object {
        const val CHOICES = "mihomo-selectors.json"
    }
}
