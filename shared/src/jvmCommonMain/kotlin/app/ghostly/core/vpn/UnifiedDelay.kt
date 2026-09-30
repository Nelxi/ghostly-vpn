package app.ghostly.core.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Latency through a proxy the way mihomo's `unified-delay` does it: one warm-up request opens the
 * tunnel (proxy handshake, TCP to the target, TLS to the target) and is not counted; then
 * [Latency.SAMPLES] requests go over that same connection and their median is the result. The
 * number is the round-trip of the tunnel itself instead of 4-6 round-trips of connection setup,
 * which made the old per-request timing high and jumpy.
 *
 * Falls back to the warm-up time (the old, setup-inclusive number) when the connection can't be
 * reused (`Connection: close`, a body of unknown length).
 */
object UnifiedDelay {
    private const val TIMEOUT_MS = 6000
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = 256 * 1024

    /** Through a loopback SOCKS port of a core, ms; negative on failure. */
    fun viaSocks(port: Int, url: String, method: String = Probe.httpMethod): Long =
        measure(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)), url, method)

    fun measure(proxy: Proxy, url: String, method: String = Probe.httpMethod): Long =
        runCatching { measureOrThrow(proxy, url, method) }.getOrDefault(-1L)

    private fun measureOrThrow(proxy: Proxy, url: String, method: String): Long {
        val uri = URI(url)
        val https = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: return -1
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if (uri.port > 0) "$host:$port" else host
        val request = ("$method $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: Ghostly\r\n" +
            "Accept: */*\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)

        val raw = Socket(proxy)
        return raw.use {
            raw.tcpNoDelay = true
            raw.soTimeout = TIMEOUT_MS
            // Unresolved: the name goes to the proxy (SOCKS5 domain), as a real app's request would.
            raw.connect(if (proxy.type() == Proxy.Type.DIRECT) InetSocketAddress(host, port) else InetSocketAddress.createUnresolved(host, port), TIMEOUT_MS)
            val sock: Socket = if (https) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket)
                    .also { it.startHandshake() }
            } else raw
            val out = sock.getOutputStream()
            val inp = sock.getInputStream().buffered()

            val t0 = System.nanoTime()
            val warm = exchange(out, inp, request, method) ?: return -1
            val warmMs = (System.nanoTime() - t0) / 1_000_000
            if (!warm.reusable) return warmMs.coerceAtLeast(1)

            val samples = ArrayList<Long>(Latency.SAMPLES)
            repeat(Latency.SAMPLES) {
                val t = System.nanoTime()
                val r = runCatching { exchange(out, inp, request, method) }.getOrNull()
                if (r != null) samples += ((System.nanoTime() - t) / 1_000_000).coerceAtLeast(1)
                if (r == null || !r.reusable) return Latency.settle(samples).takeIf { it > 0 } ?: warmMs.coerceAtLeast(1)
            }
            Latency.settle(samples)
        }
    }

    private class Reply(val reusable: Boolean)

    /** One request/response on an open connection; null when the answer isn't a 2xx/3xx. */
    private fun exchange(out: OutputStream, inp: InputStream, request: ByteArray, method: String): Reply? {
        out.write(request)
        out.flush()
        val head = readHead(inp) ?: return null
        val lines = head.split("\r\n")
        val code = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        if (code !in 200..399) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim().lowercase()
        }.toMap()
        val close = headers["connection"] == "close"
        val noBody = method == "HEAD" || code == 204 || code == 304
        val reusable = when {
            noBody -> true
            headers["transfer-encoding"]?.contains("chunked") == true -> skipChunked(inp)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.toLongOrNull() ?: return Reply(false)
                n <= MAX_BODY && skip(inp, n)
            }
            else -> false
        }
        return Reply(reusable && !close)
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var match = 0
        while (buf.size() < MAX_HEAD) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            match = when {
                b == '\r'.code && (match == 0 || match == 2) -> match + 1
                b == '\n'.code && (match == 1 || match == 3) -> match + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (match == 4) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd()
        }
        return null
    }

    private fun skip(inp: InputStream, n: Long): Boolean {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = inp.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }

    private fun skipChunked(inp: InputStream): Boolean {
        var total = 0L
        while (true) {
            val line = readLine(inp) ?: return false
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: return false
            if (size == 0L) {
                // trailers until an empty line
                while (true) {
                    val t = readLine(inp) ?: return false
                    if (t.isEmpty()) return true
                }
            }
            total += size
            if (total > MAX_BODY || !skip(inp, size) || readLine(inp) == null) return false
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 1024) {
            val b = inp.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }
}
