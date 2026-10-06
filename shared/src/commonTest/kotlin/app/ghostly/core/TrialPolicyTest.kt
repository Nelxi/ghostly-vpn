package app.ghostly.core.trial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrialPolicyTest {

    @Test
    fun minVersionGate() {
        assertTrue(TrialPolicy.clientMayTry("Android", "0.3.26"))
        assertTrue(TrialPolicy.clientMayTry("Android", "0.4.0"))
        assertTrue(TrialPolicy.clientMayTry("Android", "0.3.26", 61))
        // Old builds without attestation get no trial.
        assertFalse(TrialPolicy.clientMayTry("Android", "0.3.25"))
        assertFalse(TrialPolicy.clientMayTry("Android", "0.3.9"))
        assertFalse(TrialPolicy.clientMayTry("Android", "0.3.26", 60))
        // Desktop has no attestation: trial only via the Telegram bot.
        assertFalse(TrialPolicy.clientMayTry("Windows", "0.3.26", 61))
        assertFalse(TrialPolicy.clientMayTry("macOS", "0.3.26", 61))
        assertFalse(TrialPolicy.clientMayTry("Linux", "0.3.26", 61))
    }

    @Test
    fun versionCompare() {
        assertEquals(1, TrialPolicy.compareVersion("0.3.26", "0.3.9"))
        assertEquals(-1, TrialPolicy.compareVersion("0.3.9", "0.3.26"))
        assertEquals(0, TrialPolicy.compareVersion("0.3.26", "0.3.26"))
        assertEquals(1, TrialPolicy.compareVersion("0.4.0", "0.3.26"))
    }
}
