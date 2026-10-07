package app.ghostly.core

import app.ghostly.core.account.GhostlyAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GhostlyAccountTest {

    @Test
    fun theRequestGoesNextToTheSubscription() {
        assertEquals(
            "https://ghostlinknex.online/sub/sub_ec3df87ca5d81fc8/cabinet",
            GhostlyAccount.endpoint("https://ghostlinknex.online/sub/sub_ec3df87ca5d81fc8#Ghostly%20VPN"),
        )
        assertNull(GhostlyAccount.endpoint("https://example.com/api/sub?token=1"))
    }

    @Test
    fun withoutAnAnswerThePersonStillLandsOnTheRightSection() {
        val url = "https://srv.ghostlinknex.online/sub/sub_abc123#Ghostly"
        assertEquals("https://ghostlinknex.online/#cabinet", GhostlyAccount.fallback(url, "cabinet"))
        assertEquals("https://ghostlinknex.online/#renew=sub_abc123", GhostlyAccount.fallback(url, "renew"))
        assertEquals("https://ghostlinknex.online/#notices", GhostlyAccount.fallback(url, "notices"))
        assertEquals("https://ghostlinknex.online/#cabinet", GhostlyAccount.fallback(url, "anything"))
    }
}
