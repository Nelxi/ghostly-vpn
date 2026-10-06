package app.ghostly.vpn.trial

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.ghostly.core.trial.TrialClaimRequest
import app.ghostly.core.trial.TrialPolicy
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * Android side of trial Key Attestation.
 *
 * No secrets, no integrity checks, no root detection here: this code just
 * mints a throwaway EC key with the server challenge and returns the
 * certificate chain. A patched APK can lie about everything in this process —
 * that is why the server re-checks package/cert/challenge/securityLevel
 * and why the server is the only thing that decides.
 */
object AndroidTrialAttestation {

    /** Alias prefix for one-shot trial keys (deleted right after the claim). */
    private const val ALIAS_PREFIX = "ghostly-trial-"

    data class Attested(val chainPem: List<String>, val alias: String)

    /**
     * Generates a Keystore key bound to [challengeB64] and returns its chain as PEM.
     * Throws on devices without Keystore attestation (then the caller must
     * surface the neutral "trial unavailable" text).
     */
    fun attest(context: Context, challengeId: String, challengeB64: String): Attested {
        if (Build.VERSION.SDK_INT < 28) throw TrialUnavailable()
        val challenge = Base64.decode(challengeB64, Base64.DEFAULT)
        val alias = ALIAS_PREFIX + challengeId.take(16).filter { it.isLetterOrDigit() }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithm(KeyProperties.KEY_ALGORITHM_EC)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setUserAuthenticationRequired(false)
            .build()
        // On some firmwares a stale alias survives: drop it so re-claims don't fail locally.
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
        kpg.initialize(spec)
        kpg.generateKeyPair()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val chain = ks.getCertificateChain(alias) ?: throw TrialUnavailable()
        val pem = chain.map { cert ->
            val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            "-----BEGIN CERTIFICATE-----\n" + b64.chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----"
        }
        return Attested(pem, alias)
    }

    /** Best-effort cleanup of the one-shot key; the key is useless after the claim anyway. */
    fun wipe(alias: String) {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(alias)
        }
    }

    fun buildClaim(
        challengeId: String,
        attested: Attested,
        appVersion: String,
        hwidHint: String?,
    ): TrialClaimRequest = TrialClaimRequest(
        challengeId = challengeId,
        chainPem = attested.chainPem,
        packageName = TrialPolicy.PACKAGE,
        appVersion = appVersion,
        hwidHint = hwidHint,
    )

    class TrialUnavailable : Exception("trial unavailable for this device")
}
