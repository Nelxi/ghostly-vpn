package app.ghostly.core.vpn

/** The phone's ringer switch. */
enum class Ringer { NORMAL, VIBRATE, SILENT }

/**
 * What the phone is doing right now, for deciding whether a vibration or a UI sound may play.
 * Platforms without such a state (desktop) report none, and the user's settings alone decide.
 */
data class FeedbackEnv(
    /** The screen is on (`PowerManager.isInteractive`). */
    val screenOn: Boolean,
    /** The lock screen is up (`KeyguardManager.isKeyguardLocked`). */
    val locked: Boolean,
    /** Our window is on screen (the activity is started). */
    val appShown: Boolean,
    val ringer: Ringer,
    /** «Не беспокоить» is on (any interruption filter other than "all"). */
    val dnd: Boolean,
)

data class Feedback(val vibrate: Boolean, val sound: Boolean) {
    companion object {
        val NONE = Feedback(vibrate = false, sound = false)
    }
}

object FeedbackPolicy {
    /**
     * [fromUser] = the feedback answers a tap in the open app; anything else (connected / failed after a
     * failover, a payment confirmed, a notice arriving) is a background event.
     *
     * - The user's switches ([haptics], [sounds]) always come first.
     * - A background event stays silent unless the screen is on, unlocked and showing the app: no buzzing
     *   in a pocket because the tunnel switched servers.
     * - Silent ringer or «Не беспокоить»: background events make neither sound nor vibration; a tap still
     *   gets its vibration (like the system keyboard) but no sound.
     * - Vibrate ringer: vibration only, no sound.
     */
    fun decide(fromUser: Boolean, haptics: Boolean, sounds: Boolean, env: FeedbackEnv?): Feedback {
        if (env == null) return Feedback(haptics, sounds)
        if (!fromUser && !(env.screenOn && !env.locked && env.appShown)) return Feedback.NONE
        val quiet = env.dnd || env.ringer == Ringer.SILENT
        if (!fromUser && quiet) return Feedback.NONE
        return Feedback(
            vibrate = haptics,
            sound = sounds && !quiet && env.ringer == Ringer.NORMAL,
        )
    }

    /** Background music (the ban scene's ambient): only while the app is seen and the phone may make sounds. */
    fun ambientAllowed(env: FeedbackEnv?): Boolean =
        env == null || (env.screenOn && !env.locked && env.appShown && !env.dnd && env.ringer == Ringer.NORMAL)
}
