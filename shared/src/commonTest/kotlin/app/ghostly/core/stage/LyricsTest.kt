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
    fun parseLrcReadsTimestampsAndFractionalParts() {
        val lines = Lyrics.parseLrc("[00:01.50]hello\n[00:12]world\n[01:02.345]bye\nno stamp here")
        assertEquals(listOf(1_500L, 12_000L, 62_345L), lines.map { it.timeMs })
        assertEquals(listOf("hello", "world", "bye"), lines.map { it.text })
    }
}
