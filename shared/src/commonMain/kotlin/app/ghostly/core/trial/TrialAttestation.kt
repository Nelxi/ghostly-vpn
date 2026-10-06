package app.ghostly.core.trial

import kotlinx.serialization.Serializable

/**
 * Trial issuance policy (client side carries no secrets).
 *
 * All real checks live on the server: Key Attestation chain to Google roots,
 * challenge match (one-time, TTL), package name + release cert digest,
 * hardware securityLevel (Software is rejected), Telegram/IP soft limits.
 * Paid subscriptions and link subscriptions never go through this path.
 *
 * The client only transports bytes and shows one neutral message.
 */
object TrialPolicy {
    /** Package the server expects inside attestationApplicationId. */
    const val PACKAGE = "app.ghostly.vpn"

    /**
     * First version that can do Key Attestation.
     * Older builds get the same neutral refusal, no trial.
     */
    const val MIN_VERSION_NAME = "0.3.26"
    const val MIN_VERSION_CODE = 61

    /** Challenge lifetime, seconds (server enforces, client only hints). */
    const val CHALLENGE_TTL_SECONDS = 300

    /** The only text a user ever sees on trial refusal. Real reason goes to server log. */
    const val UNAVAILABLE = "триал недоступен для этого устройства"

    /**
     * True when this build is allowed to even try a trial.
     * Desktop (Windows/macOS/Linux) has no attestation: trial only via Telegram bot,
     * so the desktop client must not expose a trial button.
     */
    fun clientMayTry(os: String, versionName: String, versionCode: Int = 0): Boolean {
        if (os != "Android") return false
        if (versionCode > 0) return versionCode >= MIN_VERSION_CODE
        return compareVersion(versionName, MIN_VERSION_NAME) >= 0
    }

    /** Simple dotted version compare: 0.3.26 > 0.3.9. Non-numeric suffixes are ignored. */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}

/** Challenge sent to the server for OUR trial hosts. Transport only, never a decision. */
@Serializable
data class TrialChallenge(
    val id: String,
    /** base64 of 32 random bytes the key must embed via setAttestationChallenge(). */
    val challenge: String,
    val expiresIn: Int = TrialPolicy.CHALLENGE_TTL_SECONDS,
)

/**
 * Carries the active trial challenge id from the platform to the trial
 * issuance path, so the server can bind the subscription fetch to the
 * attestation claim. Transport only — the server checks
 * package/signer/challenge/level and is the only thing that decides.
 * Missing/forged key = no trial.
 */
interface TrialKeys {
    /** Active challenge id for OUR trial [url], or null (desktop/old builds). */
    fun forTrialHost(url: String): String?
}

/** Claim sent to the server. No secrets, no HWID-based auth: hwid is a log hint only. */
@Serializable
data class TrialClaimRequest(
    val challengeId: String,
    /** PEM certificates, leaf first, as produced by AndroidKeyStore. */
    val chainPem: List<String>,
    val packageName: String = TrialPolicy.PACKAGE,
    val appVersion: String,
    /** Weak auxiliary signal for logs only. Never a decision factor. */
    val hwidHint: String? = null,
)

/** Server reply. On refusal [error] is always the neutral code, never the real reason. */
@Serializable
data class TrialClaimResponse(
    val ok: Boolean,
    /** Subscription link to import on success. */
    val subscriptionUrl: String? = null,
    /** Always "trial_unavailable" on refusal. */
    val error: String? = null,
)

class TrialUnavailableException : Exception(TrialPolicy.UNAVAILABLE)
