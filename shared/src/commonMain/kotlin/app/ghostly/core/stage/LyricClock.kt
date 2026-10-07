package app.ghostly.core.stage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The time the sung lines are drawn by — the Kasane media bar's lyric system, kept as it is there
 * (LyricPlaybackClock + MediaPlayer.getPositionMs), shared by every platform.
 *
 * Two parts, as in Kasane:
 *  - [PlayerPosition]: what the player says, made usable — carried forward between its reports and
 *    never stepping back unless the track really was rewound;
 *  - [LyricPlaybackClock]: the clock itself. It runs on frame time at the track's own pace and uses
 *    that position only as a phase reference, so the text neither freezes nor jumps when the player
 *    reports late, in whole seconds, or a little off.
 *
 * Everything on screen (which line, how many letters, where it sits) is a plain function of this
 * one clock, read once per frame: there is no "current line" kept anywhere that could fall behind
 * or come back.
 */
class LyricPlaybackClock {
    private var trackKey = ""
    private var ready = false
    private var frameAtMs = 0L
    private var referenceMs = Long.MIN_VALUE
    private var referenceMovedAtMs = 0L

    /** Current typing time, ms from the start of the track. */
    var clockMs = 0L
        private set

    /** Current pace of the clock: 1.0 = exactly with the track. */
    var rate = 1.0
        private set

    /**
     * @param nowMs frame time (wall clock, ms);
     * @param trackKey a new key = a new clock;
     * @param referenceMs the player's position (may lag or come in whole seconds);
     * @param referenceAdvancing does the player say the position is really moving.
     * @return the typing time for this frame.
     */
    fun update(nowMs: Long, trackKey: String, referenceMs: Long, referenceAdvancing: Boolean): Long {
        if (!ready || trackKey != this.trackKey) {
            this.trackKey = trackKey
            ready = true
            clockMs = referenceMs
            frameAtMs = nowMs
            this.referenceMs = referenceMs
            referenceMovedAtMs = nowMs
            rate = 1.0
            return clockMs
        }

        val frameDeltaMs = (nowMs - frameAtMs).coerceIn(0L, MAX_FRAME_DELTA_MS)
        frameAtMs = nowMs

        if (referenceMs > this.referenceMs) {
            this.referenceMs = referenceMs
            referenceMovedAtMs = nowMs
        } else if (referenceMs < this.referenceMs - RESYNC_MS) {
            // A real rewind: follow the reference down, or "is it moving" would compare with the old peak.
            this.referenceMs = referenceMs
            referenceMovedAtMs = nowMs
        }

        // "The track is going" is told not only by the player's flag (it lies and lags) but by the
        // position itself moving — and not by a single frame: it has short stops.
        val running = referenceAdvancing || nowMs - referenceMovedAtMs < PAUSE_GRACE_MS
        if (!running) {
            rate = 1.0
            if (abs(referenceMs - clockMs) >= RESYNC_MS) clockMs = referenceMs
            return clockMs
        }

        val errorMs = referenceMs - clockMs
        if (abs(errorMs) >= RESYNC_MS) {
            // A seek or a new track: the only place the clock is moved, a jump is expected here.
            clockMs = referenceMs
            rate = 1.0
            return clockMs
        }

        rate = when {
            errorMs > PHASE_DEADBAND_MS -> 1.0 + MAX_RATE_STEP
            errorMs < -PHASE_DEADBAND_MS -> 1.0 - MAX_RATE_STEP
            else -> 1.0
        }
        clockMs += (frameDeltaMs * rate).roundToLong()
        return clockMs
    }

    companion object {
        /** Most the clock gains in one frame: no leap after a render freeze, ms. */
        const val MAX_FRAME_DELTA_MS = 200L
        /** A gap to the player this big is a seek or a new track, ms. */
        const val RESYNC_MS = 2500L
        /** Phase dead band: under it typing goes exactly at the track's pace, ms. */
        const val PHASE_DEADBAND_MS = 350L
        /** How much the pace may change while the clock pulls its phase in. */
        const val MAX_RATE_STEP = 0.12
        /** How long the position may stand still before it counts as a pause, ms. */
        const val PAUSE_GRACE_MS = 1300L
    }
}

/**
 * The player's position between and across its reports (Kasane's MediaPlayer.getPositionMs with its
 * "never backwards" cursor): carried forward by the wall clock while the track is going, and held
 * monotonic — a report that comes in a little behind the previous one (rounding, a re-published
 * state) changes nothing; only a new track or a real rewind moves it back.
 */
class PlayerPosition {
    private var trackKey = ""
    private var baseMs = 0L
    private var baseAtMs = 0L
    private var lastReportedMs = 0L
    private var stableMs = 0L
    private var hasReport = false

    /** Is the position really moving (the player says so, or its reports show it). */
    var advancing = false
        private set

