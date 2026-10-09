package app.ghostly.core

import app.ghostly.core.account.GhostlyAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GhostlyAccountTest {

    @Test
    fun theRequestGoesNextToTheSubscription() {
        assertEquals(
            "https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8/cabinet",
            GhostlyAccount.endpoint("https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8#Ghostly%20VPN"),
        )
        assertNull(GhostlyAccount.endpoint("https://example.com/api/sub?token=1"))
    }

    @Test
    fun withoutAnAnswerThePersonStillLandsOnTheRightSection() {
        val url = "https://ghostlynex.fun/sub/sub_abc123#Ghostly"
        assertEquals("https://ghostlynex.fun/#cabinet", GhostlyAccount.fallback(url, "cabinet"))
        assertEquals("https://ghostlynex.fun/#renew=sub_abc123", GhostlyAccount.fallback(url, "renew"))
        assertEquals("https://ghostlynex.fun/#notices", GhostlyAccount.fallback(url, "notices"))
        assertEquals("https://ghostlynex.fun/#cabinet", GhostlyAccount.fallback(url, "anything"))
    }

    @Test
    fun theServersAnswerIsReadWithThePairingOffer() {
        // What the server sends a device it cannot sign in by itself (the owner's second device, or a
        // subscription nobody is recorded as the owner of): a page where one ordinary sign-in settles it.
        val link = JsonX.decodeFromString(
            GhostlyAccount.Link.serializer(),
            """{"ok":true,"login":false,"pair":true,"reason":"not_first_device","url":"https://ghostlynex.fun/?pair=abc#cabinet"}""",
        )
        assertEquals(true, link.pair)
        assertEquals("Войдите на сайте и подтвердите это устройство — дальше кабинет будет открываться сам", GhostlyAccount.hint(link))
        // A server that knows nothing about pairing (or a device not on the subscription at all).
        assertEquals(
            "Вход без пароля работает на устройстве, где подписку добавили первой. Здесь войдите на сайте сами",
            GhostlyAccount.hint(GhostlyAccount.Link(ok = true, reason = "not_first_device", url = "https://ghostlynex.fun/#cabinet")),
        )
        // Signed in: nothing to say.
        assertNull(GhostlyAccount.hint(GhostlyAccount.Link(ok = true, login = true, url = "https://ghostlynex.fun/?app=x#cabinet")))
        // Two-step verification wins over the pairing offer.
        assertEquals("У аккаунта двухэтапная защита — войдите на сайте с кодом", GhostlyAccount.hint(GhostlyAccount.Link(ok = true, reason = "totp", pair = true)))
        assertEquals("Сервер не ответил — открываю сайт, войдите там в аккаунт", GhostlyAccount.hint(null))
    }
}
