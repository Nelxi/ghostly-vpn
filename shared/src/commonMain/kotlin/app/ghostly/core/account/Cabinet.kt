package app.ghostly.core.account

import app.ghostly.core.store.FileStore
import app.ghostly.core.vpn.Haptic
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** What the cabinet screen shows. */
sealed interface CabinetUi {
    data object Idle : CabinetUi
    data object Loading : CabinetUi

    /** Not signed in. [anon] is what the server tells anyone (plans, whether a trial is free); [why] the reason
     *  the app could not sign in by itself; [bot]: the subscription was bought in the Telegram bot. */
    data class SignedOut(val anon: CabinetState?, val why: String? = null, val bot: Boolean = false) : CabinetUi

    data class Ready(val state: CabinetState) : CabinetUi
    data class Failed(val message: String) : CabinetUi
}

/** A card payment opened in the browser and watched until the order settles. */
data class PaymentWait(
    val orderId: String,
    val bot: Boolean,
    val title: String,
    val amount: Int,
    val url: String?,
    val status: String = "awaiting",
    val startedAt: Long = Cabinet.now(),
)

/** Sign-in through Telegram: the bot asks «Войти?», the app waits for the answer. */
data class TelegramWait(val login: TgLogin, val startedAt: Long = Cabinet.now())

/** Where the cabinet should go when it opens: "cabinet", "renew", "topup", "devices"... */
data class CabinetIntent(val section: String, val subId: String? = null, val at: Long = Cabinet.now())

/** The result of an action, for the sheet that asked: [code] "funds" means the balance is short. */
data class Outcome(val ok: Boolean, val message: String? = null, val code: String? = null, val data: JsonObject? = null)

/**
 * The Ghostly account inside the app: sign-in, the subscription, balance, devices, purchases.
 *
 * Everything goes through the site's own API ([CabinetApi]); subscriptions bought in the Telegram bot are
 * handled by the bot itself on the server's side (`/api/web/app/bot/<action>`). The app signs in by itself
 * on the device that added its Ghostly subscription first; anywhere else the person signs in once (Telegram
 * or login and password) and the session stays in the app.
 *
 * Actions run in [scope], not in the screen's: a purchase finishes even if the screen goes away mid-request.
 */
