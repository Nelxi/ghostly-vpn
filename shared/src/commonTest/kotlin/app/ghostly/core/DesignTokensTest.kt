package app.ghostly.core

import app.ghostly.core.design.DesignTokens
import kotlin.test.Test
import kotlin.test.assertEquals

class DesignTokensTest {
    @Test
    fun oldDesignJsonGetsSoftShadowsAndNoStageDim() {
        val t = JsonX.decodeFromString(DesignTokens.serializer(), """{"aurora": 1.0, "parallax": 1.0, "halo": 1.0}""")
        assertEquals(0f, t.stageDim)
        assertEquals(DesignTokens().shadow, t.shadow)
        assertEquals(DesignTokens().shadowSoft, t.shadowSoft)
    }

    @Test
    fun serverCanTurnTheOldLookBack() {
        val t = JsonX.decodeFromString(DesignTokens.serializer(), """{"stageDim": 1, "shadow": 1, "shadowSoft": 1}""")
        assertEquals(1f, t.stageDim); assertEquals(1f, t.shadow); assertEquals(1f, t.shadowSoft)
    }

    @Test
    fun theOutfitComesBackOnANewTrackUnlessTheServerTurnsItOff() {
        // A design.json written before the knob existed keeps the effect on.
        val old = JsonX.decodeFromString(DesignTokens.serializer(), """{"aurora": 1.0}""")
        assertEquals(320, old.outfitReplayMs)
        val off = JsonX.decodeFromString(DesignTokens.serializer(), """{"outfitReplayMs": 0}""")
        assertEquals(0, off.outfitReplayMs)
    }
}
