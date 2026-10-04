package app.ghostly.core.stage

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTest {

    private val track = NowPlaying(
        title = "song", artist = "artist", durationMs = 0, playing = true,
        lines = listOf(
            LyricLine(1_000, "a"),
            LyricLine(5_000, "b"),
            LyricLine(9_000, "c"),
        ),
        synced = true,
    )

    @Test
    fun lineAtPicksTheLinePlayingNow() {
        assertEquals(-1, track.lineAt(999))
        assertEquals(0, track.lineAt(1_000))
        assertEquals(1, track.lineAt(8_999))
        assertEquals(2, track.lineAt(50_000))
    }

    @Test
    fun lineForNeverGoesBackOnASmallPositionDip() {
        // On line 1 ("b"); the player jitters ~0.4 s back — the text must stay on "b".
        assertEquals(1, track.lineFor(4_600, 1))
        // Still "b" while the clock recovers, and the next line is reached normally.
        assertEquals(1, track.lineFor(8_500, 1))
        assertEquals(2, track.lineFor(9_050, 1))
    }

    @Test
    fun lineForFollowsARealRewind() {
        // Rewound clearly before the current line: that is a seek, follow it.
        assertEquals(0, track.lineFor(2_000, 2))
        assertEquals(1, track.lineFor(5_500, 2))
    }

    @Test
    fun lineForStartsFreshWhenTheCurrentLineIsUnknown() {
        assertEquals(1, track.lineFor(5_000, -1))
        assertEquals(-1, track.lineFor(500, -1))
    }

    @Test
    fun parseLrcReadsTimestampsAndFractionalParts() {
        val lines = Lyrics.parseLrc("[00:01.50]hello\n[00:12]world\n[01:02.345]bye\nno stamp here")
        assertEquals(listOf(1_500L, 12_000L, 62_345L), lines.map { it.timeMs })
        assertEquals(listOf("hello", "world", "bye"), lines.map { it.text })
    }
}
