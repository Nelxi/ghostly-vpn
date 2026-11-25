package app.ghostly.core.store

import app.ghostly.core.JsonX
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer

/** Tiny JSON-file persistence in the app's private data directory. Writes are atomic (tmp + move). */
class FileStore(private val dir: String) {

    init {
        runCatching { SystemFileSystem.createDirectories(Path(dir)) }
    }

    fun <T> load(name: String, serializer: KSerializer<T>): T? = runCatching {
        val path = Path(dir, name)
        if (!SystemFileSystem.exists(path)) return null
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        JsonX.decodeFromString(serializer, text)
    }.getOrNull()

    fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        runCatching { saveRequired(name, serializer, value) }
    }

    /** Credentials must not silently retain an unprotected legacy file after a failed migration. */
    fun <T> saveRequired(name: String, serializer: KSerializer<T>, value: T) {
        val tmp = Path(dir, "$name.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(JsonX.encodeToString(serializer, value)) }
        SystemFileSystem.atomicMove(tmp, Path(dir, name))
    }

    fun readText(name: String): String? = runCatching {
        val path = Path(dir, name)
        if (!SystemFileSystem.exists(path)) null
        else SystemFileSystem.source(path).buffered().use { it.readString() }
    }.getOrNull()

    fun writeText(name: String, text: String) {
        runCatching { SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString(text) } }
    }

    fun path(name: String): String = Path(dir, name).toString()
}
