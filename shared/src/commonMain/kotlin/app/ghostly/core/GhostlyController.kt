package app.ghostly.core

import app.ghostly.core.link.LinkParser
import app.ghostly.core.link.decodeBase64Lenient
import app.ghostly.core.link.percentDecode
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.Ping
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.store.FileStore
import app.ghostly.core.sub.SubscriptionClient
import app.ghostly.core.sub.SubscriptionParser
import app.ghostly.core.vpn.NetType
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import app.ghostly.core.mihomo.visibleFor
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.time.Clock

@Serializable
private data class UiState(
    val selectedServerId: String? = null,
    val favorites: Set<String> = emptySet(),
    val pings: Map<String, Ping> = emptyMap(),
    val onboarded: Boolean = false,
    /** Update version the user closed with ✕ — not offered again until a newer one appears. */
    val dismissedUpdate: String? = null,
    /** mihomo selector choices per profile (profileId → group → member): survive reconnects and restarts. */
    val mihomoPicks: Map<String, Map<String, String>> = emptyMap(),
)

class GhostlyController(
    val platform: PlatformInfo,
    val backend: VpnBackend,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val store = FileStore(platform.dataDir)
    private val subs = SubscriptionClient(platform)

    private val _profiles = MutableStateFlow(store.load(PROFILES, ListSerializer(Profile.serializer())) ?: emptyList())
    /** Profiles for the chosen core only (see [app.ghostly.core.mihomo.visibleFor]). */
    val profiles: StateFlow<List<Profile>> by lazy {
        kotlinx.coroutines.flow.combine(_profiles, _settings) { list, s -> list.mapNotNull { it.visibleFor(s.core) } }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, visibleProfiles())
    }

    private fun visibleProfiles(): List<Profile> = _profiles.value.mapNotNull { it.visibleFor(_settings.value.core) }

    private val _settings = MutableStateFlow(store.load(SETTINGS, AppSettings.serializer()) ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(store.load(STATE, UiState.serializer()) ?: UiState())

    private val _selected = MutableStateFlow(_ui.value.selectedServerId)
    val selectedServerId: StateFlow<String?> = _selected.asStateFlow()

    private val _favorites = MutableStateFlow(_ui.value.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _pings = MutableStateFlow(_ui.value.pings)
    val pings: StateFlow<Map<String, Ping>> = _pings.asStateFlow()

    private val _mihomoPicks = MutableStateFlow(_ui.value.mihomoPicks)

    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging: StateFlow<Set<String>> = _pinging.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private var dismissedUpdate: String? = _ui.value.dismissedUpdate

    /** Server-tunable look (glow, parallax, seasonal accent, announcement) — changes without an app update. */
    val design = app.ghostly.core.design.RemoteDesign(store, "GhostlyVPN/${platform.appVersion} (${platform.os})")

    /** Messages from Ghostly (to everyone or to this person), shown in the app window. */
    val notices = app.ghostly.core.notice.Notices(store, platform, "GhostlyVPN/${platform.appVersion} (${platform.os})")

    fun loadNoticeHistory() {
        scope.launch(Dispatchers.IO) { runCatching { notices.loadHistory(_profiles.value) } }
    }

    fun dismissNotice(id: Long) {
        scope.launch(Dispatchers.IO) { runCatching { notices.dismiss(id) } }
    }

    /** Proxy groups (selectors) of the running mihomo core. */
    val mihomoGroups = app.ghostly.core.mihomo.MihomoGroups(backend as? app.ghostly.core.mihomo.DualCoreBackend, scope) { _settings.value.mihomoPingUrl }

    /** App self-update (our server first, GitHub mirror), verified by SHA-256. */
    val updater = app.ghostly.core.update.Updater(platform) { v -> v == dismissedUpdate }

    /** The log of the last run of [core] (Xray or mihomo), newest lines last; null when empty. */
    suspend fun coreLogs(core: app.ghostly.core.model.CoreType): String? =
        (backend as? app.ghostly.core.mihomo.DualCoreBackend)?.coreLogs(core) ?: backend.coreLogs()

    /** Copies (or shares) the core's log for a support message, headed with what support asks first. */
    fun exportLogs(core: app.ghostly.core.model.CoreType, share: Boolean) {
        scope.launch(Dispatchers.IO) {
            val name = if (core == app.ghostly.core.model.CoreType.MIHOMO) "Mihomo" else "Xray"
            val logs = coreLogs(core)
            if (logs == null) {
                _events.emit("Журнал $name пуст — сначала попробуй подключиться на этом ядре")
                return@launch
            }
            val head = "Ghostly ${platform.appVersion} · ${platform.os} ${platform.osVersion} · ${platform.deviceModel}\n" +
                "Ядро: $name · ${backend.coreVersion()}\n\n"
            if (share) platform.share(head + logs)
            else {
                platform.copyToClipboard(head + logs)
                _events.emit("Журнал $name скопирован — вставь его в чат поддержки")
            }
        }
    }

    fun dismissUpdate() {
        dismissedUpdate = updater.offer.value?.version
        updater.hide()
        saveUi()
    }

    fun installUpdate() {
        val offer = updater.offer.value ?: return
        scope.launch(Dispatchers.IO) { updater.install(offer) }
    }

    fun checkUpdates(manual: Boolean) {
        scope.launch(Dispatchers.IO) {
            val offer = runCatching { updater.check(force = manual) }.getOrNull()
            if (manual) _events.emit(offer?.let { "Вышла версия ${it.version} — можно обновиться" } ?: "У тебя последняя версия ♡")
        }
    }

    private val _onboarded = MutableStateFlow(_ui.value.onboarded || _profiles.value.isNotEmpty())
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot messages for toasts. */
    val events: SharedFlow<String> = _events

    val state: StateFlow<VpnState> get() = backend.state

    private var failoverAttempts = 0
    private var userWantsConnection = false

    init {
        // 0.2.1 switched everyone to mihomo; Xray is the default again — move back once, later choices stick.
        if (!_settings.value.coreXrayRestored) {
            updateSettings { it.copy(core = app.ghostly.core.model.CoreType.XRAY, coreXrayRestored = true) }
        }
        (backend as? app.ghostly.core.mihomo.DualCoreBackend)?.profileOf = ::profileOf
        (backend as? app.ghostly.core.mihomo.DualCoreBackend)?.picksOf = { id -> _mihomoPicks.value[id] ?: emptyMap() }
        app.ghostly.core.vpn.Probe.method = _settings.value.let { it.pingMethodOf(it.core) }
        // A kill switch left engaged by a crash must never keep the internet blocked.
        platform.killSwitch?.takeIf { it.engaged }?.let { ks -> scope.launch(Dispatchers.IO) { ks.release() } }
        // Local proxy credentials: ghostly_xxxxxx + a random password, generated once.
        if (_settings.value.proxyUser.isBlank() || _settings.value.proxyPass.isBlank()) {
            updateSettings { it.copy(proxyUser = it.proxyUser.ifBlank { randomUser() }, proxyPass = it.proxyPass.ifBlank { randomPassword() }) }
        }
        scope.launch {
            backend.state.collect { s ->
                when (s) {
                    is VpnState.Connected -> {
                        if (userWantsConnection) haptic(app.ghostly.core.vpn.Haptic.SUCCESS)
                        failoverAttempts = 0
                        releaseKillSwitch()
                        startGuard()
                        // Fresh traffic numbers right after connecting (and through the tunnel if direct is blocked).
                        if (_settings.value.autoUpdateSubs) launch(Dispatchers.IO) { kotlinx.coroutines.delay(3_000); refreshAll() }
                    }
                    is VpnState.Failed -> {
                        if (userWantsConnection) haptic(app.ghostly.core.vpn.Haptic.ERROR)
                        stopGuard()
                        if (userWantsConnection) {
                            engageKillSwitch()
                            onConnectionFailed(s.message)
                        }
                    }
                    else -> stopGuard()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(4_000)
            while (true) {
                val s = _settings.value
                if (s.autoCheckUpdates) {
                    val offer = runCatching { updater.check() }.getOrNull()
                    // Hands-off updates: fetch, verify SHA-256, install (desktop relaunches and reconnects).
                    if (offer != null && s.autoInstallUpdates && platform.canAutoInstall() && !updater.gaveUp(offer.version)) {
                        _events.emit("Вышла Ghostly ${offer.version} — скачиваю и ставлю сама ♡")
                        runCatching { updater.install(offer) }
                    }
                }
                kotlinx.coroutines.delay(2 * 60_000L)
            }
        }
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2_500)
            while (true) {
                runCatching { notices.refresh(_profiles.value) }
                kotlinx.coroutines.delay(45_000L)
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { design.refresh() }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
        // Subscriptions refresh themselves: at start, then every 15 min check whether the provider's
        // interval (profile-update-interval) has passed.
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (_settings.value.autoUpdateSubs) refreshStale()
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ lookups

    fun allServers(): List<Server> = visibleProfiles().flatMap { it.servers }

    fun server(id: String?): Server? = id?.let { sid -> allServers().firstOrNull { it.id == sid } }

    fun profileOf(serverId: String): Profile? = _profiles.value.firstOrNull { p -> p.servers.any { it.id == serverId } }

    fun selectedServer(): Server? = server(_selected.value) ?: allServers().firstOrNull()

    // ------------------------------------------------------------------ import

    /**
     * Accepts anything a user might paste or scan: a subscription URL, share links (one or many),
     * base64 blobs, a JSON config, or deep links (ghostly://, happ://add/, incy://, v2rayng://install-sub?url=).
     */
    fun import(raw: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { importInternal(raw.trim()) }
                .onFailure { _events.emit("Не получилось добавить: ${app.ghostly.core.sub.FetchErrors.describe(it)}") }
                .getOrDefault(false)
            onDone(ok)
        }
    }

    private suspend fun importInternal(text: String): Boolean {
        if (text.isEmpty()) {
            _events.emit("Буфер обмена пуст")
            return false
        }
        unwrapDeepLink(text)?.let { return importInternal(it) }

        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            val url = text.lineSequence().first().trim()
            _profiles.value.firstOrNull { it.url == url }?.let {
                refresh(it.id)
                return true
            }
            val id = newId()
            _refreshing.update { it + id }
            try {
                val parsed = subs.fetch(url, id, backend.appPort, mihomo = _settings.value.core == app.ghostly.core.model.CoreType.MIHOMO)
                val profile = Profile(
                    id = id,
                    name = parsed.title ?: hostOf(url),
                    url = url,
                    info = parsed.info,
                    supportUrl = parsed.supportUrl,
                    webPageUrl = parsed.webPageUrl,
                    announce = parsed.announce,
                    renewUrl = parsed.renewUrl,
                    notice = parsed.notice,
                    updateIntervalHours = parsed.updateIntervalHours ?: 12,
                    updatedAt = now(),
                    servers = parsed.servers,
                    mihomo = parsed.mihomo,
                )
                _profiles.update { it + profile }
                saveProfiles()
                if (server(_selected.value) == null) select(profile.servers.first().id)
                markOnboarded()
                _events.emit("Подписка «${profile.name}» добавлена · ${serverCount(profile.servers.size)}")
                pingAll(profile.id)
                return true
            } finally {
                _refreshing.update { it - id }
            }
        }

        if (app.ghostly.core.mihomo.MihomoYaml.looksLikeClash(text)) {
            val id = newId()
            val parsed = SubscriptionParser.parse(text, emptyMap(), id)
            if (parsed.servers.isEmpty()) {
                _events.emit("В профиле нет серверов")
                return false
            }
            _profiles.update { it + Profile(id = id, name = parsed.title ?: "Профиль Mihomo", servers = parsed.servers, mihomo = parsed.mihomo, updatedAt = now()) }
            saveProfiles()
            if (server(_selected.value) == null) select(parsed.servers.first().id)
            markOnboarded()
            _events.emit("Профиль Mihomo добавлен · ${serverCount(parsed.servers.size)}")
            return true
        }

        if (text.startsWith("{") || text.startsWith("[")) {
            val parsed = SubscriptionParser.parse(text, emptyMap(), newId())
            return addManual(parsed.servers)
        }

        val lines = text.lines().map { it.trim() }.filter { LinkParser.looksLikeLink(it) }
        if (lines.isNotEmpty()) {
            val manual = manualProfile()
            val base = manual?.servers?.size ?: 0
            val servers = lines.mapIndexedNotNull { i, l -> LinkParser.parse(l, "${manual?.id ?: MANUAL}:${base + i}:${Random.nextInt(1 shl 20)}") }
            if (servers.isEmpty()) {
                _events.emit("Ссылка не распознана")
                return false
            }
            return addManual(servers)
        }

        decodeBase64Lenient(text)?.takeIf { d -> d.lines().any { LinkParser.looksLikeLink(it) } }?.let { return importInternal(it) }

        _events.emit("Не похоже на ссылку или подписку")
        return false
    }

    private fun unwrapDeepLink(text: String): String? {
        val lower = text.lowercase()
        val prefixes = listOf("ghostly://import/", "ghostly://add/", "happ://add/", "incy://add/", "hiddify://import/", "v2raytun://import/")
        prefixes.firstOrNull { lower.startsWith(it) }?.let { return percentDecode(text.substring(it.length)) }
        if (lower.startsWith("ghostly://") || lower.startsWith("v2rayng://install-sub") || lower.startsWith("sing-box://import-remote-profile")) {
            val query = text.substringAfter('?', "")
            return query.split('&').firstOrNull { it.startsWith("url=") }?.let { percentDecode(it.removePrefix("url=")) }
        }
        return null
    }

    private suspend fun addManual(servers: List<Server>): Boolean {
        if (servers.isEmpty()) return false
        val existing = manualProfile()
        if (existing == null) {
            _profiles.update { it + Profile(id = MANUAL, name = "Мои серверы", servers = servers, updatedAt = now()) }
        } else {
            val known = existing.servers.mapNotNull { it.link }.toSet()
            val fresh = servers.filter { it.link == null || it.link !in known }
            _profiles.update { list -> list.map { if (it.id == MANUAL) it.copy(servers = it.servers + fresh) else it } }
        }
        saveProfiles()
        if (server(_selected.value) == null) select(servers.first().id)
        markOnboarded()
        _events.emit(if (servers.size == 1) "Сервер «${servers.first().name}» добавлен" else "Добавлено серверов: ${servers.size}")
        // One core at a time: say which core the new servers need when the chosen one can't run them.
        val core = _settings.value.core
        val hidden = servers.filter { s ->
            if (core == app.ghostly.core.model.CoreType.XRAY) s.outbound == null && s.config == null
            else s.link != null && s.protocol in app.ghostly.core.link.LinkParser.XRAY_ONLY
        }
        if (hidden.isNotEmpty()) {
            val names = hidden.map { it.protocol.uppercase() }.distinct().joinToString()
            _events.emit(if (core == app.ghostly.core.model.CoreType.XRAY) "$names работает на ядре Prizrak-Core — переключите ядро в настройках"
                         else "$names работает на ядре Xray — переключите ядро в настройках")
        }
        pingServers(servers)
        return true
    }

    private fun manualProfile() = _profiles.value.firstOrNull { it.id == MANUAL }

    // ------------------------------------------------------------------ profiles

    /** [manual]: the user pressed "Обновить" and waits for an answer, so success is reported too. */
    /** Since when a Ghostly subscription is answered only by a mirror, not by its own domain (per profile). */
    private val ownDomainDownSince = HashMap<String, Long>()

    /**
     * Seamless domain switch for Ghostly's own subscriptions: when the link's domain has been dead
     * for [app.ghostly.core.sub.GhostlyDomains.SWITCH_AFTER_MS] (blocked by RKN, DNS gone) while a
     * mirror answers, the subscription quietly moves to that mirror. Returns the new link, or null.
     */
    private fun domainFailover(profileId: String, url: String, fetchedFrom: String?): String? {
        val d = app.ghostly.core.sub.GhostlyDomains
        if (fetchedFrom == null || fetchedFrom == url || !d.isOurs(url)) {
            ownDomainDownSince.remove(profileId)
            return null
        }
        val first = profileId !in ownDomainDownSince
        val since = ownDomainDownSince.getOrPut(profileId) { now() }
        if (now() - since < d.SWITCH_AFTER_MS) {
            // Check again soon: a blocked domain shouldn't wait for the usual refresh interval.
            if (first) scope.launch { kotlinx.coroutines.delay(d.SWITCH_AFTER_MS + 5_000); refresh(profileId) }
            return null
        }
        ownDomainDownSince.remove(profileId)
        return fetchedFrom
    }

    fun refresh(profileId: String, manual: Boolean = false) {
        val profile = _profiles.value.firstOrNull { it.id == profileId } ?: return
        val url = profile.url ?: return
        if (profileId in _refreshing.value) return
        scope.launch(Dispatchers.IO) {
            _refreshing.update { it + profileId }
            try {
                val parsed = subs.fetch(url, profileId, backend.appPort, mihomo = _settings.value.core == app.ghostly.core.model.CoreType.MIHOMO)
                val movedTo = domainFailover(profileId, url, parsed.fetchedFrom)
                _profiles.update { list ->
                    list.map {
                        if (it.id != profileId) it else it.copy(
                            url = movedTo ?: it.url,
                            name = parsed.title ?: it.name,
                            info = parsed.info ?: it.info,
                            supportUrl = parsed.supportUrl ?: it.supportUrl,
                            webPageUrl = parsed.webPageUrl ?: it.webPageUrl,
                            // The note follows the provider: gone from the headers means gone here too.
                            announce = parsed.announce,
                            renewUrl = parsed.renewUrl,
                            notice = parsed.notice,
                            updateIntervalHours = parsed.updateIntervalHours ?: it.updateIntervalHours,
                            updatedAt = now(),
                            servers = parsed.servers,
                            mihomo = parsed.mihomo,
                        )
                    }
                }
                saveProfiles()
                if (_settings.value.core == app.ghostly.core.model.CoreType.MIHOMO && parsed.mihomo == null && parsed.servers.all { it.config != null } && warnedXrayOnly.add(profileId)) {
                    _events.emit("«${profile.name}»: провайдер отдаёт только формат Xray — на ядре Mihomo эта подписка не показывается")
                }
                // Selection is id-based (profile id + index), so it survives; fall back if the server vanished.
                if (server(_selected.value) == null) parsed.servers.firstOrNull()?.let { select(it.id) }
                if (manual) _events.emit("Подписка «${parsed.title ?: profile.name}» обновлена · ${serverCount(parsed.servers.size)}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _events.emit("Не удалось обновить «${profile.name}»: ${app.ghostly.core.sub.FetchErrors.describe(e)}")
            } finally {
                _refreshing.update { it - profileId }
            }
        }
    }

    private fun serverCount(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> "сервер"
            m10 in 2..4 && m100 !in 12..14 -> "сервера"
            else -> "серверов"
        }
        return "$n $word"
    }

    /** Profiles already reported as "Xray format only" on mihomo (once per run, not on every auto-refresh). */
    private val warnedXrayOnly = mutableSetOf<String>()

    fun refreshAll() = _profiles.value.filter { it.url != null }.forEach { refresh(it.id) }

    private fun refreshStale() {
        val t = now()
        _profiles.value.filter { it.url != null && t - it.updatedAt > it.updateIntervalHours.coerceAtLeast(1) * 3_600_000L }
            .forEach { refresh(it.id) }
    }

    fun renameProfile(profileId: String, name: String) {
        _profiles.update { list -> list.map { if (it.id == profileId) it.copy(name = name.trim().ifEmpty { it.name }) else it } }
        saveProfiles()
    }

    fun deleteProfile(profileId: String) {
        _profiles.update { list -> list.filterNot { it.id == profileId } }
        saveProfiles()
        if (server(_selected.value) == null) _selected.value = allServers().firstOrNull()?.id
        saveUi()
    }

    fun deleteServer(serverId: String) {
        _profiles.update { list -> list.map { p -> p.copy(servers = p.servers.filterNot { it.id == serverId }) }.filter { it.servers.isNotEmpty() || it.url != null } }
        saveProfiles()
        if (_selected.value == serverId) select(allServers().firstOrNull()?.id)
    }

    // ------------------------------------------------------------------ selection

    /** Last server picked in each subscription, so switching back to it lands where the user left. */
    private val lastInProfile = mutableMapOf<String, String>()

    /**
     * Make [profileId] the active subscription: its last picked server, else its «Авто», else its
     * first server. Used by the subscription switcher on Home.
     */
    fun switchProfile(profileId: String) {
        val profile = visibleProfiles().firstOrNull { it.id == profileId } ?: return
        if (profile.servers.any { it.id == _selected.value }) return
        val target = lastInProfile[profileId]?.takeIf { id -> profile.servers.any { it.id == id } }
            ?: profile.servers.firstOrNull { it.isAuto }?.id
            ?: profile.servers.firstOrNull()?.id
            ?: return
        select(target)
    }

    fun select(serverId: String?) {
        val previous = _selected.value
        previous?.let { id -> profileOf(id)?.let { lastInProfile[it.id] = id } }
        _selected.value = serverId
        if (serverId != null && _settings.value.core == app.ghostly.core.model.CoreType.MIHOMO) {
            // Picking a server is also a selector choice — remember it, so reconnects keep it.
            val target = server(serverId)
            val profile = target?.let { profileOf(it.id) }
            if (target != null && profile != null) rememberPicks(profile, picksAsChoices(target, profile))
        }
        saveUi()
        if (serverId != null && serverId != previous && backend.state.value is VpnState.Connected) {
            scope.launch {
                // mihomo: same profile → just move the selector, no reconnect.
                val target = server(serverId)
                if (target == null || !backend.switchInPlace(target)) reconnect()
            }
        }
    }

    // ------------------------------------------------------------------ mihomo selectors

    /** Saved selector choices of a profile (group → member). */
    fun mihomoPicksOf(profileId: String): Map<String, String> = _mihomoPicks.value[profileId] ?: emptyMap()

    /** What connecting to [server] would set the selectors to, as group → member names. */
    private fun picksAsChoices(server: Server, profile: Profile): Map<String, String> {
        val provided = profile.mihomo
        return if (provided != null) {
            if (server.mihomo != null) app.ghostly.core.mihomo.MihomoProfiles.selectPath(provided, server.name).toMap() else emptyMap()
        } else mapOf(app.ghostly.core.mihomo.MihomoConfigBuilder.MAIN_GROUP to server.name)
    }

    private fun rememberPicks(profile: Profile, choices: Map<String, String>) {
        if (choices.isEmpty()) return
        _mihomoPicks.update { all -> all + (profile.id to ((all[profile.id] ?: emptyMap()) + choices)) }
        saveUi()
    }

    /**
     * The user picked [member] in selector [group] (from the list, running core or not). The choice is
     * saved, applied to the running core when there is one, and the selected server follows it.
     */
    fun pickGroup(group: String, member: String, profileId: String? = null) {
        val profile = profileId?.let { id -> visibleProfiles().firstOrNull { it.id == id } } ?: groupOwner(group) ?: return
        if (activeProfile()?.id != profile.id) {
            // A selector of another subscription: that subscription becomes the active one
            // (reconnecting onto it when connected), with this choice kept.
            val s = profile.servers.firstOrNull { it.name == member }
            if (s != null) select(s.id) else switchProfile(profile.id)
            rememberPicks(profile, mapOf(group to member))
            return
        }
        rememberPicks(profile, mapOf(group to member))
        // The row on the Home screen follows the pick when it names a server of the profile.
        profile.servers.firstOrNull { it.name == member }?.let { s ->
            _selected.value = s.id
            saveUi()
        }
        mihomoGroups.select(group, member)
    }

    /** The subscription the core runs: the one holding the selected server, else the first. */
    private fun activeOf(list: List<Profile>, selected: String?): Profile? =
        list.firstOrNull { p -> p.servers.any { it.id == selected } } ?: list.firstOrNull()

    /** The active subscription (see [activeOf]); its selectors are the ones drawn and applied. */
    fun activeProfile(): Profile? = activeOf(visibleProfiles(), _selected.value)

    /** The visible profile a selector belongs to: by its groups for Clash profiles, the link profile otherwise. */
    private fun groupOwner(group: String): Profile? {
        // Same group names ("Авто", "Выбор") can exist in several subscriptions: the active one wins.
        val visible = visibleProfiles().let { all -> listOfNotNull(activeProfile()) + all.filter { it.id != activeProfile()?.id } }
        visible.firstOrNull { p -> p.mihomo?.let { group in app.ghostly.core.mihomo.MihomoProfiles.groups(it).keys } == true }?.let { return it }
        val links = visible.filter { it.mihomo == null && it.servers.any { s -> s.link != null && s.config == null } }
        return links.firstOrNull { p -> p.servers.any { it.id == _selected.value } } ?: links.firstOrNull()
    }

    /**
     * Selector groups drawn before the core runs (and while it starts): the same groups the running
     * core would report, built from the profiles, with saved choices as `now` and TCP pings as delays.
     */
    /** Selectors of every subscription (profile id → groups), so each one shows its own before it is active. */
    val staticMihomoGroupsByProfile: StateFlow<Map<String, List<app.ghostly.core.mihomo.ProxyGroupInfo>>> by lazy {
        kotlinx.coroutines.flow.combine(profiles, _mihomoPicks, _selected, _pings, _settings) { list, picks, selected, pings, s ->
            if (s.core != app.ghostly.core.model.CoreType.MIHOMO) emptyMap()
            else list.associate { p -> p.id to staticGroups(p, picks[p.id] ?: emptyMap(), selected, pings) }.filterValues { it.isNotEmpty() }
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())
    }

    val staticMihomoGroups: StateFlow<List<app.ghostly.core.mihomo.ProxyGroupInfo>> by lazy {
        kotlinx.coroutines.flow.combine(profiles, _mihomoPicks, _selected, _pings, _settings) { list, picks, selected, pings, s ->
            // Only the active subscription runs on the core, so only its selectors are shown:
            // two Clash subscriptions must not glue their groups into one list.
            if (s.core != app.ghostly.core.model.CoreType.MIHOMO) emptyList()
            else activeOf(list, selected)?.let { p -> staticGroups(p, picks[p.id] ?: emptyMap(), selected, pings) }.orEmpty()
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }

    private fun staticGroups(profile: Profile, picks: Map<String, String>, selected: String?, pings: Map<String, Ping>): List<app.ghostly.core.mihomo.ProxyGroupInfo> {
        val delays = profile.servers.mapNotNull { s -> pings[s.id]?.takeIf(Ping::ok)?.let { s.name to it.ms.toInt() } }.toMap()
        val provided = profile.mihomo
        if (provided != null) {
            val groups = app.ghostly.core.mihomo.MihomoProfiles.groups(provided)
            // Groups the config marks `hidden: true` work under the hood (through the visible groups'
            // selectors) but never show as rows, like mihomo's own clients draw it.
            return groups.filterValues { !it.hidden }.map { (name, g) ->
                val type = g.type
                val members = g.members
                app.ghostly.core.mihomo.ProxyGroupInfo(
                    name = name,
                    type = when (type) {
                        "select" -> "Selector"
                        "url-test" -> "URLTest"
                        "fallback" -> "Fallback"
                        "load-balance" -> "LoadBalance"
                        "relay" -> "Relay"
                        else -> type.replaceFirstChar { it.uppercase() }
                    },
                    now = picks[name] ?: members.firstOrNull().takeIf { type == "select" },
                    members = members,
                    delays = delays.filterKeys { it in members },
                    nestedGroups = members.filter { it in groups.keys }.toSet(),
                )
            }
        }
        // Link profiles: the template's groups. Shown only for the profile the selection is in (they all look alike).
        val links = profile.servers.filter { it.link != null && it.config == null }
        if (links.isEmpty()) return emptyList()
        val owner = groupOwner(app.ghostly.core.mihomo.MihomoConfigBuilder.MAIN_GROUP)
        if (owner?.id != profile.id) return emptyList()
        val names = links.map { it.name }
        val auto = app.ghostly.core.mihomo.MihomoConfigBuilder.AUTO_GROUP
        val fallback = app.ghostly.core.mihomo.MihomoConfigBuilder.FALLBACK_GROUP
        val now = picks[app.ghostly.core.mihomo.MihomoConfigBuilder.MAIN_GROUP]
            ?: links.firstOrNull { it.id == selected }?.name ?: names.firstOrNull()
        return listOf(
            app.ghostly.core.mihomo.ProxyGroupInfo(app.ghostly.core.mihomo.MihomoConfigBuilder.MAIN_GROUP, "Selector", now, listOf(auto, fallback) + names, delays, setOf(auto, fallback)),
            app.ghostly.core.mihomo.ProxyGroupInfo(auto, "URLTest", null, names, delays, emptySet()),
            app.ghostly.core.mihomo.ProxyGroupInfo(fallback, "Fallback", null, names, delays, emptySet()),
        )
    }

    fun toggleFavorite(serverId: String) {
        _favorites.update { if (serverId in it) it - serverId else it + serverId }
        saveUi()
    }

    /**
     * The server to recommend (the «Лучший сейчас» card, failover): among those that answer, regular
     * servers before white lists (the scarce pool), inside them Hysteria2 and the Finnish node first
     * ([app.ghostly.core.model.regularPreference]); the lowest ping only decides within that group.
     */
    fun bestServer(exclude: Set<String> = emptySet()): Server? {
        val p = _pings.value
        val alive = allServers().filter { !it.isAuto && it.id !in exclude && p[it.id]?.ok == true }
        val regular = alive.filter { !it.isWhitelist }
        val pool = regular.ifEmpty { alive }
        val score: (Server) -> Int = if (regular.isNotEmpty()) { s -> app.ghostly.core.model.regularPreference(s) }
        else { s -> app.ghostly.core.model.whitelistPreference(s) }
        val top = pool.maxOfOrNull(score) ?: return null
        return pool.filter { score(it) == top }.minByOrNull { p[it.id]!!.ms }
    }

    // ------------------------------------------------------------------ ping

    fun pingAll(profileId: String? = null) {
        val servers = if (profileId == null) allServers() else _profiles.value.firstOrNull { it.id == profileId }?.servers.orEmpty()
        pingServers(servers)
    }

    fun ping(serverId: String) {
        server(serverId)?.let { pingServers(listOf(it)) }
    }

    private val _groupsPinging = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Selector groups being tested before the core runs, as [groupTestKey]s (the live core reports its
     * own, see [MihomoGroups.testing]).
     */
    val groupsPinging: StateFlow<Set<String>> = _groupsPinging.asStateFlow()

    fun groupTestKey(profileId: String, group: String) = "$profileId\u0000$group"

    /**
     * The ping button of a selector group. With the core running it asks the core to test the group;
     * without it the group's servers (through nested groups too) are pinged like any other server —
     * on Android that briefly loads a ping-only core, so the delays are real ones, not handshakes.
     */
    fun testGroup(group: String, profileId: String? = null) {
        val profile = profileId?.let { id -> visibleProfiles().firstOrNull { it.id == id } } ?: groupOwner(group) ?: return
        // The running core only knows the active subscription's groups.
        if (mihomoGroups.groups.value.isNotEmpty() && activeProfile()?.id == profile.id) {
            mihomoGroups.test(group)
            return
        }
        val key = groupTestKey(profile.id, group)
        if (key in _groupsPinging.value) return
        val defs = profile.mihomo?.let { app.ghostly.core.mihomo.MihomoProfiles.groups(it) }.orEmpty()
        val listed = staticMihomoGroupsByProfile.value[profile.id].orEmpty().associate { it.name to it.members }
        val names = HashSet<String>()
        val seen = HashSet<String>()
        fun walk(g: String) {
            if (!seen.add(g)) return
            (defs[g]?.members ?: listed[g]).orEmpty().forEach { m -> if (m in defs || m in listed) walk(m) else names += m }
        }
        walk(group)
        val job = pingServers(profile.servers.filter { it.name in names }) ?: return
        _groupsPinging.update { it + key }
        job.invokeOnCompletion { _groupsPinging.update { it - key } }
    }

    /**
     * Two phases: an instant TCP handshake to every server (results in ~100 ms, marked quick),
     * then the real round-trip through the core, batched by the backend.
     */
    private fun pingServers(servers: List<Server>): kotlinx.coroutines.Job? {
        val targets = servers.filter { it.canPing && it.id !in _pinging.value }
        if (targets.isEmpty()) return null
        _pinging.update { it + targets.map { s -> s.id } }
        val method = _settings.value.let { it.pingMethodOf(it.core) }
        if (method == app.ghostly.core.model.PingMethod.TCP || method == app.ghostly.core.model.PingMethod.ICMP) {
            return scope.launch(Dispatchers.IO) { pingDirect(targets, method) }
        }
        return scope.launch(Dispatchers.IO) {
            val gate = Semaphore(24)
            targets.filter { it.protocol != "hysteria" && it.host != null && it.port > 0 }.map { s ->
                async {
                    gate.withPermit {
                        val ms = platform.tcpPing(s.host!!, s.port, 2500)
                        if (ms > 0 && _pings.value[s.id]?.let { !it.quick && now() - it.at < 60_000 } != true) {
                            _pings.update { it + (s.id to Ping(ms, now(), quick = true)) }
                        }
                    }
                }
            }.awaitAll()
            runCatching {
                backend.pingMany(targets, _settings.value.let { it.pingUrlOf(it.core) }) { id, ms -> pinged(id, ms) }
            }
            _pinging.update { it - targets.map { s -> s.id }.toSet() }
            saveUi()
        }
    }

    private val blockChecking = MutableStateFlow<Set<String>>(emptySet())

    /**
     * A final ping result. A server that doesn't answer gets a direct check (outside the tunnel): is
     * its IP blocked, is TLS cut by SNI, or does the TSPU freeze it after ~16 KB — shown instead of «нет».
     * Skipped while connected through the tunnel on platforms where the app's sockets would go into it.
     */
    private fun pinged(id: String, ms: Long) {
        _pings.update { it + (id to Ping(ms, now())) }
        _pinging.update { it - id }
        if (ms > 0 || !_settings.value.blockCheck) return
        val target = server(id)?.let { app.ghostly.core.vpn.BlockCheck.target(it) } ?: return
        if (backend.state.value is VpnState.Connected && !backend.directProbesBypassTunnel) return
        var fresh = false
        blockChecking.update { fresh = id !in it; it + id }
        if (!fresh) return
        scope.launch(Dispatchers.IO) {
            try {
                val verdict = platform.blockCheck(target) ?: return@launch
                _pings.update { m -> m[id]?.takeIf { !it.ok }?.let { m + (id to it.copy(block = verdict)) } ?: m }
            } finally {
                blockChecking.update { it - id }
            }
        }
    }

    /**
     * TCP / ICMP methods: the result of the direct probe is final. Servers that can't be probed that
     * way (UDP-only protocols for TCP, no host) fall back to the real ping through the core.
     */
    private suspend fun pingDirect(targets: List<Server>, method: app.ghostly.core.model.PingMethod) {
        val udp = setOf("hysteria", "tuic", "wireguard", "hysteria2")
        val (direct, viaCore) = targets.partition { s ->
            s.host != null && (method == app.ghostly.core.model.PingMethod.ICMP || (s.port > 0 && s.protocol !in udp))
        }
        val gate = Semaphore(24)
        kotlinx.coroutines.coroutineScope {
            direct.forEach { s ->
                launch {
                    val ms = gate.withPermit {
                        if (method == app.ghostly.core.model.PingMethod.ICMP) platform.icmpPing(s.host!!, 2500)
                        else platform.tcpPing(s.host!!, s.port, 2500)
                    }
                    pinged(s.id, ms)
                }
            }
            if (viaCore.isNotEmpty()) launch {
                runCatching {
                    backend.pingMany(viaCore, _settings.value.let { it.pingUrlOf(it.core) }) { id, ms -> pinged(id, ms) }
                }
            }
        }
        _pinging.update { it - targets.map { s -> s.id }.toSet() }
        saveUi()
    }

    // ------------------------------------------------------------------ connection

    fun toggle() {
        scope.launch {
            when (backend.state.value) {
                is VpnState.Connected, VpnState.Connecting -> disconnect()
                else -> connect()
            }
        }
    }

    suspend fun connect() {
        val server = selectedServer()
        if (server == null) {
            _events.emit("Сначала добавь подписку ♡")
            return
        }
        if (backend.needsPermission() && !backend.requestPermission()) {
            _events.emit("Без разрешения на VPN подключиться нельзя")
            return
        }
        userWantsConnection = true
        resolvePortConflicts()
        // «Авто» from the subscription is an Xray balancer that already includes white-list servers:
        // switching away from it would just fight the balancer.
        val target = if (_settings.value.saveWhitelist && !server.isAuto) chooseForNetwork(server) else server
        if (_selected.value != target.id) {
            _selected.value = target.id
            saveUi()
        }
        runCatching { backend.connect(target, _settings.value) }
            .onFailure { _events.emit(it.message ?: "Не удалось подключиться") }
    }

    fun toast(message: String) {
        scope.launch { _events.emit(message) }
    }

    private fun engageKillSwitch() {
        val ks = platform.killSwitch ?: return
        if (!_settings.value.killSwitch || ks.engaged) return
        scope.launch(Dispatchers.IO) {
            if (ks.engage()) _events.emit("Kill switch: VPN упал — интернет заблокирован, пока туннель не вернётся. Отключи VPN, чтобы снять блок")
        }
    }

    private fun releaseKillSwitch() {
        val ks = platform.killSwitch ?: return
        if (ks.engaged) scope.launch(Dispatchers.IO) { ks.release() }
    }

    suspend fun disconnect() {
        userWantsConnection = false
        platform.killSwitch?.takeIf { it.engaged }?.release()
        backend.disconnect()
    }

    /** If another app holds our local proxy ports, move ours to free ones instead of failing. */
    private suspend fun resolvePortConflicts() {
        val s = _settings.value
        if (!platform.isDesktop && !s.localProxy) return
        val listen = if (s.allowLan) "0.0.0.0" else "127.0.0.1"
        fun pick(port: Int, avoid: Int): Int {
            if (platform.isPortFree(port, listen)) return port
            var p = port + 10
            while (p < 65000 && (p == avoid || !platform.isPortFree(p, listen))) p += 10
            return p
        }
        val socks = pick(s.socksPort, -1)
        val http = pick(s.httpPort, socks)
        if (socks != s.socksPort || http != s.httpPort) {
            updateSettings { it.copy(socksPort = socks, httpPort = http) }
            _events.emit("Порты ${s.socksPort}/${s.httpPort} заняты другой программой — Ghostly перешёл на $socks/$http")
        }
    }

    private suspend fun reconnect() {
        val server = selectedServer() ?: return
        runCatching { backend.connect(server, _settings.value) }
            .onFailure { _events.emit(it.message ?: "Не удалось переподключиться") }
    }

    private suspend fun onConnectionFailed(message: String) {
        val current = _selected.value
        if (!_settings.value.autoFailover || failoverAttempts >= 3) {
            _events.emit(message)
            return
        }
        failoverAttempts++
        val next = bestServer(exclude = setOfNotNull(current)) ?: allServers().firstOrNull { it.id != current && !it.isAuto }
        if (next == null) {
            _events.emit(message)
            return
        }
        _events.emit("«${server(current)?.name}» не отвечает — пробую «${next.name}»")
        _selected.value = next.id
        saveUi()
        runCatching { backend.connect(next, _settings.value) }
    }

    // ------------------------------------------------------------------ connection guard

    private var guardJob: kotlinx.coroutines.Job? = null

    private fun stopGuard() {
        guardJob?.cancel()
        guardJob = null
    }

    /**
     * While connected: check that traffic really passes through the tunnel (not just "connected").
     * Two failures in a row → switch to the best working server, picking white-list servers when the
     * mobile network is in white-list mode. On a white-list server, go back to a regular one as soon
     * as it's reachable again — white-list traffic is the scarce pool.
     *
     * Mobile data takes a shorter path: when the operator turns the white lists on, a regular server
     * stops passing traffic from one check to the next, so the *first* failed check is already an
     * unambiguous signal. Waiting for the second one, and then a full poll interval, cost about a
     * minute before the app switched — that delay is what users notice. Wi-Fi keeps the two-failure
     * rule, where a single hiccup is noise.
     */
    private fun startGuard() {
        if (guardJob?.isActive == true) return
        guardJob = scope.launch(Dispatchers.IO) {
            var fails = 0
            var regularBack = 0
            // First check almost immediately: the tunnel needs a moment, but 12 s of silence made the
            // guard notice a blocked network much later than it had to.
            kotlinx.coroutines.delay(FIRST_GUARD_DELAY_MS)
            while (true) {
                var delayMs = GUARD_DELAY_MS
                val s = _settings.value
                val cur = selectedServer()
                if (s.smartGuard && cur != null) {
                    val net = platform.networkType()
                    val netChanged = lastNet != null && net != lastNet
                    lastNet = net
                    val ms = runCatching { backend.healthCheck(s.pingUrlOf(s.core)) }.getOrDefault(-1L)
                    if (ms > 0) {
                        fails = 0
                        _pings.update { it + (cur.id to Ping(ms, now())) }
                    } else if (!cur.isAuto) {
                        fails++
                    }
                    // Mobile + a regular server: a dead tunnel means the white lists came on, most
                    // likely. Switched on the first failure instead of the second.
                    val fastFail = net == NetType.CELLULAR && !cur.isWhitelist && !cur.isAuto
                    if (fails >= if (fastFail) 1 else 2) {
                        fails = 0
                        guardFailover(cur)
                        kotlinx.coroutines.delay(if (fastFail) FAST_GUARD_DELAY_MS else 15_000)
                        continue
                    }
                    if (!cur.isAuto && s.saveWhitelist && backend.directProbesBypassTunnel && hasWhitelistServers()) {
                        when {
                            // Switched Wi-Fi -> mobile while on a regular server: re-check right away.
                            netChanged && net == NetType.CELLULAR && !cur.isWhitelist && !regularStable() -> {
                                preferredWhitelist(whitelistServers())?.let { next ->
                                    _events.emit("Мобильный интернет — перешла на белые списки «${next.name}»")
                                    select(next.id)
                                }
                                regularBack = 0
                            }
                            cur.isWhitelist -> {
                                // Wi-Fi: back as soon as regular servers answer. Mobile: only when they are
                                // stable three checks in a row — a flaky regular route is worse.
                                val ok = if (net == NetType.CELLULAR) regularStable() else regularReachable()
                                regularBack = if (ok) regularBack + 1 else 0
                                val need = if (net == NetType.CELLULAR) 3 else 2
                                if (regularBack >= need) {
                                    regularBack = 0
                                    preferredRegular(regularServers())?.let { next ->
                                        _events.emit("Обычный интернет стабилен — перешла на «${next.name}», чтобы не тратить трафик белых списков")
                                        select(next.id)
                                    }
                                }
                            }
                            else -> regularBack = 0
                        }
                        // Mobile on a regular server (white lists may come on any moment) or halfway
                        // back to regular: look more often, a late switch is the whole complaint.
                        if ((net == NetType.CELLULAR && !cur.isWhitelist) || regularBack > 0) delayMs = FAST_GUARD_DELAY_MS
                    } else {
                        regularBack = 0
                    }
                }
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    private var lastNet: NetType? = null

    private fun whitelistServers() = allServers().filter { !it.isAuto && it.isWhitelist }
    private fun regularServers() = allServers().filter { !it.isAuto && !it.isWhitelist }
    private fun hasWhitelistServers() = whitelistServers().isNotEmpty()

    /**
     * The regular server to move to: the highest [app.ghostly.core.model.regularPreference] wins
     * (Hysteria2, and the Finnish node above all), the lowest ping decides inside that group. A
     * provider whose servers all score zero keeps the old plain-lowest-ping behaviour.
     */
    private fun preferredRegular(list: List<Server>): Server? {
        if (list.isEmpty()) return null
        val top = list.maxOf { app.ghostly.core.model.regularPreference(it) }
        if (top <= 0) return bestOf(list)
        return bestOf(list.filter { app.ghostly.core.model.regularPreference(it) == top })
    }

    /**
     * The white-list server to move to: «Белые списки 2» on Hysteria2, the Finnish node first
     * ([app.ghostly.core.model.whitelistPreference]); the lowest ping decides inside that group.
     */
    private fun preferredWhitelist(list: List<Server>): Server? {
        if (list.isEmpty()) return null
        val top = list.maxOf { app.ghostly.core.model.whitelistPreference(it) }
        if (top <= 0) return bestOf(list)
        return bestOf(list.filter { app.ghostly.core.model.whitelistPreference(it) == top })
    }

    /**
     * Pick the pool for the current network before connecting (rules from real-life testing):
     * Wi-Fi/cable -> regular servers, lowest ping. Mobile data -> white lists, unless regular servers
     * answer every probe with an even ping.
     */
    private suspend fun chooseForNetwork(server: Server): Server {
        if (!hasWhitelistServers() || !backend.directProbesBypassTunnel) return server
        return when (platform.networkType()) {
            NetType.CELLULAR -> when {
                server.isWhitelist -> server
                regularStable() -> server
                else -> preferredWhitelist(whitelistServers())?.also {
                    _events.emit("Мобильный интернет: белые списки сейчас надёжнее — подключаюсь через «${it.name}»")
                } ?: server
            }
            NetType.WIFI, NetType.ETHERNET -> when {
                !server.isWhitelist -> server
                regularReachable() -> preferredRegular(regularServers())?.also {
                    _events.emit("Wi-Fi: обычные серверы доступны — подключаюсь через «${it.name}»")
                } ?: server
                else -> server
            }
            NetType.UNKNOWN -> server
        }
    }

    /**
     * Regular route is usable, not just alive: 3 rounds x up to 3 servers, every probe must answer and
     * the slowest reply may not exceed 3x the fastest (throttled mobile networks show huge jitter).
     */
    private suspend fun regularStable(): Boolean {
        val regular = regularServers().filter { it.protocol != "hysteria" && it.host != null && it.port > 0 }
            .distinctBy { it.host to it.port }.take(3)
        if (regular.isEmpty()) return false
        val samples = mutableListOf<Long>()
        for (round in 0 until 3) {
            for (r in regular) {
                val ms = platform.tcpPing(r.host!!, r.port, 2000)
                if (ms <= 0) return false
                samples += ms
            }
            if (round < 2) kotlinx.coroutines.delay(700)
        }
        val min = samples.minOrNull() ?: return false
        val max = samples.maxOrNull() ?: return false
        return max <= maxOf(min * 3, min + 150)
    }

    /** Direct TCP to a few regular servers: does the network let normal VPN traffic through? */
    private suspend fun regularReachable(): Boolean {
        val regular = allServers().filter { !it.isAuto && !it.isWhitelist && it.protocol != "hysteria" && it.host != null && it.port > 0 }
            .distinctBy { it.host to it.port }.take(3)
        if (regular.isEmpty()) return false
        return regular.any { platform.tcpPing(it.host!!, it.port, 2500) > 0 }
    }

    private fun bestOf(list: List<Server>): Server? {
        val p = _pings.value
        return list.filter { p[it.id]?.ok == true }.minByOrNull { p[it.id]!!.ms } ?: list.firstOrNull()
    }

    private suspend fun guardFailover(cur: Server) {
        val others = allServers().filter { !it.isAuto && it.id != cur.id }
        if (others.isEmpty()) return
        // White-list mode: regular servers can't be reached directly, only white-listed routes work.
        val whitelistMode = others.any { it.isWhitelist } && backend.directProbesBypassTunnel &&
            (platform.networkType() == NetType.CELLULAR || !regularReachable())
        val pool = if (whitelistMode) others.filter { it.isWhitelist } else others.filter { !it.isWhitelist }.ifEmpty { others }
        val candidates = pool.filter { it.name != cur.name }
        // Both ways the Finnish Hysteria2 wins: «Белые списки 2» onto the white lists, plain
        // Hysteria2 coming off them (see [preferredWhitelist] / [preferredRegular]).
        val next = (if (whitelistMode) preferredWhitelist(candidates) else preferredRegular(candidates)) ?: return
        _events.emit(
            if (whitelistMode) "Похоже, включились белые списки — перешла на «${next.name}»"
            else "«${cur.name}» перестал пропускать трафик — перешла на «${next.name}»",
        )
        select(next.id)
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val after = transform(before)
        if (after == before) return
        _settings.value = after
        app.ghostly.core.vpn.Probe.method = after.pingMethodOf(after.core)
        store.save(SETTINGS, AppSettings.serializer(), after)
        if (before.startOnBoot != after.startOnBoot) runCatching { platform.setStartOnBoot(after.startOnBoot) }
        // Providers send another format to mihomo (Clash YAML with groups): fetch subscriptions again.
        if (before.core != after.core) {
            refreshAll()
            if (server(_selected.value) == null) {
                _selected.value = allServers().firstOrNull()?.id
                saveUi()
            }
        }
        if (backend.state.value is VpnState.Connected && tunnelAffecting(before) != tunnelAffecting(after)) {
            scope.launch { reconnect() }
        }
    }

    /** Settings that only change the look don't require a reconnect. */
    private fun tunnelAffecting(s: AppSettings) = s.copy(
        accent = AppSettings().accent, haptics = true, reduceMotion = false, language = "",
        autoUpdateSubs = true, autoConnect = false, startOnBoot = false, pingUrl = "",
        hapticStrength = 0f, monet = false, blockCheck = false, sounds = false, soundVolume = 0f, stageMode = false,
        pingMethod = app.ghostly.core.model.PingMethod.PROXY_GET, mihomoPingMethod = app.ghostly.core.model.PingMethod.PROXY_GET,
    )

    private var lastHover = 0L

    /** Hover note, at most one per 55 ms like on the site (sliding over a list stays a soft ripple). */
    fun hoverSound() {
        val s = _settings.value
        if (!s.sounds) return
        val t = now()
        if (t - lastHover < 55) return
        lastHover = t
        platform.playHover(s.soundVolume)
    }

    fun haptic(kind: app.ghostly.core.vpn.Haptic = app.ghostly.core.vpn.Haptic.CLICK) {
        val s = _settings.value
        if (s.haptics) platform.haptic(kind, s.hapticStrength)
        if (s.sounds) {
            if (kind == app.ghostly.core.vpn.Haptic.TICK) platform.playSound(app.ghostly.core.vpn.Haptic.CLICK, s.soundVolume * 0.6f)
            else platform.playSound(kind, s.soundVolume)
        }
    }

    fun markOnboarded() {
        if (_onboarded.value) return
        _onboarded.value = true
        saveUi()
    }

    // ------------------------------------------------------------------ export

    fun shareLink(serverId: String): String? = server(serverId)?.let { s -> s.link ?: s.config?.let { JsonPretty.encodeToString(JsonObject.serializer(), it) } }

    fun exportConfig(serverId: String): String? = server(serverId)?.let {
        JsonPretty.encodeToString(JsonObject.serializer(), app.ghostly.core.xray.XrayConfigBuilder.build(it, _settings.value, app.ghostly.core.xray.Ingress.Proxy(app.ghostly.core.xray.XrayConfigBuilder.localProxy(_settings.value), 0)))
    }

    // ------------------------------------------------------------------ persistence

    private fun saveProfiles() = store.save(PROFILES, ListSerializer(Profile.serializer()), _profiles.value)

    private fun saveUi() = store.save(
        STATE, UiState.serializer(),
        UiState(_selected.value, _favorites.value, _pings.value, _onboarded.value, dismissedUpdate, _mihomoPicks.value),
    )

    private fun randomUser(): String = "ghostly_" + secureToken("abcdefghijkmnpqrstuvwxyz23456789", 6)

    private fun randomPassword(): String = secureToken("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789", 20)

    /** Random string from a cryptographically secure source (Uuid.random uses the platform CSPRNG). */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun secureToken(alphabet: String, length: Int): String {
        val bytes = (0 until (length + 15) / 16 + 1).flatMap { kotlin.uuid.Uuid.random().toByteArray().asList() }
        return (0 until length).joinToString("") { alphabet[(bytes[it].toInt() and 0xFF) % alphabet.length].toString() }
    }

    fun regenerateProxyCredentials() = updateSettings { it.copy(proxyUser = randomUser(), proxyPass = randomPassword()) }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')

    private fun newId() = "p" + now().toString(36) + Random.nextInt(1 shl 16).toString(36)

    companion object {
        private const val PROFILES = "profiles.json"
        private const val SETTINGS = "settings.json"
        private const val STATE = "state.json"
        const val MANUAL = "manual"

        /** Normal poll of the connection guard. */
        private const val GUARD_DELAY_MS = 20_000L

        /** Poll while a switch is pending (mobile on a regular server, or heading back to regular). */
        private const val FAST_GUARD_DELAY_MS = 6_000L

        /** First look at the freshly raised tunnel — long enough to be a real check, short enough to matter. */
        private const val FIRST_GUARD_DELAY_MS = 1_500L

        fun now(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
