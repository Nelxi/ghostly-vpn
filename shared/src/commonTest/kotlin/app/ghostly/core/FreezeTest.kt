package app.ghostly.core

import app.ghostly.core.account.CabinetException
import app.ghostly.core.account.Freeze
import app.ghostly.core.account.FreezeAnswer
import app.ghostly.ui.Format
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreezeTest {

    @Test
    fun readsTheServersAnswer() {
        // As POST /sub/<id>/freeze answers (ghostlink_freeze.status), with fields the app doesn't use.
        val a = JsonX.decodeFromString(
            FreezeAnswer.serializer(),
            """{"ok":true,"sub_id":"sub_abc123","message":"Подписка заморожена до 18.11 в 05:31 (МСК)",
               "freeze":{"frozen":true,"until_ms":1794969060000,"budget_left_ms":1987200000,"budget_left_days":23,
               "freeze_days":7,"budget_days":30,"can_freeze":false,"reason":"Подписка уже заморожена.","next_days":0,
               "expiry_ms":1797000000000}}""",
        )
        val s = a.freeze!!
        assertTrue(a.ok)
        assertTrue(s.frozen)
        assertEquals(1794969060000, s.untilMs)
        assertEquals(23, s.budgetLeftDays)
        assertFalse(s.canFreeze)
        assertEquals("Подписка уже заморожена.", s.reason)
    }

    @Test
    fun rulesSayItEndsByItself() {
        val s = JsonX.decodeFromString(FreezeAnswer.serializer(), """{"ok":true,"freeze":{"can_freeze":true,"next_days":7,"budget_left_days":30}}""").freeze!!
        val rules = Freeze.rules(s)
        assertEquals("Заморозка длится 7 дней и снимется сама", rules.first())
        assertEquals("Всего до 30 дней заморозки за оплаченный срок, осталось 30", rules.last())
        // Near the end of the budget the next freeze is shorter.
        assertEquals("Заморозка длится 2 дня и снимется сама", Freeze.rules(s.copy(nextDays = 2)).first())
    }

    @Test
    fun anotherDeviceIsSentToTheSiteOnlyByOurOwnLink() {
        fun refused(code: String, url: String) = Freeze.refused(
            CabinetException("Заморозить подписку можно с её первого устройства или в кабинете на сайте.", code, 403,
                JsonObject(mapOf("url" to JsonPrimitive(url)))),
        )
        assertEquals("https://ghostlyvpn.com/#cabinet", refused("not_owner_device", "https://ghostlyvpn.com/#cabinet").cabinetUrl)
        assertNull(refused("not_owner_device", "https://example.com/#cabinet").cabinetUrl)
        assertNull(refused("budget", "https://ghostlyvpn.com/#cabinet").cabinetUrl)
    }

    @Test
    fun untilShowsDateAndTime() {
        // 2026-11-18 02:31 UTC = 05:31 in Moscow.
        assertEquals("18 ноября, 05:31", Format.dateTimeShort(1794969060000, 180))
        assertEquals("18 ноября, 02:31", Format.dateTimeShort(1794969060000, 0))
    }
}
