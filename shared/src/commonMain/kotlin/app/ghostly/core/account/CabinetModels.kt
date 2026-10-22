package app.ghostly.core.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ghostly's Telegram bot, when the server didn't name one (signed out, older server). */
const val GHOSTLY_BOT = "GhostlyVPNruBot"

/**
 * The Ghostly cabinet as the site's API returns it (`/api/web/app/state`, and the same body after most
 * actions). Field names follow the server; everything has a default, so an older or newer server never
 * breaks the parse.
 */
@Serializable
data class CabinetState(
    val ok: Boolean = false,
    val auth: Boolean = false,
    /** A free trial is still available here (this account, this device). */
    val trial: Boolean = false,
    val prices: Prices = Prices(),
    val topup: Range = Range(),
    val account: Account? = null,
    /** Username of the Telegram bot (where bot subscriptions were bought). */
    val bot: String? = null,
    val balance: Double = 0.0,
    val history: List<HistoryItem> = emptyList(),
    val subs: List<CabinetSub> = emptyList(),
    val orders: List<Order> = emptyList(),
    val ref: Referral? = null,
    @SerialName("gb_left") val gbLeft: Int = 0,
    val sessions: List<Session> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val popular: String? = null,
    /** "app" — a session the app opened by itself: password, 2FA, transfers need an ordinary sign-in. */
    val scope: String? = null,
    /** The bot answers: subscriptions bought there can be managed from here. */
    val bridge: Boolean = false,
    @SerialName("bot_state") val botState: BotState? = null,
) {
    val fullAccess: Boolean get() = scope != "app"
    val botName: String get() = bot?.takeIf { it.isNotBlank() } ?: GHOSTLY_BOT
    fun plan(id: String?): Plan? = plans.firstOrNull { it.id == id }
}

@Serializable
data class Prices(
    /** Price of one day of extension, ₽. */
    val day: Int = 0,
    val gb: Int = 0,
    @SerialName("gb_month_max") val gbMonthMax: Int = 0,
    /** Price of one extra device, ₽. */
    val device: Int = 0,
    @SerialName("devices_max") val devicesMax: Int = 10,
    val pools: Map<String, PoolPrice> = emptyMap(),
)

@Serializable
data class PoolPrice(val title: String = "", val price: Int = 0, val base: Int = 0, val max: Int = 0)

@Serializable
data class Range(val min: Int = 10, val max: Int = 100_000)

@Serializable
data class Account(
    val id: String = "",
    val login: String? = null,
    /** Made by a purchase without registration: no password to come back with. */
    val guest: Boolean = false,
    val totp: Boolean = false,
    val created: Long = 0,
    @SerialName("backup_left") val backupLeft: Int = 0,
    val tg: TgLink? = null,
)

@Serializable
data class TgLink(val username: String? = null, val since: Long = 0)

@Serializable
data class HistoryItem(val delta: Int = 0, val type: String = "", val note: String? = null, val ts: Long = 0)

@Serializable
data class CabinetSub(
    val id: String = "",
    val plan: String = "",
    @SerialName("plan_id") val planId: String? = null,
    /** Unix millis, 0 = no end. */
    val expiry: Long = 0,
    val active: Boolean = false,
    /** 0 = unlimited traffic. */
    @SerialName("traffic_gb") val trafficGb: Int = 0,
    val used: Long = 0,
    /** Device limit, 0 = unlimited. */
    val devices: Int = 0,
    val url: String = "",
    val page: String? = null,
    val backup: String? = null,
    val pools: SubPools? = null,
    val hw: List<Device> = emptyList(),
    val hwb: List<Device> = emptyList(),
    /** Bought in the Telegram bot: managed through the bot (see [CabinetState.bridge]). */
    val bot: Boolean = false,
) {
    val unlimitedTime: Boolean get() = expiry <= 0L
    val unlimitedTraffic: Boolean get() = trafficGb <= 0 || pools?.unlimited == true
}

@Serializable
data class SubPools(
    @SerialName("cycle_end") val cycleEnd: Long = 0,
    val unlimited: Boolean = false,
    val reg: Pool? = null,
    val wl: Pool? = null,
) {
    fun all(): List<Pair<String, Pool>> = listOfNotNull(reg?.let { "reg" to it }, wl?.let { "wl" to it })
}

@Serializable
data class Pool(
    val title: String = "",
    val used: Long = 0,
    @SerialName("limit_gb") val limitGb: Int = 0,
    @SerialName("topup_left") val topupLeft: Int = 0,
    val price: Int = 0,
    val blocked: Boolean = false,
)

@Serializable
data class Device(val id: String = "", val model: String = "", val os: String? = null, val ago: String? = null, val active: Boolean = true)

@Serializable
data class Order(
    val id: String = "",
    val kind: String = "plan",
    val amount: Int = 0,
    /** new / awaiting / paid / provisioning / issued / cancelled */
    val status: String = "",
    val url: String? = null,
    val title: String? = null,
    val created: Long = 0,
    val promo: String? = null,
    @SerialName("balance_used") val balanceUsed: Int = 0,
) {
    val open: Boolean get() = status == "new" || status == "awaiting"
}

@Serializable
data class Referral(
    val code: String? = null,
    val link: String? = null,
    val count: Int = 0,
    val earned: Int = 0,
    val percent: Int = 30,
    val max: Int = 300,
)

@Serializable
data class Session(val id: String = "", val ua: String = "", val ip: String = "", val last: Long = 0, val current: Boolean = false)

@Serializable
data class Plan(val id: String = "", val title: String = "", val days: Int = 0, val amount: Int = 0)

@Serializable
data class BotState(
    val prices: Map<String, BotPrice> = emptyMap(),
    /** Seconds left of the bot's after-trial discount, 0 = none. */
    @SerialName("discount_left") val discountLeft: Long = 0,
    val pending: Order? = null,
)

@Serializable
data class BotPrice(val base: Int = 0, val percent: Int = 0, val amount: Int = 0)

/** `/api/web/tg/login/start`. */
@Serializable
data class TgLogin(val code: String = "", val poll: String = "", val ttl: Int = 300, val bot: String = "", val tg: String = "", val web: String = "")

/** A price with a promo code (`/api/web/promo`, the bot's quote). */
@Serializable
data class Quote(val discount: Int = 0, val total: Int = 0, val amount: Int = 0, val percent: Int = 0, val base: Int = 0)

/** `/api/web/order/<id>` and the bot's order. */
@Serializable
data class OrderStatus(val status: String = "", val title: String? = null, val amount: Int = 0, val url: String? = null)

/** `/api/web/app/pair` without confirm: what confirming would do. */
@Serializable
data class PairInfo(val kind: String = "", val device: String = "", val plan: String = "")
