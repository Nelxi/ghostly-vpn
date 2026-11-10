package app.ghostly.core.update

import app.ghostly.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Fetches a release file into [target] and returns its path only when its SHA-256 equals [sha256].
 *
 * - A file that is already there and intact is returned as it is: an install that did not go through
 *   must not cost another 70–180 MB (Android used to fetch the whole APK again on every attempt).
 * - A download that broke off continues from where it stopped. The unfinished part lives next to
 *   [target] under a name bound to [sha256], so a part of another release is never continued.
 * - Mirrors are tried in order; whatever they serve is checked against the same hash, and a part that
 *   does not add up to it is thrown away.
 */
suspend fun downloadVerifiedTo(
    target: File, urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit,
): String = withContext(Dispatchers.IO) {
    val want = sha256.trim().lowercase()
    target.parentFile?.mkdirs()
    if (target.isFile && (size <= 0 || target.length() == size) && sha256Of(target) == want) {
        onProgress(1f)
        return@withContext target.path
    }
    val part = File(target.path + "." + want.take(16) + ".part")
    // Parts of other releases (and the fixed "<file>.part" of older builds) are of no use any more.
    target.parentFile?.listFiles()?.forEach {
        if (it != part && it.name.startsWith(target.name + ".") && it.name.endsWith(".part")) it.delete()
    }
    var last: Exception? = null
    for (url in urls) {
        try {
            fetchInto(part, url, want, size, onProgress)
            target.delete()
            if (!part.renameTo(target)) throw IOException(tr("Не удалось сохранить файл обновления"))
            onProgress(1f)
            return@withContext target.path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Next mirror: it continues the same part, and the result is verified against the same hash.
            last = e
        }
    }
    throw last ?: IOException(tr("Не удалось скачать обновление"))
}

/**
 * Brings [part] up to the whole file from [url] (continuing what is already there) and checks the hash.
 * Returns normally only for a complete, matching file. A broken connection keeps the part for the next
 * try; anything that shows the part itself is wrong deletes it, so a bad part can never block updates.
 */
private fun fetchInto(part: File, url: String, want: String, size: Long, onProgress: (Float) -> Unit) {
    var have = if (part.isFile) part.length() else 0L
    if (size > 0 && have >= size) {
        // Everything arrived last time and only the rename is missing — or it is not our file at all.
        if (have == size && sha256Of(part) == want) return
        part.delete()
        have = 0L
    }
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "GhostlyVPN-updater")
        // Offsets below are offsets in the file itself, never in a compressed stream.
        c.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        val continued = have > 0 && code == HttpURLConnection.HTTP_PARTIAL
        if (continued && !c.getHeaderField("Content-Range").orEmpty().trim().startsWith("bytes $have-")) {
            part.delete()
            throw IOException("HTTP 206: unexpected Content-Range")
        }
        if (code == 416) part.delete()
        if (code !in 200..299 || (code == HttpURLConnection.HTTP_PARTIAL && !continued)) throw IOException("HTTP $code")
        // A server that ignores Range answers 200 with the whole file: start over.
        if (!continued) have = 0L
        val md = MessageDigest.getInstance("SHA-256")
        if (continued) digestFile(md, part)
        val body = c.contentLengthLong
        val total = if (size > 0) size else if (body > 0) have + body else 0L
        var done = have
        var lastReport = if (total > 0) (done.toFloat() / total).coerceIn(0f, 0.99f) else 0f
        if (lastReport > 0f) onProgress(lastReport)
        c.inputStream.use { input ->
            FileOutputStream(part, continued).use { out ->
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
        // A connection closed early does not always raise an error: count the bytes ourselves.
        if (total > 0 && done < total) throw IOException(tr("Не удалось скачать обновление"))
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) {
            part.delete()
            throw IOException(tr("Контрольная сумма не совпала — файл повреждён или подменён, установка отменена"))
        }
    } finally {
        c.disconnect()
    }
}

private fun digestFile(md: MessageDigest, file: File) {
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
}

private fun sha256Of(file: File): String = runCatching {
    val md = MessageDigest.getInstance("SHA-256")
    digestFile(md, file)
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrDefault("")
