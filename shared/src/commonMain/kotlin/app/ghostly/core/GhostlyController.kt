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

    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging: StateFlow<Set<String>> = _pinging.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private var dismissedUpdate: String? = _ui.value.dismissedUpdate

    /** Server-tunable look (glow, parallax, seasonal accent, announcement) — changes without an app update. */
    val design = app.ghostly.core.design.RemoteDesign(store, "GhostlyVPN/${platform.appVersion} (${platform.os})")

    /** Proxy groups (selectors) of the running mihomo core. */
    val mihomoGroups = app.ghostly.core.mihomo.MihomoGroups(
        backend = backend as? app.ghostly.core.mihomo.DualCoreBackend,
        scope = scope,
        pingUrl = { _settings.value.pingUrl },
        store = store,
        profiles = profiles,
        selectedId = _selected,
        pings = _pings,
        pinging = _pinging,
        settings = _settings,
        selectServer = { id -> select(id) },
        pingServers = { list -> pingServers(list) },
    )

    /** App self-update (our server first, GitHub mirror), verified by SHA-256. */
    val updater = app.ghostly.core.update.Updater(platform) { v -> v == dismissedUpdate }

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
        // After construction: refresh() uses properties declared further down.
        scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(1_500); refreshWrongFormat() }
        app.ghostly.core.vpn.Probe.method = _settings.value.pingMethod
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
                        failoverAttempts = 0
                        releaseKillSwitch()
                        startGuard()
                        // Fresh traffic numbers right after connecting (and through the tunnel if direct is blocked).
                        if (_settings.value.autoUpdateSubs) launch(Dispatchers.IO) { kotlinx.coroutines.delay(3_000); refreshAll() }
                    }
                    is VpnState.Failed -> {
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
                    if (offer != null && s.autoInstallUpdates && platform.canAutoInstall()) {
                        _events.emit("Вышла Ghostly ${offer.version} — скачиваю и ставлю сама ♡")
                        runCatching { updater.install(offer) }
                    }
                }
                kotlinx.coroutines.delay(2 * 60_000L)
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
                .onFailure { _events.emit("Не получилось добавить: ${it.message ?: it::class.simpleName}") }
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
                    updateIntervalHours = parsed.updateIntervalHours ?: 12,
                    updatedAt = now(),
                    servers = parsed.servers,
                    mihomo = parsed.mihomo,
                )
                _profiles.update { it + profile }
                saveProfiles()
                if (server(_selected.value) == null) select(profile.servers.first().id)
                markOnboarded()
                _events.emit("Подписка «${profile.name}» добавлена · ${profile.servers.size} серверов")
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
            _profiles.update { it + Profile(id = id, name = parsed.title ?: "Профиль mihomo", servers = parsed.servers, mihomo = parsed.mihomo, updatedAt = now()) }
            saveProfiles()
            if (server(_selected.value) == null) select(parsed.servers.first().id)
            markOnboarded()
            _events.emit("Профиль mihomo добавлен · ${parsed.servers.size} серверов")
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
        pingServers(servers)
        return true
    }

    private fun manualProfile() = _profiles.value.firstOrNull { it.id == MANUAL }

    // ------------------------------------------------------------------ profiles

    fun refresh(profileId: String) {
        val profile = _profiles.value.firstOrNull { it.id == profileId } ?: return
        val url = profile.url ?: return
        if (profileId in _refreshing.value) return
        scope.launch(Dispatchers.IO) {
            _refreshing.update { it + profileId }
            try {
                val parsed = subs.fetch(url, profileId, backend.appPort, mihomo = _settings.value.core == app.ghostly.core.model.CoreType.MIHOMO)
                _profiles.update { list ->
                    list.map {
                        if (it.id != profileId) it else it.copy(
                            name = parsed.title ?: it.name,
                            info = parsed.info ?: it.info,
                            supportUrl = parsed.supportUrl ?: it.supportUrl,
                            webPageUrl = parsed.webPageUrl ?: it.webPageUrl,
                            updateIntervalHours = parsed.updateIntervalHours ?: it.updateIntervalHours,
                            updatedAt = now(),
                            servers = parsed.servers,
                            mihomo = parsed.mihomo,
                        )
                    }
                }
                saveProfiles()
                if (_settings.value.core == app.ghostly.core.model.CoreType.MIHOMO && parsed.mihomo == null && parsed.servers.all { it.config != null } && warnedXrayOnly.add(profileId)) {
                    _events.emit("«${profile.name}»: провайдер отдаёт только формат Xray — на ядре mihomo эта подписка не показывается")
                }
                // Selection is id-based (profile id + index), so it survives; fall back if the server vanished.
                if (server(_selected.value) == null) parsed.servers.firstOrNull()?.let { select(it.id) }
            } catch (e: Exception) {
                _events.emit("«${profile.name}»: ${e.message ?: "ошибка обновления"}")
            } finally {
                _refreshing.update { it - profileId }
            }
        }
    }

    /** Profiles already reported as "Xray format only" on mihomo (once per run, not on every auto-refresh). */
    private val warnedXrayOnly = mutableSetOf<String>()

    /**
     * Subscriptions saved in the other core's format (Xray JSON while mihomo is chosen, Clash YAML
     * while Xray is) are fetched again right away — not only after the first connect.
     */
    private fun refreshWrongFormat() {
        val core = _settings.value.core
        _profiles.value.filter { p ->
            p.url != null && when (core) {
                app.ghostly.core.model.CoreType.MIHOMO -> p.mihomo == null && p.servers.isNotEmpty() && p.servers.all { it.config != null }
                app.ghostly.core.model.CoreType.XRAY -> p.mihomo != null
            }
        }.forEach { refresh(it.id) }
    }

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

    fun select(serverId: String?) {
        val previous = _selected.value
        server(serverId)?.let { mihomoGroups.rememberServer(it, profileOf(it.id)) }
        _selected.value = serverId
        saveUi()
        if (serverId != null && serverId != previous && backend.state.value is VpnState.Connected) {
            scope.launch {
                // mihomo: same profile → just move the selector, no reconnect.
                val target = server(serverId)
                if (target == null || !backend.switchInPlace(target)) reconnect()
            }
        }
    }

    fun toggleFavorite(serverId: String) {
        _favorites.update { if (serverId in it) it - serverId else it + serverId }
        saveUi()
    }

    /** Best non-auto server by latency among those with a successful ping. */
    fun bestServer(exclude: Set<String> = emptySet()): Server? {
        val p = _pings.value
        return allServers().filter { !it.isAuto && it.id !in exclude && p[it.id]?.ok == true }
            .minByOrNull { p[it.id]!!.ms }
    }

    // ------------------------------------------------------------------ ping

    fun pingAll(profileId: String? = null) {
        val servers = if (profileId == null) allServers() else _profiles.value.firstOrNull { it.id == profileId }?.servers.orEmpty()
        pingServers(servers)
    }

    fun ping(serverId: String) = server(serverId)?.let { pingServers(listOf(it)) }

    /**
     * Two phases: an instant TCP handshake to every server (results in ~100 ms, marked quick),
     * then the real round-trip through the core, batched by the backend.
     */
    private fun pingServers(servers: List<Server>) {
        val targets = servers.filter { !it.isAuto && it.id !in _pinging.value }
        if (targets.isEmpty()) return
        _pinging.update { it + targets.map { s -> s.id } }
        val method = _settings.value.pingMethod
        if (method == app.ghostly.core.model.PingMethod.TCP || method == app.ghostly.core.model.PingMethod.ICMP) {
            scope.launch(Dispatchers.IO) { pingDirect(targets, method) }
            return
        }
        scope.launch(Dispatchers.IO) {
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
                backend.pingMany(targets, _settings.value.pingUrl) { id, ms ->
                    _pings.update { it + (id to Ping(ms, now())) }
                    _pinging.update { it - id }
                }
            }
            _pinging.update { it - targets.map { s -> s.id }.toSet() }
            saveUi()
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
                    val ms = gate.withPermit { probeDirect(s, method) }
                    _pings.update { it + (s.id to Ping(ms, now())) }
                    _pinging.update { it - s.id }
                }
            }
            if (viaCore.isNotEmpty()) launch {
                runCatching {
                    backend.pingMany(viaCore, _settings.value.pingUrl) { id, ms ->
                        _pings.update { it + (id to Ping(ms, now())) }
                        _pinging.update { it - id }
                    }
                }
            }
        }
        _pinging.update { it - targets.map { s -> s.id }.toSet() }
        saveUi()
    }

    /**
     * Up to three TCP/ICMP probes: a single lost packet shouldn't mark a working server dead.
     * Stops after two answers; the best time wins, "no answer" only when all three failed.
     */
    private suspend fun probeDirect(s: Server, method: app.ghostly.core.model.PingMethod): Long {
        var best = -1L
        var answers = 0
        for (attempt in 0 until 3) {
            if (answers >= 2) break
            val ms = if (method == app.ghostly.core.model.PingMethod.ICMP) platform.icmpPing(s.host!!, 2500)
            else platform.tcpPing(s.host!!, s.port, 2500)
            if (ms > 0) {
                answers++
                if (best < 0 || ms < best) best = ms
            }
        }
        return best
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
     * While connected: every ~20 s check that traffic really passes through the tunnel (not just
     * "connected"). Two failures in a row → switch to the best working server, picking white-list
     * servers when the mobile network is in white-list mode. On a white-list server, go back to a
     * regular one as soon as it's reachable again — white-list traffic is the scarce pool.
     */
    private fun startGuard() {
        if (guardJob?.isActive == true) return
        guardJob = scope.launch(Dispatchers.IO) {
            var fails = 0
            var regularBack = 0
            kotlinx.coroutines.delay(12_000)
            while (true) {
                val s = _settings.value
                val cur = selectedServer()
                if (s.smartGuard && cur != null) {
                    val ms = runCatching { backend.healthCheck(s.pingUrl) }.getOrDefault(-1L)
                    if (ms > 0) {
                        fails = 0
                        _pings.update { it + (cur.id to Ping(ms, now())) }
                    } else if (!cur.isAuto) {
                        fails++
                    }
                    if (fails >= 2) {
                        fails = 0
                        guardFailover(cur)
                        kotlinx.coroutines.delay(15_000)
                        continue
                    }
                    val net = platform.networkType()
                    val netChanged = lastNet != null && net != lastNet
                    lastNet = net
                    if (!cur.isAuto && s.saveWhitelist && backend.directProbesBypassTunnel && hasWhitelistServers()) {
                        when {
                            // Switched Wi-Fi -> mobile while on a regular server: re-check right away.
                            netChanged && net == NetType.CELLULAR && !cur.isWhitelist && !regularStable() -> {
                                bestOf(whitelistServers())?.let { next ->
                                    _events.emit("Мобильный интернет — перешла на белые списки «${next.name}»")
                                    select(next.id)
                                }
                                regularBack = 0
                            }
                            cur.isWhitelist -> {
                                // Wi-Fi: back as soon as regular servers answer. Mobile: only when they are
                                // stable three checks in a row (~1 min) — a flaky regular route is worse.
                                val ok = if (net == NetType.CELLULAR) regularStable() else regularReachable()
                                regularBack = if (ok) regularBack + 1 else 0
                                val need = if (net == NetType.CELLULAR) 3 else 2
                                if (regularBack >= need) {
                                    regularBack = 0
                                    bestOf(regularServers())?.let { next ->
                                        _events.emit("Обычный интернет стабилен — перешла на «${next.name}», чтобы не тратить трафик белых списков")
                                        select(next.id)
                                    }
                                }
                            }
                            else -> regularBack = 0
                        }
                    } else {
                        regularBack = 0
                    }
                }
                kotlinx.coroutines.delay(20_000)
            }
        }
    }

    private var lastNet: NetType? = null

    private fun whitelistServers() = allServers().filter { !it.isAuto && it.isWhitelist }
    private fun regularServers() = allServers().filter { !it.isAuto && !it.isWhitelist }
    private fun hasWhitelistServers() = whitelistServers().isNotEmpty()

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
                else -> bestOf(whitelistServers())?.also {
                    _events.emit("Мобильный интернет: белые списки сейчас надёжнее — подключаюсь через «${it.name}»")
                } ?: server
            }
            NetType.WIFI, NetType.ETHERNET -> when {
                !server.isWhitelist -> server
                regularReachable() -> bestOf(regularServers())?.also {
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
        val next = bestOf(pool.filter { it.name != cur.name }) ?: return
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
        app.ghostly.core.vpn.Probe.method = after.pingMethod
        store.save(SETTINGS, AppSettings.serializer(), after)
        if (before.startOnBoot != after.startOnBoot) runCatching { platform.setStartOnBoot(after.startOnBoot) }
        // Providers send another format to mihomo (Clash YAML with groups): fetch subscriptions again.
        if (before.core != after.core) {
            refreshAll()
            warnedXrayOnly.clear()
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
    )

    fun haptic() {
        if (_settings.value.haptics) platform.haptic()
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
        UiState(_selected.value, _favorites.value, _pings.value, _onboarded.value, dismissedUpdate),
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

        fun now(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
