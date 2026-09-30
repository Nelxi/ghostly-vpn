package app.ghostly.core.vpn

import app.ghostly.core.model.Ping

/**
 * How a latency number is settled from raw samples and how it moves on screen. Shared by every
 * backend, so Xray shows the same kind of number as mihomo with `unified-delay`.
 */
object Latency {
    /** Samples taken on the already open connection after the warm-up request. */
    const val SAMPLES = 3

    /** A previous result older than this is not blended with a new one. */
    const val BLEND_WINDOW_MS = 10 * 60_000L

    /** Median of the successful samples (the middle one, or the lower middle for an even count); -1 if none. */
    fun settle(samples: List<Long>): Long {
        val ok = samples.filter { it > 0 }.sorted()
        if (ok.isEmpty()) return -1
        return ok[(ok.size - 1) / 2]
    }

    /**
     * The number to show for a fresh result. Close to the previous one (within 2x) it is averaged in,
     * so ±10 ms of jitter doesn't make the list jump; a real change or a failure is shown as is.
     */
    fun shown(previous: Ping?, fresh: Long, now: Long): Long {
        if (fresh <= 0 || previous == null || !previous.ok || previous.quick) return fresh
        if (now - previous.at > BLEND_WINDOW_MS) return fresh
        if (fresh > previous.ms * 2 || fresh * 2 < previous.ms) return fresh
        return (previous.ms + fresh + 1) / 2
    }
}
