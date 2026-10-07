package app.ghostly.core

import app.ghostly.core.sub.Attest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttestTest {

    @Test
    fun parsesTheServersRequest() {
        assertEquals(Attest.Ask("abc_-1", "AAEC"), Attest.parse("v1;abc_-1;AAEC"))
        assertEquals(Attest.Ask("id", "bm9uY2U="), Attest.parse(" v1;id;bm9uY2U= "))
    }

    @Test
    fun ignoresWhatItDoesNotUnderstand() {
        assertNull(Attest.parse(null))
        assertNull(Attest.parse(""))
        assertNull(Attest.parse("v2;id;AAEC"))
        assertNull(Attest.parse("v1;id"))
        assertNull(Attest.parse("v1;;AAEC"))
    }

    @Test
    fun theAnswerGoesNextToTheSubscription() {
        assertEquals(
            "https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8/attest",
            Attest.endpoint("https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8#Ghostly%20VPN"),
        )
        assertEquals("https://ghostlynex.fun/sub/sub_1/attest", Attest.endpoint("https://ghostlynex.fun/sub/sub_1?fmt=xray"))
        assertEquals("https://ghostlynex.fun/sub/sub_1/attest", Attest.endpoint(" https://ghostlynex.fun/sub/sub_1/ "))
    }

    @Test
    fun theFetchAfterAnAnswerIsADifferentRequestForCaches() {
        val ask = Attest.Ask("c1", "AAEC")
        assertEquals("https://ghostlynex.fun/sub/sub_1?attested=c1#Ghostly", Attest.again("https://ghostlynex.fun/sub/sub_1#Ghostly", ask))
        assertEquals("https://ghostlynex.fun/sub/sub_1?fmt=xray&attested=c1", Attest.again("https://ghostlynex.fun/sub/sub_1?fmt=xray", ask))
    }

    @Test
    fun linksOfAnotherShapeGetNoAnswer() {
        assertNull(Attest.endpoint("https://example.com/api/v1/client/subscribe?token=abc"))
        assertNull(Attest.endpoint("vless://uuid@host:443"))
        assertNull(Attest.endpoint("https://example.com/sub/"))
    }
}
