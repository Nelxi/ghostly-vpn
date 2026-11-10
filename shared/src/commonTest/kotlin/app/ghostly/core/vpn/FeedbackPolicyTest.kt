package app.ghostly.core.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackPolicyTest {
    private val open = FeedbackEnv(screenOn = true, locked = false, appShown = true, ringer = Ringer.NORMAL, dnd = false)
    private val all = Feedback(vibrate = true, sound = true)

    private fun bg(env: FeedbackEnv?) = FeedbackPolicy.decide(fromUser = false, haptics = true, sounds = true, env = env)
    private fun tap(env: FeedbackEnv?) = FeedbackPolicy.decide(fromUser = true, haptics = true, sounds = true, env = env)

    @Test
    fun desktopFollowsSettingsOnly() {
        assertEquals(all, bg(null))
        assertEquals(Feedback(vibrate = false, sound = true), FeedbackPolicy.decide(false, haptics = false, sounds = true, env = null))
        assertEquals(Feedback(vibrate = true, sound = false), FeedbackPolicy.decide(true, haptics = true, sounds = false, env = null))
    }

    @Test
    fun openAppPlaysEverything() {
        assertEquals(all, tap(open))
        assertEquals(all, bg(open))
    }

    @Test
    fun settingsOffStayOff() {
        assertEquals(Feedback.NONE, FeedbackPolicy.decide(true, haptics = false, sounds = false, env = open))
        assertEquals(Feedback(vibrate = false, sound = true), FeedbackPolicy.decide(true, haptics = false, sounds = true, env = open))
    }

    @Test
    fun backgroundEventIsSilentInAPocket() {
        assertEquals(Feedback.NONE, bg(open.copy(screenOn = false)))
        assertEquals(Feedback.NONE, bg(open.copy(locked = true)))
        assertEquals(Feedback.NONE, bg(open.copy(appShown = false)))
        // A failover while the user is in another app: nothing either.
        assertEquals(Feedback.NONE, bg(open.copy(appShown = false, ringer = Ringer.VIBRATE)))
    }

    @Test
    fun silentRingerAndDoNotDisturb() {
        assertEquals(Feedback.NONE, bg(open.copy(ringer = Ringer.SILENT)))
        assertEquals(Feedback.NONE, bg(open.copy(dnd = true)))
        // A tap keeps its vibration but makes no sound.
        assertEquals(Feedback(vibrate = true, sound = false), tap(open.copy(ringer = Ringer.SILENT)))
        assertEquals(Feedback(vibrate = true, sound = false), tap(open.copy(dnd = true)))
    }

    @Test
    fun vibrateRingerMeansVibrationOnly() {
        assertEquals(Feedback(vibrate = true, sound = false), bg(open.copy(ringer = Ringer.VIBRATE)))
        assertEquals(Feedback(vibrate = true, sound = false), tap(open.copy(ringer = Ringer.VIBRATE)))
    }

    @Test
    fun ambientOnlyWhenSeenAndAllowedToSound() {
        assertTrue(FeedbackPolicy.ambientAllowed(null))
        assertTrue(FeedbackPolicy.ambientAllowed(open))
        assertFalse(FeedbackPolicy.ambientAllowed(open.copy(appShown = false)))
        assertFalse(FeedbackPolicy.ambientAllowed(open.copy(screenOn = false)))
        assertFalse(FeedbackPolicy.ambientAllowed(open.copy(locked = true)))
        assertFalse(FeedbackPolicy.ambientAllowed(open.copy(ringer = Ringer.VIBRATE)))
        assertFalse(FeedbackPolicy.ambientAllowed(open.copy(dnd = true)))
    }
}