class Cabinet(
    private val platform: PlatformInfo,
    store: FileStore,
    private val scope: CoroutineScope,
    private val toast: suspend (String) -> Unit,
    private val haptic: (Haptic) -> Unit,
    /** The Ghostly subscription in use in the app, null if there is none. */
    private val ghostlySub: () -> String?,
    /** Ids (`sub_…`) of the subscriptions added to the app. */
    private val appSubIds: () -> Set<String>,
    private val importSub: (String) -> Unit,
    /** Re-fetches the app's profile carrying this subscription id (its expiry and traffic changed). */
    private val refreshSub: (String) -> Unit,
    tunnelPort: () -> Int?,
) {
    val api = CabinetApi(platform, store).also {
        it.tunnelPort = tunnelPort
        it.subscriptionUrl = ghostlySub
    }

    private val _ui = MutableStateFlow<CabinetUi>(CabinetUi.Idle)
    val ui: StateFlow<CabinetUi> = _ui.asStateFlow()

    /** Key of the action in progress ("extend", "device:<id>", "checkout"...), null when idle. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _payment = MutableStateFlow<PaymentWait?>(null)
    val payment: StateFlow<PaymentWait?> = _payment.asStateFlow()

    private val _telegram = MutableStateFlow<TelegramWait?>(null)
    val telegram: StateFlow<TelegramWait?> = _telegram.asStateFlow()

    /** Password accepted, the two-step code is next: the server's ticket for it. */
    private val _ticket = MutableStateFlow<String?>(null)
    val ticket: StateFlow<String?> = _ticket.asStateFlow()

    /** The subscription in the app has no recorded owner: may it be attached to this account? */
    private val _claim = MutableStateFlow<PairInfo?>(null)
    val claim: StateFlow<PairInfo?> = _claim.asStateFlow()

    private val _intent = MutableStateFlow<CabinetIntent?>(null)
    val intent: StateFlow<CabinetIntent?> = _intent.asStateFlow()

    /** The id the server gives this device in device lists (sha256 of the HWID, 12 hex). */
    val deviceId: String by lazy { deviceKey(platform.hwid) }

    val state: CabinetState? get() = (_ui.value as? CabinetUi.Ready)?.state

    private var loadJob: Job? = null
    private var tgJob: Job? = null
    private var payJob: Job? = null
    private var pairCode: String? = null
    private var claimCode: String? = null
    private var autoTried = false

    // ------------------------------------------------------------------ navigation

    fun request(section: String, subId: String? = null) {
        _intent.value = CabinetIntent(section, subId)
    }

    fun consumeIntent() {
        _intent.value = null
    }

    // ------------------------------------------------------------------ loading

    /** Screenshots and previews: show [ui] and never ask the server. */
    fun preview(ui: CabinetUi) {
        frozen = true
        _ui.value = ui
    }

    private var frozen = false

    /** The screen became visible: keep what is shown and bring it up to date. */
    fun open() {
        if (frozen || loadJob?.isActive == true) return
        loadJob = scope.launch { load() }
    }

    fun refresh() {
        if (frozen) return
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    private suspend fun load() {
        val shown = _ui.value
        if (shown !is CabinetUi.Ready) _ui.value = CabinetUi.Loading
        var offline = false
        if (api.hasSession) {
            try {
                val st = fetchState()
                if (st.auth) {
                    show(st)
                    return
                }
                api.forgetSession() // ended on the server (signed out elsewhere, sessions revoked)
            } catch (e: CabinetException) {
                if (shown is CabinetUi.Ready) {
                    _ui.value = shown
                    toast(e.message.orEmpty())
                    return
                }
                if (e.network) offline = true else {
                    _ui.value = CabinetUi.Failed(e.message.orEmpty())
                    return
                }
            }
        }
        var why: String? = null
        var bot = false
        val sub = ghostlySub()
        if (!offline && sub != null && api.autoSignIn && !autoTried) {
            autoTried = true
            val auto = autoSignIn(sub)
            if (auto.state != null) {
                show(auto.state)
                return
            }
            why = auto.why
            bot = auto.bot
            offline = auto.network
        }
        val anon = if (offline) null else runCatching { fetchState() }.getOrNull()
        _ui.value = if (anon == null && offline) CabinetUi.Failed(tr("Нет связи с сервером Ghostly. Проверьте интернет и попробуйте ещё раз"))
        else CabinetUi.SignedOut(anon, why, bot)
    }

    private suspend fun fetchState(): CabinetState = api.decode(CabinetState.serializer(), api.post("/api/web/app/state", safe = true))

    private fun show(st: CabinetState) {
        _ui.value = CabinetUi.Ready(st)
    }

    private suspend fun reload() {
        runCatching { fetchState() }.onSuccess { st -> if (st.auth) show(st) else signedOutLocally() }
    }

    private suspend fun signedOutLocally() {
        api.forgetSession()
        _ui.value = CabinetUi.SignedOut(runCatching { fetchState() }.getOrNull())
    }

    // ------------------------------------------------------------------ signing in

    private data class Auto(val state: CabinetState? = null, val why: String? = null, val bot: Boolean = false, val network: Boolean = false)

    /** The app's own subscription opens the account it belongs to (first device, or one the owner confirmed). */
    private suspend fun autoSignIn(subUrl: String): Auto {
        val id = subIdOf(subUrl) ?: return Auto()
        val link = try {
            api.decode(GhostlyAccount.Link.serializer(), api.post("/sub/$id/cabinet", jsonOf("go" to "cabinet"), safe = true, device = true))
        } catch (e: CabinetException) {
            return Auto(why = if (e.network) null else e.message, network = e.network)
        }
        pairCode = if (link.pair) CabinetApi.queryParam(link.url, "pair") else null
        if (!link.login) return Auto(why = link.reason ?: "unknown", bot = link.bot)
        val code = CabinetApi.queryParam(link.url, "app") ?: return Auto(why = "unknown", bot = link.bot)
        return try {
            val st = api.decode(CabinetState.serializer(), api.post("/api/web/app/login", jsonOf("code" to code)))
            if (st.auth) Auto(state = fetchState()) else Auto(why = "stale", bot = link.bot)
        } catch (e: CabinetException) {
            Auto(why = if (e.network) null else e.message, bot = link.bot, network = e.network)
        }
    }

    /** «Войти с этой подпиской» on the sign-in screen: asks the server again. */
    fun signInWithSubscription() = scope.launch {
        val sub = ghostlySub() ?: return@launch
        _busy.value = "auto"
        try {
            api.autoSignIn = true
            val auto = autoSignIn(sub)
            if (auto.state != null) {
                show(auto.state)
                haptic(Haptic.SUCCESS)
            } else {
                val anon = (_ui.value as? CabinetUi.SignedOut)?.anon ?: runCatching { fetchState() }.getOrNull()
                _ui.value = CabinetUi.SignedOut(anon, auto.why, auto.bot)
                toast(reasonText(auto.why, auto.bot) ?: tr("Не получилось войти автоматически"))
            }
        } finally {
            _busy.value = null
        }
    }

    /** Login and password. Null when signed in or when the two-step code is asked next ([ticket]), else the error. */
    suspend fun login(login: String, password: String): String? = background("login") {
        val j = api.post("/api/web/login", jsonOf("login" to login.trim(), "password" to password))
        if (j.bool("need_totp") == true) _ticket.value = j.string("ticket") else signedIn()
    }

    suspend fun totp(code: String): String? = background("login") {
        val t = _ticket.value ?: throw CabinetException(tr("Начните вход заново"))
        api.post("/api/web/login/totp", jsonOf("ticket" to t, "code" to code.trim()))
        _ticket.value = null
        signedIn()
    }

    fun cancelTicket() {
        _ticket.value = null
    }

    /** A new account with a login and password; on a guest account the same call keeps it and adds the password. */
    suspend fun register(login: String, password: String): String? = background("login") {
        api.post("/api/web/register", jsonOf("login" to login.trim(), "password" to password, "consent" to true))
        signedIn()
    }

    /** Sign-in through the Telegram bot: opens it and waits until the person taps «Войти» there. */
    fun telegram() {
        tgJob?.cancel()
        tgJob = scope.launch {
            val tg = try {
                api.decode(TgLogin.serializer(), api.post("/api/web/tg/login/start"))
            } catch (e: CabinetException) {
                toast(e.message.orEmpty())
                return@launch
            }
            _telegram.value = TelegramWait(tg)
            openTelegram()
            val until = now() + tg.ttl * 1000L
            try {
                while (isActive && now() < until) {
                    delay(2_000)
                    val j = try {
                        api.post("/api/web/tg/login/poll", jsonOf("code" to tg.code, "poll" to tg.poll), safe = true)
                    } catch (e: CabinetException) {
                        if (e.network) continue
                        toast(e.message.orEmpty())
                        return@launch
                    }
                    when (j.string("status")) {
                        "pending" -> continue
                        "ok" -> {
                            if (j.bool("need_totp") == true) _ticket.value = j.string("ticket") else signedIn()
                            return@launch
                        }
                        "denied" -> {
                            toast(tr("Вход отклонён в Telegram"))
                            return@launch
                        }
                        else -> {
                            toast(tr("Ссылка для входа устарела, попробуйте ещё раз"))
                            return@launch
                        }
                    }
                }
            } finally {
                _telegram.value = null
            }
        }
    }

    fun openTelegram() {
        _telegram.value?.login?.let { platform.openUrl(it.web.ifBlank { it.tg }) }
    }

    fun cancelTelegram() {
        tgJob?.cancel()
        _telegram.value = null
    }

    private suspend fun signedIn() {
        val st = fetchState()
        show(st)
        haptic(Haptic.SUCCESS)
        api.autoSignIn = true
        pairAfterSignIn()
    }

    /**
     * The server could not sign this device in by itself and handed it a pair code. Now that the person has
     * signed in: the same owner's device is remembered at once; a subscription nobody owns yet is attached
     * only after asking ([claim]).
     */
    private suspend fun pairAfterSignIn() {
        val code = pairCode ?: return
        pairCode = null
        val info = runCatching { api.post("/api/web/app/pair", jsonOf("code" to code)).obj("pair") }.getOrNull()
            ?.let { runCatching { api.decode(PairInfo.serializer(), it) }.getOrNull() } ?: return
        when (info.kind) {
            "trust" -> runCatching { api.post("/api/web/app/pair", jsonOf("code" to code, "confirm" to true)) }
            "claim" -> {
                claimCode = code
                _claim.value = info
            }
        }
    }

    fun answerClaim(yes: Boolean) = scope.launch {
        val code = claimCode
        _claim.value = null
        claimCode = null
        if (!yes || code == null) return@launch
        runCatching { api.post("/api/web/app/pair", jsonOf("code" to code, "confirm" to true)) }
            .onSuccess { toast(tr("Подписка привязана к аккаунту")); reload() }
            .onFailure { toast(it.message.orEmpty()) }
    }

    /** Signs out here and on the server; the app no longer signs in by itself until asked to. */
    fun logout() = scope.launch {
        _busy.value = "logout"
        try {
            runCatching { api.post("/api/web/logout") }
            api.forgetSession()
            api.autoSignIn = false
            _ticket.value = null
            _ui.value = CabinetUi.SignedOut(runCatching { fetchState() }.getOrNull())
        } finally {
            _busy.value = null
        }
    }

    // ------------------------------------------------------------------ actions

    private suspend fun background(key: String, block: suspend () -> Unit): String? = scope.async {
        _busy.value = key
        try {
            block()
            null
        } catch (e: CabinetException) {
            haptic(Haptic.ERROR)
            e.message.orEmpty()
        } finally {
            _busy.value = null
        }
    }.await()

    /** Runs a change on the server, then brings the cabinet (and the app's copy of [subId]) up to date. */
    private suspend fun act(key: String, subId: String?, done: String?, call: suspend () -> JsonObject): Outcome = scope.async {
        if (_busy.value != null && _busy.value != key) return@async Outcome(false, tr("Подождите, предыдущее действие ещё выполняется"))
        _busy.value = key
        try {
            val j = call()
            val message = j.obj("result")?.string("message") ?: j.string("message") ?: done
            reload()
            subId?.let(refreshSub)
            haptic(Haptic.SUCCESS)
            message?.let { toast(it) }
            Outcome(true, message, data = j)
        } catch (e: CabinetException) {
            haptic(Haptic.ERROR)
            Outcome(false, e.message, e.code, e.body)
        } finally {
            _busy.value = null
        }
    }.await()

    private fun botPath(sub: CabinetSub, action: String, site: String) = if (sub.bot) "/api/web/app/bot/$action" else site

    /** Extend by [days] from the balance. */
    suspend fun extend(sub: CabinetSub, days: Int) = act("extend", sub.id, tr("Подписка продлена на {0} {1}", days, app.ghostly.i18n.L10n.plural(days.toLong(), "день", "дня", "дней"))) {
        api.post(botPath(sub, "extend", "/api/web/extend"), jsonOf("sub_id" to sub.id, "days" to days))
    }

    suspend fun buyGb(sub: CabinetSub, pool: String, gb: Int) = act("gb", sub.id, tr("Готово: +{0} ГБ", gb)) {
        api.post(botPath(sub, "gb", "/api/web/gb"), jsonOf("sub_id" to sub.id, "pool" to pool, "gb" to gb))
    }

    suspend fun buyDevices(sub: CabinetSub, count: Int) = act("devices", sub.id, tr("Готово: можно подключить ещё {0}", count)) {
        api.post(botPath(sub, "devices", "/api/web/devices"), jsonOf("sub_id" to sub.id, "count" to count))
    }

    /** [act]: "remove" (disconnect, may come back into a free slot), "ban", "unban". */
    suspend fun device(sub: CabinetSub, device: Device, act: String) = act("device:${device.id}", sub.id, when (act) {
        "remove" -> tr("Устройство отключено")
        "ban" -> tr("Устройство запрещено")
        else -> tr("Устройство снова разрешено")
    }) {
        if (sub.bot) api.post("/api/web/app/bot/device", jsonOf("sub_id" to sub.id, "device" to device.id, "act" to act))
        else api.post("/api/web/device/$act", jsonOf("sub_id" to sub.id, "device" to device.id))
    }

    /** Removes an ended site subscription from the cabinet (orders and history stay). */
    suspend fun hide(sub: CabinetSub) = act("hide", null, tr("Подписка убрана из кабинета")) {
        api.post("/api/web/sub/hide", jsonOf("sub_id" to sub.id))
    }

    /** The price of [plan] with [promo] (and, for a bot subscription, the bot's own discount). */
    suspend fun quote(plan: Plan, promo: String, sub: CabinetSub?): Pair<Quote?, String?> = scope.async {
        try {
            val j = if (sub?.bot == true) api.post("/api/web/app/bot/quote", jsonOf("plan_id" to plan.id, "promo" to promo.trim()), safe = true)
            else api.post("/api/web/promo", jsonOf("plan_id" to plan.id, "code" to promo.trim()), safe = true)
            api.decode(Quote.serializer(), j) to null
        } catch (e: CabinetException) {
            null to e.message
        }
    }.await()

    /**
     * Buys [plan]: for [sub] (its days are added to it) or, with none, a new subscription (the server adds
     * the days to an existing one of the same account anyway). Paid by the balance when it covers the price,
     * otherwise a card page opens in the browser and the order is watched until it is paid.
     */
    suspend fun checkout(plan: Plan, sub: CabinetSub?, promo: String, useBalance: Boolean): Outcome = scope.async {
        _busy.value = "checkout"
        try {
            val code = promo.trim().uppercase()
            val j = if (sub?.bot == true) {
                api.post("/api/web/app/bot/checkout", jsonOf("plan_id" to plan.id, "sub_id" to sub.id, "promo" to code.ifEmpty { null }, "use_balance" to useBalance))
            } else {
                api.post("/api/web/checkout", jsonOf("kind" to "plan", "plan_id" to plan.id, "sub_id" to sub?.id, "promo" to code.ifEmpty { null }, "use_balance" to useBalance))
            }
            afterCheckout(j, bot = sub?.bot == true, title = plan.title, subId = sub?.id)
        } catch (e: CabinetException) {
            haptic(Haptic.ERROR)
            Outcome(false, e.message, e.code, e.body)
        } finally {
            _busy.value = null
        }
    }.await()

    /** Tops the balance up by [amount] ₽ with a card. */
    suspend fun topup(amount: Int): Outcome = scope.async {
        _busy.value = "topup"
        try {
            afterCheckout(api.post("/api/web/checkout", jsonOf("kind" to "topup", "amount" to amount)), bot = false, title = tr("Пополнение баланса"), subId = null)
        } catch (e: CabinetException) {
            haptic(Haptic.ERROR)
            Outcome(false, e.message, e.code, e.body)
        } finally {
            _busy.value = null
        }
    }.await()

    private suspend fun afterCheckout(j: JsonObject, bot: Boolean, title: String, subId: String?): Outcome {
        val result = j.obj("result") ?: j
        val redirect = result.string("redirect")
        val orderId = result.obj("order")?.string("id") ?: result.string("order")
        if (redirect != null && orderId != null) {
            val amount = result.obj("order")?.get("amount")?.let { runCatching { it.toString().toDouble().toInt() }.getOrNull() } ?: 0
            watch(PaymentWait(orderId, bot, title, amount, redirect))
            platform.openUrl(redirect)
            return Outcome(true, tr("Откроется страница оплаты. После оплаты возвращайтесь сюда"), data = j)
        }
        // Paid on the spot (balance, a 100% promo code).
        reload()
        subId?.let(refreshSub)
        addMissingSubs()
        haptic(Haptic.SUCCESS)
        toast(tr("Оплачено. Подписка уже обновилась"))
        return Outcome(true, data = j)
    }

    /** A free trial for this account (made on the spot for a newcomer); the subscription is added to the app. */
    suspend fun trial(): Outcome = act("trial", null, tr("Пробный период включён. Подписка уже в приложении")) {
        api.post("/api/web/trial").also { addMissingSubs(it) }
    }

    suspend fun cancelOrder(order: Order, bot: Boolean) = act("cancel:${order.id}", null, tr("Заказ отменён")) {
        if (bot) api.post("/api/web/app/bot/cancel", jsonOf("order_id" to order.id))
        else api.post("/api/web/order/${order.id}/cancel")
    }

    /** Opens the payment page of an unpaid order again and watches it. */
    fun payOrder(order: Order, bot: Boolean) {
        val url = order.url ?: return
        watch(PaymentWait(order.id, bot, order.title ?: tr("Заказ"), order.amount, url))
        platform.openUrl(url)
    }

    /** A link that hands the subscription over to another account (site: 24 h code; bot: a t.me link). */
    suspend fun transfer(sub: CabinetSub): Pair<String?, String?> = scope.async {
        try {
            val j = if (sub.bot) api.post("/api/web/app/bot/transfer", jsonOf("sub_id" to sub.id))
            else api.post("/api/web/transfer/new", jsonOf("sub_id" to sub.id))
            j.string("link") to null
        } catch (e: CabinetException) {
            null to e.message
        }
    }.await()

    suspend fun claimTransfer(code: String) = act("claim", null, tr("Подписка перешла на ваш аккаунт")) {
        api.post("/api/web/transfer/claim", jsonOf("code" to code.trim())).also { addMissingSubs(it) }
    }

    suspend fun changePassword(old: String, new: String) = act("password", null, tr("Пароль изменён, другие сессии завершены")) {
        api.post("/api/web/password", jsonOf("old" to old, "new" to new))
    }

    /** [id] of a session, or "all" for every other one. */
    suspend fun revokeSession(id: String) = act("session:$id", null, if (id == "all") tr("Остальные сессии завершены") else tr("Сессия завершена")) {
        api.post("/api/web/sessions/revoke", jsonOf("id" to id))
    }

    /** Opens the bot with a one-time code that links Telegram to this account. */
    suspend fun linkTelegram(): String? = background("tglink") {
        val j = api.post("/api/web/tg/link")
        platform.openUrl(j.string("web") ?: j.string("tg") ?: throw CabinetException(tr("Сервер не дал ссылку")))
    }

    /** Adds to the app the active subscriptions of this account it does not have yet. */
    fun addToApp(sub: CabinetSub) {
        importSub(sub.url)
    }

    private suspend fun addMissingSubs(fresh: JsonObject? = null) {
        val st = fresh?.let { runCatching { api.decode(CabinetState.serializer(), it) }.getOrNull() }?.takeIf { it.subs.isNotEmpty() }
            ?: state ?: return
        val have = appSubIds()
        st.subs.filter { it.active && it.id !in have && it.url.isNotBlank() }.forEach { importSub(it.url) }
    }

    // ------------------------------------------------------------------ payments

    private fun watch(w: PaymentWait) {
        payJob?.cancel()
        _payment.value = w
        payJob = scope.launch {
            var n = 0
            while (isActive && now() - w.startedAt < WATCH_MS) {
                delay(if (n < 60) 3_000 else 10_000)
                n++
                val status = runCatching { orderStatus(w, check = n % 4 == 0) }.getOrNull() ?: continue
                _payment.value = _payment.value?.copy(status = status)
                when (status) {
                    "issued" -> {
                        _payment.value = null
                        reload()
                        state?.subs?.forEach { refreshSub(it.id) }
                        addMissingSubs()
                        haptic(Haptic.SUCCESS)
                        toast(tr("Оплата прошла ♡ {0}", w.title))
                        return@launch
                    }
                    "cancelled" -> {
                        _payment.value = null
                        reload()
                        haptic(Haptic.ERROR)
                        toast(tr("Оплата не прошла, деньги не списаны"))
                        return@launch
                    }
                }
            }
            _payment.value = null
        }
    }

    private suspend fun orderStatus(w: PaymentWait, check: Boolean): String {
        val j = if (w.bot) api.post("/api/web/app/bot/order", jsonOf("order_id" to w.orderId, "check" to check), safe = true).obj("order") ?: JsonObject(emptyMap())
        else api.get("/api/web/order/${w.orderId}")
        return j.string("status").orEmpty()
    }

    /** The person came back (the app was opened again, a `ghostly://cabinet` link): look at the order now. */
    fun onReturn() {
        if (frozen) return
        val w = _payment.value ?: run {
            if (_ui.value is CabinetUi.Ready) refresh()
            return
        }
        scope.launch {
            val status = runCatching { orderStatus(w, check = true) }.getOrNull() ?: return@launch
            if (status == "issued" || status == "cancelled") watch(w.copy(startedAt = now() - WATCH_MS + 4_000))
        }
    }

    fun forgetPayment() {
        payJob?.cancel()
        _payment.value = null
    }

    companion object {
        private const val WATCH_MS = 20 * 60_000L

        fun now(): Long = Clock.System.now().toEpochMilliseconds()

        /** `sub_…` of a subscription link, null for anything else. */
        fun subIdOf(url: String): String? =
            url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
                .takeIf { Regex("sub_[A-Za-z0-9_]{6,48}").matches(it) }

        /** The server's short device id: the first 12 hex digits of SHA-256 of the HWID. */
        fun deviceKey(hwid: String): String = Sha256.hex(hwid).take(12)

        private const val BOT = "ghostlyvpnrubot"

        /**
         * The cabinet section a link of Ghostly's leads to, or null for any other link: `ghostly://cabinet`
         * (and `ghostly://renew`), the site's `#cabinet` / `#renew=<sub>`, the bot's `?start=renew`.
         */
        fun linkTarget(url: String): Pair<String, String?>? {
            val u = url.trim()
            val lower = u.lowercase()
            if (lower.startsWith("ghostly://")) {
                val what = lower.removePrefix("ghostly://").substringBefore('?').substringBefore('/').substringBefore('#')
                return when (what) {
                    "cabinet", "paid", "account" -> "cabinet" to null
                    "renew" -> "renew" to null
                    "topup" -> "topup" to null
                    else -> null
                }
            }
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
            val host = app.ghostly.core.sub.GhostlyDomains.hostOf(u)
            if (host == "t.me" || host == "telegram.me") {
                val bot = u.substringAfter("://").substringAfter('/').substringBefore('?').substringBefore('/').lowercase()
                return if (bot == BOT && "start=renew" in lower) "renew" to null else null
            }
            if (!app.ghostly.core.sub.GhostlyDomains.isOurs(u)) return null
            val fragment = u.substringAfter('#', "")
            return when {
                fragment.startsWith("renew=") -> "renew" to fragment.removePrefix("renew=").substringBefore('&').takeIf { it.isNotBlank() }
                fragment == "renew" -> "renew" to null
                fragment == "cabinet" -> "cabinet" to null
                else -> null
            }
        }

        /** Why the app could not sign in by itself, in words; null when there is nothing to explain. */
        fun reasonText(why: String?, bot: Boolean): String? = when (why) {
            null -> null
            "not_first_device" -> tr("Подписку сначала добавили на другом устройстве. Войдите один раз, и дальше приложение будет входить само")
            "totp" -> tr("У аккаунта включена двухэтапная защита: войдите по логину и коду")
            "revoked" -> tr("Эта подписка отозвана. Войдите в аккаунт, чтобы узнать подробности")
            "no_owner", "ambiguous" -> tr("Не получилось понять, чей это аккаунт. Войдите через Telegram или по логину")
            "no_device_id", "device_not_registered" -> tr("Приложение ещё не обновляло эту подписку. Обновите её и попробуйте снова")
            "unknown_sub" -> tr("Сервер не знает эту подписку")
            "rate" -> tr("Слишком много попыток. Подождите пару минут")
            "stale", "unknown" -> if (bot) tr("Подписка из Telegram-бота: войдите через Telegram") else tr("Войдите, чтобы управлять подпиской")
            else -> why
        }
    }
}
