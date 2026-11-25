package app.ghostly.core.store

import app.ghostly.core.JsonX
import app.ghostly.core.model.Profile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** An OS-owned key protects persisted credentials; no shared application secret. */
interface ProfileProtector {
    val scheme: String
    fun seal(plaintext: ByteArray): ByteArray
    fun open(ciphertext: ByteArray): ByteArray
}

@OptIn(ExperimentalEncodingApi::class)
class ProfileStore(private val files: FileStore, private val protector: ProfileProtector?) {
    private val serializer = ListSerializer(Profile.serializer())

    /** A lost OS key must not prevent re-importing a subscription or destroy the locked file. */
    fun loadOrRecover(): List<Profile>? = try {
        load()
    } catch (error: Exception) {
        val locked = files.readText(FILE) ?: throw error
        if (!locked.trimStart().startsWith("\"$PREFIX")) throw error
        val previous = files.load(RECOVERY, String.serializer())
        if (previous != null && previous != locked) throw error
        files.saveRequired(RECOVERY, String.serializer(), locked)
        emptyList()
    }

    fun load(): List<Profile>? {
        val text = files.readText(FILE) ?: return null
        if (text.trimStart().startsWith('"')) {
            val sealed = JsonX.decodeFromString(String.serializer(), text)
            require(sealed.startsWith(PREFIX)) { "Unknown protected profile format" }
            val parts = sealed.removePrefix(PREFIX).split(':', limit = 2)
            val key = requireNotNull(protector) { "Protected profiles require their original OS key" }
            require(parts.size == 2 && parts[0] == key.scheme) { "Profile protection scheme mismatch" }
            return JsonX.decodeFromString(serializer, key.open(Base64.decode(parts[1])).decodeToString())
        }
        val legacy = runCatching { JsonX.decodeFromString(serializer, text) }.getOrNull() ?: return null
        if (protector != null) save(legacy)
        return legacy
    }

    fun save(profiles: List<Profile>) {
        val key = protector
        if (key == null) {
            files.save(FILE, serializer, profiles)
            return
        }
        val plaintext = JsonX.encodeToString(serializer, profiles).encodeToByteArray()
        try {
            val sealed = PREFIX + key.scheme + ":" + Base64.encode(key.seal(plaintext))
            files.saveRequired(FILE, String.serializer(), sealed)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        private const val FILE = "profiles.json"
        private const val RECOVERY = "profiles.recovery.json"
        private const val PREFIX = "ghostly-profiles-v1:"
    }
}
