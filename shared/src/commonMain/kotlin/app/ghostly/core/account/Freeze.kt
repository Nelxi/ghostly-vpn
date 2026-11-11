package app.ghostly.core.account

import app.ghostly.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Freezing a Ghostly subscription: a fixed pause (7 days) during which the VPN is off on every device
 * and the subscription's term stands still; it ends by itself and may be taken off earlier. The server
 * keeps all the rules (paid plans of a month or longer, a budget of days per paid term) and says what
 * is possible right now; the app only shows it.
 *
 * Asked through `POST /sub/<id>/freeze` {op: status | freeze | unfreeze} with the device's x-hwid: the
 * same trust as the app's cabinet sign-in (the subscription's first device, or one its owner confirmed).
 */
@Serializable
data class FreezeState(
    val frozen: Boolean = false,
    @SerialName("until_ms") val untilMs: Long = 0,
    @SerialName("budget_left_days") val budgetLeftDays: Int = 0,
    @SerialName("freeze_days") val freezeDays: Int = 7,
    @SerialName("budget_days") val budgetDays: Int = 30,
    @SerialName("can_freeze") val canFreeze: Boolean = false,
    /** Why it cannot be frozen now, in the server's words. */
    val reason: String = "",
    /** How long the next freeze lasts (less than [freezeDays] when the budget is nearly spent). */
    @SerialName("next_days") val nextDays: Int = 0,
)

@Serializable
data class FreezeAnswer(
    val ok: Boolean = false,
    val freeze: FreezeState? = null,
    val message: String = "",
)

/** What the app shows for a freeze request. */
sealed interface FreezeResult {
    data class Done(val state: FreezeState, val message: String) : FreezeResult

    /** The server refused; [cabinetUrl] is set when the site cabinet can do it instead (another device). */
    data class Refused(val message: String, val cabinetUrl: String?) : FreezeResult
}

object Freeze {
    const val STATUS = "status"
    const val FREEZE = "freeze"
    const val UNFREEZE = "unfreeze"

    /** Codes after which the person should go to the site cabinet. */
    private val GO_TO_SITE = setOf("not_owner_device", "no_device_id")

    fun refused(e: CabinetException): FreezeResult.Refused {
        val url = e.body?.get("url")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { e.code in GO_TO_SITE && it.startsWith("https://") && app.ghostly.core.sub.GhostlyDomains.isOurs(it) }
        return FreezeResult.Refused(e.message.orEmpty(), url)
    }

    /** The rules, as the warning before freezing. */
    fun rules(s: FreezeState): List<String> = listOf(
        tr("Заморозка длится {0} {1} и снимется сама", s.nextDays.takeIf { it > 0 } ?: s.freezeDays,
            app.ghostly.i18n.L10n.plural((s.nextDays.takeIf { it > 0 } ?: s.freezeDays).toLong(), "день", "дня", "дней")),
        tr("Пока подписка заморожена, VPN не работает ни на одном устройстве, а её срок стоит на месте"),
        tr("Разморозить можно в любой момент: неиспользованные дни вернутся в запас. После окончания можно заморозить снова"),
        tr("Всего до {0} дней заморозки за оплаченный срок, осталось {1}", s.budgetDays, s.budgetLeftDays),
    )
}
