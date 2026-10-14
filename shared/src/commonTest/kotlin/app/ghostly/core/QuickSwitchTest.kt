package app.ghostly.core

import app.ghostly.core.model.SubscriptionInfo
import app.ghostly.core.vpn.QuickSwitch
import app.ghostly.i18n.L10n
import app.ghostly.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickSwitchTest {
    private val order = listOf("a", "b", "c", "d")

    @Test
    fun stepsThroughFavouritesInListOrderAndWraps() {
        val favs = setOf("d", "b")
        assertEquals("d", QuickSwitch.next(order, favs, "b"))
        assertEquals("b", QuickSwitch.next(order, favs, "d"))
    }

    @Test
    fun fromANonFavouriteGoesToTheFirstFavourite() {
        assertEquals("b", QuickSwitch.next(order, setOf("c", "b"), "a"))
        assertEquals("c", QuickSwitch.next(order, setOf("c"), null))
    }

    @Test
    fun nowhereToGo() {
        assertNull(QuickSwitch.next(order, emptySet(), "a"))
        assertNull(QuickSwitch.next(order, setOf("a"), "a"))
        // A favourite that is no longer in any subscription doesn't count.
        assertNull(QuickSwitch.next(order, setOf("gone"), "a"))
    }

    @Test
    fun subscriptionLine() {
        L10n.install("ru", emptyMap())
        val now = 1_000_000_000_000L
        val gb = 1L shl 30
        val info = SubscriptionInfo(upload = gb, download = 33 * gb, total = 100 * gb, expire = now / 1000 + 12 * 86_400 + 60)
        assertEquals("Ghostly · Осталось 12 дней · 34 ГБ из 100 ГБ", Format.subscriptionLine("Ghostly", info, now))
        assertEquals("Ghostly", Format.subscriptionLine("Ghostly", SubscriptionInfo(), now))
        assertEquals("Подписка истекла", Format.subscriptionLine(null, info.copy(expire = 5, total = 0), now))
        assertNull(Format.subscriptionLine(" ", null, now))
    }
}
