package app.ghostly.core.stage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lyric clock against the players it has to live with: positions in whole seconds, reported
 * once a poll, a little late, sometimes a little back, with a play flag that flickers.
 */
class LyricClockTest {

    /** A player whose real position is [trueMs]; what it reports is up to the scenario. */
    private class Sim(val frameMs: Long = 16, val pollMs: Long = 1200) {
        val time = LyricTime()
        var now = 1_000_000L
        var trueMs = 0L
        var playing = true
        val seen = mutableListOf<Long>()
        private var sincePoll = Long.MAX_VALUE / 2

        /** One frame; [report] turns the true position into what the player says (null = no report this poll). */
        fun frame(report: (Long) -> Long? = { it / 1000 * 1000 }, flag: () -> Boolean = { playing }) {
            if (sincePoll >= pollMs) {
                sincePoll = 0
                report(trueMs)?.let { time.report("song", it, flag(), now) }
            }
            seen += time.nowMs(now, 0)
            now += frameMs
            sincePoll += frameMs
            if (playing) trueMs += frameMs
        }

        fun run(ms: Long, report: (Long) -> Long? = { it / 1000 * 1000 }, flag: () -> Boolean = { playing }) {
            repeat((ms / frameMs).toInt()) { frame(report, flag) }
        }

        val steps: List<Long> get() = seen.zipWithNext { a, b -> b - a }
    }

    @Test
    fun wholeSecondReportsOnceAPollTypeSmoothlyAndNeverGoBack() {
        val s = Sim().apply { trueMs = 150_000; run(60_000) }
        val steps = s.steps.drop(200) // the first seconds are the clock finding its phase
        assertTrue(steps.all { it >= 0 }, "the clock went back: ${steps.filter { it < 0 }.take(5)}")
        // 16 ms frames at 0.88×…1.12×: no frame stalls, none leaps.
        assertTrue(steps.all { it in 13..19 }, "a frame stalled or leapt: ${steps.filter { it !in 13..19 }.take(5)}")
        // Whole seconds are on average half a second behind the truth; the clock sits inside that band.
        val lag = s.trueMs - s.seen.last()
        assertTrue(lag in -400..1400, "lag $lag ms")
    }

    @Test
    fun aReportThatComesInALittleBehindChangesNothing() {
        val s = Sim().apply { trueMs = 60_000; run(8_000, report = { it }) }
        val before = s.seen.size
        // The player re-publishes its state 300 ms behind where it was: jitter, not a rewind.
        s.run(6_000, report = { it - 300 })
        val steps = s.steps.drop(before - 1)
        assertTrue(steps.all { it >= 0 }, "the clock went back on a late report")
        assertTrue(steps.all { it in 13..19 })
    }

    @Test
    fun theLineOnScreenNeverReturnsToThePreviousOneWhilePlaying() {
        val track = NowPlaying("t", "a", 0, true, lines = (0 until 60).map { LyricLine(150_000L + it * 2_300L, "line $it") }, synced = true)
        val s = Sim().apply { trueMs = 149_000 }
        var jitter = 0
        // Whole seconds, every third report half a second stale, the play flag flickering.
        s.run(120_000, report = { (it - if (jitter++ % 3 == 0) 480 else 0) / 1000 * 1000 }, flag = { jitter % 5 != 0 })
        val shown = s.seen.map { track.lineAt(it) }
        assertTrue(shown.zipWithNext().all { (a, b) -> b >= a }, "a line came back")
        assertTrue(shown.last() > 40)
    }

    @Test
    fun aPauseStopsTheClockAndPlayingOnResumesIt() {
        val s = Sim().apply { trueMs = 30_000; run(10_000, report = { it }) }
        s.playing = false
        // The clock learns about a pause from the next report and the position standing still.
        s.run(3_000, report = { it })
        val paused = s.seen.last()
        s.run(8_000, report = { it })
        assertEquals(paused, s.seen.last(), "the clock ran during a pause")
        // As in Kasane it coasts past the pause point by at most a poll plus the pause grace, and
        // waits there: on resume it eases back instead of jumping.
        assertTrue(paused - s.trueMs in 0..(1_200 + LyricPlaybackClock.PAUSE_GRACE_MS), "coasted ${paused - s.trueMs} ms")
        s.playing = true
        s.run(20_000, report = { it })
        assertTrue(abs(s.seen.last() - s.trueMs) < 600)
        assertTrue(s.steps.all { it >= 0 })
    }

    @Test
    fun aSeekIsFollowedBothWays() {
        val s = Sim().apply { trueMs = 60_000; run(8_000, report = { it }) }
        s.trueMs += 30_000
        s.run(3_000, report = { it })
        assertTrue(abs(s.seen.last() - s.trueMs) < 500, "forward seek not followed")
        s.trueMs -= 45_000
        s.run(3_000, report = { it })
        assertTrue(abs(s.seen.last() - s.trueMs) < 500, "rewind not followed")
    }

    @Test
    fun aNewTrackStartsTheClockAfresh() {
        val time = LyricTime()
        time.report("one", 200_000, true, 1_000)
        assertEquals(200_000, time.nowMs(1_000, 0))
        time.report("two", 0, true, 2_000)
        assertTrue(time.nowMs(2_016, 0) < 100)
    }

    @Test
    fun aRenderFreezeIsNotCaughtUpInOneLeap() {
        val s = Sim().apply { trueMs = 60_000; run(8_000, report = { it }) }
        val before = s.seen.last()
        s.now += 900; s.trueMs += 900 // the UI froze for 0.9 s
        s.frame(report = { it })
        assertTrue(s.seen.last() - before <= LyricPlaybackClock.MAX_FRAME_DELTA_MS * 1.12 + 1, "leapt ${s.seen.last() - before} ms")
    }

    @Test
    fun aLineIsTypedBeforeTheNextOneStarts() {
        for (window in listOf(300L, 800L, 2_000L, 5_000L, 20_000L)) {
            val typing = LyricLayout.typingDurationMs(10_000, 10_000 + window)
            assertTrue(typing < window, "window $window: typing $typing")
            assertTrue(typing <= LyricLayout.TYPING_MAX_MS)
            assertEquals(1f, LyricLayout.typedProgress(10_000 + window, 10_000, 10_000 + window))
        }
        assertEquals(0f, LyricLayout.typedProgress(9_999, 10_000, 12_000))
        // Typing only ever grows with the clock.
        val p = (0..2_000 step 16).map { LyricLayout.typedProgress(10_000L + it, 10_000, 12_000) }
        assertTrue(p.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun aLineIsNotDrawnBeforeItsTimeAndIsGoneSoonAfterTheNextStarts() {
        val start = 10_000L
        val end = 12_000L
        assertFalse(LyricLayout.pose(start - 1, start, end, hasNext = true).visible)
        assertTrue(LyricLayout.pose(start, start, end, hasNext = true).visible)
        // In place and at full brightness once the transition is over.
        val settled = LyricLayout.pose(start + LyricLayout.TRANSITION_MS, start, end, hasNext = true)
        assertEquals(0f, settled.offset)
        assertEquals(1f, settled.alpha)
        // Leaving: fades out over EXIT_MS, never brighter than the frame before.
        val fade = (0..LyricLayout.EXIT_MS step 10).map { LyricLayout.pose(end + it, start, end, hasNext = true).alpha }
        assertTrue(fade.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(fade.last() < 0.01f)
        assertFalse(LyricLayout.pose(end + LyricLayout.EXIT_MS + 1, start, end, hasNext = true).visible)
        // The last line of a song stays.
        assertTrue(LyricLayout.pose(end + 60_000, start, end, hasNext = false).visible)
    }
}
