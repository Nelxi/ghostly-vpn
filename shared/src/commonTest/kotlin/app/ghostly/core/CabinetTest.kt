package app.ghostly.core

import app.ghostly.core.account.Cabinet
import app.ghostly.core.account.CabinetApi
import app.ghostly.core.account.CabinetState
import app.ghostly.core.account.Sha256
import app.ghostly.ui.components.rub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CabinetTest {

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        // Longer than one 64-byte block.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        // Non-ASCII goes in as UTF-8, like Python's str.encode().
        assertEquals(Sha256.hex("привет").length, 64)
    }

    @Test
    fun deviceKeyIsTheServersShortId() {
        // ghostlink_hwid.device_key: sha256(hwid).hexdigest()[:12]
        assertEquals("ba7816bf8f01", Cabinet.deviceKey("abc"))
    }

    @Test
    fun sessionCookieIsTakenAndADeletedTwinDoesNotWin() {
        val set = listOf(
            "gv_s=TOKEN123; Domain=.ghostlynex.fun; Expires=Thu, 18 Nov 2027 00:00:00 GMT; Max-Age=34560000; Secure; HttpOnly; Path=/; SameSite=Lax",
            "gv_d=DEVICEKEY; Domain=.ghostlynex.fun; Max-Age=34560000; Secure; HttpOnly; Path=/; SameSite=Lax",
            "gv_s=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; Path=/; SameSite=Lax",
        )
        assertEquals("TOKEN123" to "DEVICEKEY", CabinetApi.applyCookies(set, null, null))
    }

    @Test
    fun signOutClearsTheSessionButKeepsTheDevice() {
        val set = listOf("gv_s=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
        assertEquals(null to "DEV", CabinetApi.applyCookies(set, "OLD", "DEV"))
        // Nothing about the session in an answer: it stays.
        assertEquals("OLD" to "DEV", CabinetApi.applyCookies(listOf("other=1; Path=/"), "OLD", "DEV"))
    }

    @Test
    fun cookieHeaderCarriesBoth() {
        assertEquals("gv_s=T; gv_d=D", CabinetApi.cookieHeader("T", "D"))
        assertEquals("gv_d=D", CabinetApi.cookieHeader(null, "D"))
        assertNull(CabinetApi.cookieHeader("", null))
    }

    @Test
    fun basesStartWithWhatWorkedThenTheSubscriptionsHost() {
        assertEquals(
            listOf("https://ghostlynex.fun", "https://ghostlinknex.online"),
            CabinetApi.baseOrder(null, "https://ghostlinknex.online/sub/sub_ec3df87ca5d81fc8"),
        )
        assertEquals(
            listOf("https://ghostlinknex.online", "https://ghostlynex.fun"),
            CabinetApi.baseOrder("https://ghostlinknex.online", null),
        )
        // Another provider's link is never asked.
        assertEquals(listOf("https://ghostlynex.fun", "https://ghostlinknex.online"), CabinetApi.baseOrder(null, "https://example.com/sub/abc"))
    }

    @Test
    fun queryParamsOfTheServersLinks() {
        assertEquals("CODE1", CabinetApi.queryParam("https://ghostlynex.fun/?app=CODE1#cabinet", "app"))
        assertEquals("P-2_x", CabinetApi.queryParam("https://ghostlynex.fun/?pair=P-2_x#renew=sub_1", "pair"))
        assertNull(CabinetApi.queryParam("https://ghostlynex.fun/#cabinet", "app"))
    }

    @Test
    fun subscriptionIds() {
        assertEquals("sub_ec3df87ca5d81fc8", Cabinet.subIdOf("https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8"))
        assertEquals("sub_ec3df87ca5d81fc8", Cabinet.subIdOf("https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8/?fmt=json#Ghostly"))
        assertNull(Cabinet.subIdOf("https://example.com/s/abc"))
    }

    @Test
    fun ghostlyLinksOpenTheCabinet() {
        assertEquals("renew" to "sub_1c6fc7d243691bfb", Cabinet.linkTarget("https://ghostlynex.fun/#renew=sub_1c6fc7d243691bfb"))
        assertEquals("renew" to null, Cabinet.linkTarget("https://t.me/GhostlyVPNruBot?start=renew"))
        assertEquals("cabinet" to null, Cabinet.linkTarget("https://ghostlinknex.online/#cabinet"))
        assertEquals("cabinet" to null, Cabinet.linkTarget("ghostly://cabinet"))
        assertEquals("cabinet" to null, Cabinet.linkTarget("ghostly://paid?o=123"))
        assertNull(Cabinet.linkTarget("ghostly://import/https%3A%2F%2Fexample.com"))
        assertNull(Cabinet.linkTarget("https://t.me/GhostlySupportBot?start=support"))
        assertNull(Cabinet.linkTarget("https://t.me/someoneelse?start=renew"))
        assertNull(Cabinet.linkTarget("https://example.com/#renew=sub_1"))
        assertNull(Cabinet.linkTarget("https://ghostlynex.fun/docs/oferta"))
    }

    @Test
    fun cabinetStateFromTheServer() {
        // Trimmed from a real /api/web/app/state answer (ids and numbers changed).
        val json = """
        {"ok":true,"auth":true,"trial":false,
         "prices":{"day":20,"gb":3,"gb_month_max":10,"device":30,"devices_max":10,
                   "pools":{"wl":{"title":"Белые списки","price":5,"base":10,"max":20},"reg":{"title":"Обычные серверы","price":3,"base":40,"max":50}}},
         "topup":{"min":10,"max":100000},
         "account":{"id":"w_abcdefghij","login":"ghost","guest":false,"totp":false,"created":1759000000,"backup_left":0,"tg":{"username":"listopad","since":1759100000}},
         "bot":"GhostlyVPNruBot","admin":true,"balance":12.5,
         "history":[{"delta":-140,"type":"extend_days","note":"extend +7d","ts":1760400000}],
         "subs":[
           {"id":"sub_1111111111111111","plan":"1 Месяц","plan_id":"month_1","expiry":1762000000000,"active":true,"traffic_gb":50,"used":1073741824,
            "devices":3,"url":"https://ghostlynex.fun/sub/sub_1111111111111111","page":"https://ghostlynex.fun/sub/sub_1111111111111111",
            "backup":null,"pools":{"cycle_end":1762000000000,"unlimited":false,
              "reg":{"title":"Обычные серверы","used":536870912,"limit_gb":40,"topup_left":10,"price":3,"blocked":false},
              "wl":{"title":"Белые списки","used":0,"limit_gb":10,"topup_left":20,"price":5,"blocked":false}},
            "hw":[{"id":"ba7816bf8f01","model":"Nothing A059","os":"Android 16","ago":"5 минут назад","active":true}],"hwb":[]},
           {"id":"sub_2222222222222222","plan":"Навсегда · безлимит","plan_id":"week_1","expiry":0,"active":true,"traffic_gb":0,"used":5,
            "devices":0,"url":"https://ghostlynex.fun/sub/sub_2222222222222222","pools":{"cycle_end":0,"unlimited":true},"bot":true,"hw":[],"hwb":[]}],
         "orders":[{"id":"abcdef123456","kind":"plan","amount":129,"status":"awaiting","url":"https://pay.example/x","title":"1 Месяц","created":1760400000}],
         "ref":{"code":"GH0ST","link":"https://ghostlynex.fun/?ref=GH0ST","count":2,"earned":77,"percent":30,"max":300},
         "gb_left":10,"sessions":[{"id":"0123456789","ua":"GhostlyVPN/0.3.36 (Android 16; Nothing A059)","ip":"1.2.3.4","last":1760400000,"current":true}],
         "plans":[{"id":"week_1","title":"1 Неделя","days":7,"amount":49},{"id":"month_3","title":"3 Месяца","days":90,"amount":329}],
         "popular":"month_3","scope":"app","bridge":true,
         "bot_state":{"prices":{"month_3":{"base":329,"percent":40,"amount":197}},"discount_left":3600,
                      "pending":{"id":"0a1b2c3d4e","kind":"plan","status":"awaiting","title":"1 Месяц","amount":129,"url":null,"created":1760400000,"promo":null,"balance_used":0}}}
        """
        val st = JsonX.decodeFromString(CabinetState.serializer(), json)
        assertTrue(st.auth)
        assertFalse(st.fullAccess)
        assertEquals(12.5, st.balance)
        assertEquals(2, st.subs.size)
        val site = st.subs[0]
        assertEquals(40, site.pools?.reg?.limitGb)
        assertEquals(listOf("reg", "wl"), site.pools?.all()?.map { it.first })
        assertEquals("ba7816bf8f01", site.hw.single().id)
        assertFalse(site.unlimitedTime)
        val bot = st.subs[1]
        assertTrue(bot.bot)
        assertTrue(bot.unlimitedTime)
        assertTrue(bot.unlimitedTraffic)
        assertEquals(197, st.botState?.prices?.get("month_3")?.amount)
        assertTrue(st.botState?.pending?.open == true)
        assertTrue(st.orders.single().open)
        assertEquals("3 Месяца", st.plan("month_3")?.title)
        assertEquals("GhostlyVPNruBot", st.botName)
    }

    @Test
    fun anonymousStateHasPlansAndNoAccount() {
        val st = JsonX.decodeFromString(
            CabinetState.serializer(),
            """{"auth":false,"bridge":false,"ok":true,"plans":[{"amount":49,"days":7,"id":"week_1","title":"1 Неделя"}],"popular":"month_3","prices":{"day":20},"scope":null,"topup":{"max":100000,"min":10},"trial":true}""",
        )
        assertFalse(st.auth)
        assertTrue(st.trial)
        assertNull(st.account)
        assertEquals(1, st.plans.size)
    }

    @Test
    fun rubles() {
        assertEquals("129 ₽", rub(129))
        assertEquals("1 290 ₽", rub(1290))
        assertEquals("12,5 ₽", rub(12.5))
        assertEquals("0,05 ₽", rub(0.05))
        assertEquals("−140 ₽", rub(-140))
        assertEquals("0 ₽", rub(0.0))
    }
}
