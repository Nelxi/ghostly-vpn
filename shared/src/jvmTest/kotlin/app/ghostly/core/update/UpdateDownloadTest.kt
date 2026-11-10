package app.ghostly.core.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The release downloader shared by Android and desktop, against a local web server. */
class UpdateDownloadTest {

    private val body = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val sha = sha256(body)
    private val dir = kotlin.io.path.createTempDirectory("ghostly-update-test").toFile()
    private val target = File(dir, "Ghostly-Android.apk")
    private val part = File(target.path + "." + sha.take(16) + ".part")
    private val site = Site(body)

    @AfterTest
    fun cleanUp() {
        site.server.stop(0)
        dir.deleteRecursively()
    }

    private class Site(val body: ByteArray) {
        /** "path" or "path bytes=N-" per request. */
        val requests = CopyOnWriteArrayList<String>()
        val served = AtomicLong()

        /** Drop the connection after this many bytes of the next answer. */
        @Volatile
        var cutAfter = -1

        @Volatile
        var ranges = true
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val path = ex.requestURI.path
                requests += path + (range?.let { " $it" } ?: "")
                if (path.startsWith("/missing")) {
                    ex.sendResponseHeaders(404, -1)
                    ex.close()
                    return@createContext
                }
                val file = if (path.startsWith("/other")) ByteArray(body.size) { 7 } else body
                val from = if (ranges && range != null) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
                val data = file.copyOfRange(from, file.size)
                if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${file.size - 1}/${file.size}")
                ex.sendResponseHeaders(if (from > 0) 206 else 200, data.size.toLong())
                val cut = cutAfter
                cutAfter = -1
                val n = if (cut in 0 until data.size) cut else data.size
                runCatching {
                    ex.responseBody.write(data, 0, n)
                    ex.responseBody.flush()
                }
                served.addAndGet(n.toLong())
                // Fewer bytes than promised: the server drops the connection.
                ex.close()
            }
            server.start()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fetch(vararg urls: String, progress: (Float) -> Unit = {}): String =
        runBlocking { downloadVerifiedTo(target, urls.toList(), sha, body.size.toLong(), progress) }

    @Test
    fun downloadsAndVerifies() {
        val seen = ArrayList<Float>()
        assertEquals(target.path, fetch(site.base + "/dl/app.apk") { seen += it })
        assertContentEquals(body, target.readBytes())
        assertEquals(1f, seen.last())
        assertEquals(seen.sorted(), seen)
        assertFalse(part.exists())
    }

    @Test
    fun aFileThatIsAlreadyThereIsNotFetchedAgain() {
        fetch(site.base + "/dl/app.apk")
        // What the old Android updater did every two minutes.
        repeat(30) { fetch(site.base + "/dl/app.apk") }
        assertEquals(1, site.requests.size)
        assertEquals(body.size.toLong(), site.served.get())
    }

    @Test
    fun aFileOfAnotherReleaseIsReplaced() {
        target.writeBytes(ByteArray(body.size) { 1 })
        fetch(site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun aBrokenDownloadContinuesFromWhereItStopped() {
        site.cutAfter = 120_000
        assertFailsWith<java.io.IOException> { fetch(site.base + "/dl/app.apk") }
        assertFalse(target.exists())
        val kept = part.length()
        assertTrue(kept in 1..120_000, "kept $kept")
        val seen = ArrayList<Float>()
        fetch(site.base + "/dl/app.apk") { seen += it }
        assertContentEquals(body, target.readBytes())
        assertEquals("/dl/app.apk bytes=$kept-", site.requests[1])
        // The bar starts where the first try ended, not at zero.
        assertTrue(seen.first() >= kept.toFloat() / body.size - 0.02f, "first ${seen.first()}")
        assertEquals(body.size.toLong(), site.served.get())
    }

    @Test
    fun theNextMirrorContinuesTheSamePart() {
        site.cutAfter = 200_000
        fetch(site.base + "/dl/1.1.0/app.apk", site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
        assertEquals(2, site.requests.size)
        assertTrue(site.requests[1].startsWith("/dl/app.apk bytes="), site.requests[1])
        assertEquals(body.size.toLong(), site.served.get())
    }

    @Test
    fun aServerThatIgnoresRangeStartsOver() {
        site.ranges = false
        part.writeBytes(body.copyOfRange(0, 50_000))
        fetch(site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
        assertEquals("/dl/app.apk bytes=50000-", site.requests.single())
    }

    @Test
    fun aPartThatArrivedWholeIsOnlyRenamed() {
        part.writeBytes(body)
        fetch(site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
        assertTrue(site.requests.isEmpty())
    }

    @Test
    fun aWrongFileIsThrownAway() {
        val e = assertFailsWith<java.io.IOException> { fetch(site.base + "/other/app.apk") }
        assertTrue("Контрольная сумма" in e.message.orEmpty(), e.message)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun aPartThatDoesNotAddUpIsDroppedAndTheNextMirrorStartsClean() {
        part.writeBytes(ByteArray(50_000) { 9 })
        assertFailsWith<java.io.IOException> { fetch(site.base + "/dl/app.apk") }
        assertFalse(part.exists())
        part.writeBytes(ByteArray(50_000) { 9 })
        fetch(site.base + "/dl/app.apk", site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
        assertEquals("/dl/app.apk", site.requests.last())
    }

    @Test
    fun aPartLongerThanTheFileIsNotContinued() {
        part.writeBytes(ByteArray(body.size + 10) { 3 })
        fetch(site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
        assertEquals("/dl/app.apk", site.requests.single())
    }

    @Test
    fun aMissingMirrorFallsThroughToTheNext() {
        fetch(site.base + "/missing/app.apk", site.base + "/dl/app.apk")
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun whenEveryMirrorFailsTheLastErrorIsReported() {
        val e = assertFailsWith<java.io.IOException> { fetch(site.base + "/missing/a.apk", site.base + "/missing/b.apk") }
        assertEquals("HTTP 404", e.message)
        assertFalse(target.exists())
    }

    @Test
    fun partsOfOtherReleasesAreRemoved() {
        val old = File(target.path + ".part").apply { writeBytes(ByteArray(10)) }
        val other = File(target.path + ".0123456789abcdef.part").apply { writeBytes(ByteArray(10)) }
        val unrelated = File(dir, "notes.part").apply { writeBytes(ByteArray(10)) }
        fetch(site.base + "/dl/app.apk")
        assertFalse(old.exists())
        assertFalse(other.exists())
        assertTrue(unrelated.exists())
    }
}
