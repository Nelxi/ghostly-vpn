package app.ghostly.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Streams the first reachable URL into [target], hashing on the fly; keeps it only if SHA-256 matches. */
internal suspend fun downloadVerifiedTo(
    target: File, urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit,
): String = withContext(Dispatchers.IO) {
    target.parentFile?.mkdirs()
    // Already downloaded and intact (an install that didn't go through): don't fetch 120 MB again.
    if (target.isFile && (size <= 0 || target.length() == size) && sha256Of(target).equals(sha256.trim(), ignoreCase = true)) {
        onProgress(1f)
        return@withContext target.path
    }
    var last: Exception? = null
    for (url in urls) {
        val tmp = File(target.path + ".part")
        try {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "GhostlyVPN-updater")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0f
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 0.99f)
                            if (p - lastReport >= 0.01f) { lastReport = p; onProgress(p) }
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256.trim(), ignoreCase = true)) {
                tmp.delete()
                throw IOException("Контрольная сумма не совпала — файл повреждён или подменён, установка отменена")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл обновления")
            onProgress(1f)
            return@withContext target.path
        } catch (e: Exception) {
            tmp.delete()
            // Next mirror: whatever it serves is verified against the same hash.
            last = e
        }
    }
    throw last ?: IOException("Не удалось скачать обновление")
}

private fun sha256Of(file: File): String = runCatching {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrDefault("")
