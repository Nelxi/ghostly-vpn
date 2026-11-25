package app.ghostly.core

import app.ghostly.core.model.Profile
import app.ghostly.core.store.FileStore
import app.ghostly.core.store.ProfileProtector
import app.ghostly.core.store.ProfileStore
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ProfileStoreTest {
    private fun protector(): ProfileProtector {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return object : ProfileProtector {
            override val scheme = "test-aesgcm"
            override fun seal(plaintext: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                iv + doFinal(plaintext)
            }
            override fun open(ciphertext: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
                doFinal(ciphertext, 12, ciphertext.size - 12)
            }
        }
    }

    @Test fun legacyProfilesMigrateAndCiphertextCannotBeOpenedByAnotherKey() {
        val directory = Files.createTempDirectory("ghostly-profile-test").toFile()
        try {
            val files = FileStore(directory.path)
            val profiles = listOf(Profile("id", "Test", "https://example.com/sub/private-token"))
            files.save("profiles.json", ListSerializer(Profile.serializer()), profiles)
            val key = protector()
            val store = ProfileStore(files, key)
            assertEquals(profiles, store.load())
            val first = files.readText("profiles.json")!!
            assertFalse(first.contains("private-token"))
            assertEquals(profiles, ProfileStore(files, key).load())
            store.save(profiles)
            assertNotEquals(first, files.readText("profiles.json"))
            assertFails { ProfileStore(files, protector()).load() }
            assertFails { ProfileStore(files, null).load() }
            assertEquals(emptyList(), ProfileStore(files, protector()).loadOrRecover())
            assertFalse(files.readText("profiles.recovery.json")!!.contains("private-token"))
            assertEquals(profiles, ProfileStore(files, key).load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun encryptionFailureNeverWritesPlaintext() {
        val directory = Files.createTempDirectory("ghostly-profile-test").toFile()
        try {
            val files = FileStore(directory.path)
            val broken = object : ProfileProtector {
                override val scheme = "unavailable"
                override fun seal(plaintext: ByteArray): ByteArray = error("Keystore unavailable")
                override fun open(ciphertext: ByteArray): ByteArray = error("Keystore unavailable")
            }
            assertFails { ProfileStore(files, broken).save(listOf(Profile("id", "secret"))) }
            assertEquals(null, files.readText("profiles.json"))
        } finally { directory.deleteRecursively() }
    }
}
