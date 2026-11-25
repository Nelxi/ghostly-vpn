package app.ghostly.vpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ghostly.core.store.ProfileProtector
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidProfileProtection : ProfileProtector {
    override val scheme = "android-keystore-aesgcm"
    private const val ALIAS = "ghostly-profile-storage-v1"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun seal(plaintext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        iv + doFinal(plaintext)
    }

    override fun open(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 28) { "Invalid protected profile" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
}
