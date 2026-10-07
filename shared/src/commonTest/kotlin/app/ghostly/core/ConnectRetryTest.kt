package app.ghostly.core

import app.ghostly.core.vpn.ConnectRetry
import app.ghostly.core.vpn.CoreErrors
import app.ghostly.core.vpn.VpnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectRetryTest {

    @Test
    fun fiveTriesWithAGrowingPause() {
        assertEquals(5, ConnectRetry.MAX)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), (1..5).map { ConnectRetry.delayMs(it) })
        assertTrue((0 until 5).all { ConnectRetry.shouldRetry(it, retryable = true) })
        assertFalse(ConnectRetry.shouldRetry(5, retryable = true))
    }

    @Test
    fun aFailureThatCannotGoAwayIsNotRetried() {
        assertFalse(ConnectRetry.shouldRetry(0, retryable = false))
    }

    @Test
    fun twoFailuresWithTheSameTextAreDifferentStates() {
        // A StateFlow drops equal values: the second identical error must still reach the retry logic.
        assertNotEquals(VpnState.Failed("boom"), VpnState.Failed("boom"))
    }

    @Test
    fun busyAdapterAndBusyPortAreWorthAnotherStart() {
        val wintun = listOf("2026/10/07 04:20:38.840347 [Error] failed to create tun: Cannot create a file when that file already exists.")
        assertTrue(CoreErrors.transient(wintun))
        assertEquals("Адаптер TUN ещё занят прошлым подключением", CoreErrors.describe(wintun, tun = true))
        val port = listOf("Failed to start: app/proxyman/inbound: failed to listen TCP on 10808 > listen tcp 127.0.0.1:10808: bind: address already in use")
        assertTrue(CoreErrors.transient(port))
        assertEquals("Локальный порт занят другой программой", CoreErrors.describe(port))
    }

    @Test
    fun aBrokenConfigIsNotStartedAgainAndKeepsTheCoreLine() {
        val bad = listOf("2026/10/07 04:20:38 [Warning] something", "Failed to start: main: failed to load config files > infra/conf: unknown transport protocol: foo")
        assertFalse(CoreErrors.transient(bad))
        assertTrue(CoreErrors.describe(bad).startsWith("Сервер прислал настройки, которые ядро не понимает: "))
        assertTrue("unknown transport protocol: foo" in CoreErrors.describe(bad))
    }

    @Test
    fun anUnknownFailureShowsTheLastLineWithoutTheTimestamp() {
        val tail = listOf("2026/10/07 04:20:38.840347 [Warning] core: something odd happened", "")
        assertTrue(CoreErrors.transient(tail))
        assertEquals("core: something odd happened", CoreErrors.describe(tail))
        assertEquals("Ядро не запустилось", CoreErrors.describe(emptyList()))
        assertEquals("Ядро не успело поднять TUN-адаптер", CoreErrors.describe(emptyList(), tun = true))
    }
}
