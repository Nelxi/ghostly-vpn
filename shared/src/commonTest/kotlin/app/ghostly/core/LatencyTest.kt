package app.ghostly.core

import app.ghostly.core.model.Ping
import app.ghostly.core.vpn.Latency
import kotlin.test.Test
import kotlin.test.assertEquals

class LatencyTest {
    @Test
    fun settleTakesTheMedianOfSuccessfulSamples() {
        assertEquals(48, Latency.settle(listOf(52, 48, 45)))
        assertEquals(45, Latency.settle(listOf(-1, 45, 300)))
        assertEquals(60, Latency.settle(listOf(60)))
        assertEquals(-1, Latency.settle(listOf(-1, -1)))
        assertEquals(-1, Latency.settle(emptyList()))
    }

    @Test
    fun smallJitterIsBlendedIn() {
        val prev = Ping(50, at = 1_000)
        assertEquals(55, Latency.shown(prev, 60, now = 2_000))
        assertEquals(45, Latency.shown(prev, 40, now = 2_000))
    }

    @Test
    fun realChangesAndFailuresShowAsIs() {
        val prev = Ping(50, at = 1_000)
        assertEquals(140, Latency.shown(prev, 140, now = 2_000))
        assertEquals(20, Latency.shown(prev, 20, now = 2_000))
        assertEquals(-1, Latency.shown(prev, -1, now = 2_000))
    }

    @Test
    fun oldQuickOrFailedPreviousResultsAreNotBlended() {
        assertEquals(70, Latency.shown(null, 70, now = 0))
        assertEquals(70, Latency.shown(Ping(50, at = 0), 70, now = Latency.BLEND_WINDOW_MS + 1))
        assertEquals(70, Latency.shown(Ping(50, at = 0, quick = true), 70, now = 10))
        assertEquals(70, Latency.shown(Ping(-1, at = 0), 70, now = 10))
    }
}
