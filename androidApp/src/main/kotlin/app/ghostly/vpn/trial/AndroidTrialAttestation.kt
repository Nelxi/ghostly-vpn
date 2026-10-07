package app.ghostly.vpn.trial

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * The phone's half of the trial check (see tools/trial/ghostlink_attest.py for the server's).
 *
 * Nothing is decided here and there are no secrets: the Keystore makes a throwaway key bound to the
 * server's challenge, and the secure hardware signs a certificate for it that names the package and
 * the certificate the APK is signed with. A re-packed APK gets its own signer written there by the
 * system, and that is what the server refuses.
 */
object AndroidTrialAttestation {

    private const val ALIAS = "ghostly-trial-attest"

    /**
     * Certificate chain (DER, base64, leaf first) of a fresh key bound to [challenge].
     * Throws where the device can't attest keys (very old Android, broken Keystore).
     */
    fun attest(challenge: ByteArray): List<String> {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // One alias, reused: a key left behind by an interrupted run must not pile up or get in the way.
        runCatching { keyStore.deleteEntry(ALIAS) }
        try {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val chain = keyStore.getCertificateChain(ALIAS)?.takeIf { it.size >= 2 }
                ?: throw IllegalStateException("no attestation chain")
            return chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        } finally {
            runCatching { keyStore.deleteEntry(ALIAS) }
        }
    }

    /**
     * Signs of root that a genuine build reports honestly (the server believes them only after the
     * attestation proved the build is genuine; the hardware's own "bootloader unlocked" needs no help).
     */
    fun rootSigns(context: Context): List<String> {
        val signs = mutableListOf<String>()
        val dirs = listOf(
            "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin",
            "/data/local/bin", "/data/local/xbin", "/data/local", "/system/bin/failsafe", "/debug_ramdisk",
        )
        if (dirs.any { runCatching { File(it, "su").exists() }.getOrDefault(false) }) signs += "su"
        if (listOf("/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap").any { runCatching { File(it).exists() }.getOrDefault(false) }) signs += "magisk"
        if (Build.TAGS?.contains("test-keys") == true) signs += "test-keys"
        val managers = listOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "me.weishu.kernelsu", "me.bmax.apatch",
            "eu.chainfire.supersu", "com.koushikdutta.superuser",
        )
        val pm = context.packageManager
        if (managers.any { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }) signs += "root-manager"
        return signs
    }
}