    /**
     * A report from the player: [reportedMs] was the position at [atMs] (wall clock).
     * [advancing]: the track is going — see how each platform decides that.
     */
    fun report(trackKey: String, reportedMs: Long, advancing: Boolean, atMs: Long) {
        val changed = !hasReport || trackKey != this.trackKey
        // Against the previous report, not against where the track "should" be by now: players that
        // report whole seconds are always a little behind that, and it is not a rewind.
        val rewound = !changed && reportedMs + REWIND_DETECTION_MS < lastReportedMs
        if (changed || rewound) {
            this.trackKey = trackKey
            stableMs = reportedMs
        }
        hasReport = true
        baseMs = reportedMs
        baseAtMs = atMs
        lastReportedMs = reportedMs
        this.advancing = advancing
    }

    /** The position at [nowMs], within [durationMs] when it is known (> 0). */
    fun positionMs(nowMs: Long, durationMs: Long): Long {
        if (!hasReport) return 0L
        // Between reports the position is carried by the clock: without it typing would go in steps
        // of the report interval.
        var resolved = if (advancing && baseMs > 0L) baseMs + max(0L, nowMs - baseAtMs) else baseMs
        if (durationMs > 0L) resolved = min(resolved, durationMs)
        stableMs = max(stableMs, resolved)
        if (durationMs > 0L) stableMs = min(stableMs, durationMs)
        return stableMs
    }

    companion object {
        /** A report this far behind where the track should be is a rewind, not jitter, ms. */
        const val REWIND_DETECTION_MS = 500L
    }
}

/** [PlayerPosition] + [LyricPlaybackClock] wired the way the Kasane media bar wires them. */
class LyricTime {
    private val position = PlayerPosition()
    private val clock = LyricPlaybackClock()
    private var trackKey = ""

    fun report(trackKey: String, reportedMs: Long, advancing: Boolean, atMs: Long) {
        this.trackKey = trackKey
        position.report(trackKey, reportedMs, advancing, atMs)
    }

    /** The typing time at [nowMs]; safe to call several times a frame (the clock adds up frame deltas). */
    fun nowMs(nowMs: Long, durationMs: Long): Long {
        val t = clock.update(nowMs, trackKey, position.positionMs(nowMs, durationMs), position.advancing)
        return if (durationMs > 0L) t.coerceIn(0L, durationMs) else max(0L, t)
    }
}

/** How a line is laid out in time — the Kasane media bar's numbers. */
object LyricLayout {
    /** A line comes in over this long, ms. */
    const val TRANSITION_MS = 330L
    /** The previous line leaves faster, so it does not sit on top of the new one being typed, ms. */
    const val EXIT_MS = 170L
    const val VISIBLE_RANGE = 1.28f
    /** Typing room for a line with no next one (or broken timestamps), ms. */
    const val TYPING_FALLBACK_MS = 4000L
    /** Most a line takes to type: a long line must not crawl letter by letter for seconds, ms. */
    const val TYPING_MAX_MS = 3200L
    /** Least, so a short line does not land in one burst, ms. */
    const val TYPING_MIN_MS = 600L
    /** Typing ends this long before the next line, ms. */
    const val TYPING_TAIL_MS = 420L

    /** End of a line's window: the next line's timestamp, the track's end for the last one. */
    fun lineEndMs(lines: List<LyricLine>, index: Int, durationMs: Long): Long {
        val start = lines[index].timeMs
        val end = if (index + 1 < lines.size) lines[index + 1].timeMs else durationMs
        return if (end > start) end else start + TYPING_FALLBACK_MS
    }

    /**
     * Time to type a line: its window minus the tail during which the text stands complete, capped
     * at [TYPING_MAX_MS]. The tail is proportionally shorter for short windows, so a line is always
     * finished before the next one starts.
     */
    fun typingDurationMs(startMs: Long, endMs: Long): Long {
        val window = endMs - startMs
        if (window <= 0L) return TYPING_MIN_MS
        val tail = min(TYPING_TAIL_MS, window / 4L)
        val limit = max(1L, window - tail)
        val duration = min(limit, TYPING_MAX_MS)
        return max(duration, min(TYPING_MIN_MS, limit))
    }

    /** 0..1: how much of the line is typed at [clockMs]. */
    fun typedProgress(clockMs: Long, startMs: Long, endMs: Long): Float =
        ((clockMs - startMs) / typingDurationMs(startMs, endMs).toFloat()).coerceIn(0f, 1f)

    fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Where a line is and how bright, at [clockMs]. [offset] is in line spacings from the centre. */
    data class Pose(val visible: Boolean, val offset: Float, val alpha: Float)

    fun pose(clockMs: Long, startMs: Long, endMs: Long, hasNext: Boolean): Pose {
        if (clockMs < startMs || (hasNext && clockMs > endMs + EXIT_MS)) return Pose(false, 0f, 0f)
        // Coming in takes the whole transition; leaving is shorter and fades fast: the previous line
        // must be gone while the new one only starts typing.
        val enter = smoothStep((clockMs - startMs) / TRANSITION_MS.toFloat())
        val exit = if (hasNext) smoothStep((clockMs - endMs) / EXIT_MS.toFloat()) else 0f
        val exitFade = (1f - exit) * (1f - exit)
        val offset = 0.5f * (1f - enter) - 0.5f * exit
        val focus = (1f - abs(offset) / VISIBLE_RANGE).coerceIn(0f, 1f)
        val eased = focus * focus * (3f - 2f * focus)
        return Pose(true, offset, (0.18f + 0.82f * eased) * exitFade)
    }
}
