package app.ghostly.core

import app.ghostly.core.vpn.TunnelApplier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Background work isn't awaited by advanceUntilIdle: move the virtual clock well past every pause and reconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(20_000)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelApplierTest {
    private class Rig(scope: TestScope) {
        var mode = "smart"
        var live = true
        val connects = mutableListOf<String>()
        lateinit var applier: TunnelApplier<String>

        init {
            applier = TunnelApplier(scope.backgroundScope, 350, { live }, { mode }) {
                val m = mode
                applier.connect(m) {
                    delay(2_000) // a reconnect takes a while
                    connects += m
                }
            }
            applier.markApplied(mode)
        }
    }

    @Test
    fun burstOfSwitchesReconnectsOnceOntoTheLastMode() = runTest {
        val r = Rig(this)
        for (m in listOf("global", "smart", "global", "direct-ru", "global")) {
            r.mode = m
            r.applier.request()
            advanceTimeBy(100)
        }
        settle()
        assertEquals(listOf("global"), r.connects)
    }

    @Test
    fun switchingBackBeforeThePauseEndsDoesNothing() = runTest {
        val r = Rig(this)
        r.mode = "global"; r.applier.request(); advanceTimeBy(100)
        r.mode = "smart"; r.applier.request()
        settle()
        assertEquals(emptyList(), r.connects)
    }

    @Test
    fun changeDuringAReconnectIsNotLost() = runTest {
        val r = Rig(this)
        r.mode = "global"; r.applier.request()
        advanceTimeBy(1_000) // the reconnect onto "global" is now running
        r.mode = "direct-ru"; r.applier.request()
        settle()
        assertEquals(listOf("global", "direct-ru"), r.connects)
        assertEquals("direct-ru", r.applier.applied)
    }

    @Test
    fun forcedRequestReconnectsEvenWhenNothingChanged() = runTest {
        val r = Rig(this)
        r.applier.request(force = true)
        settle()
        assertEquals(listOf("smart"), r.connects)
    }

    @Test
    fun noTunnelNoReconnect() = runTest {
        val r = Rig(this)
        r.live = false
        r.mode = "global"; r.applier.request()
        settle()
        assertEquals(emptyList(), r.connects)
    }

    @Test
    fun settingsWaitForTheLastEditWithoutSlowingOrdinaryServerPicks() = runTest {
        val r = Rig(this)
        repeat(4) { i ->
            r.mode = "setting-$i"
            r.applier.request(quietMs = 900)
            advanceTimeBy(500)
        }
        assertEquals(emptyList(), r.connects)
        advanceTimeBy(399)
        runCurrent()
        assertEquals("smart", r.applier.applied)
        settle()
        assertEquals(listOf("setting-3"), r.connects)
    }

    @Test
    fun failedConnectDoesNotMarkUnappliedSettingsAsApplied() = runTest {
        val r = Rig(this)
        try { r.applier.connect("broken") { error("failed") } } catch (_: IllegalStateException) { }
        assertEquals("smart", r.applier.applied)
    }

    @Test
    fun queuedConnectReadsTheCurrentConfigOnlyAfterThePreviousConnectFinishes() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        val first = launch { r.applier.connect("old") { gate.await() } }
        runCurrent()
        var config = "before-edit"
        val second = launch { r.applier.connectSnapshot({ config to config }) { r.connects += it } }
        runCurrent()
        config = "latest-edit"
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("latest-edit"), r.connects)
        assertEquals("latest-edit", r.applier.applied)
    }
}
