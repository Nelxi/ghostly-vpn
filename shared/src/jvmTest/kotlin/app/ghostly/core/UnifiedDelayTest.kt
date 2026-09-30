package app.ghostly.core

import app.ghostly.core.vpn.UnifiedDelay
import java.io.InputStream
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedDelayTest {
    /**
     * A tiny HTTP/1.1 server: the first answer on every connection is slow (like a tunnel being set
     * up), the next ones are instant. [reply] builds the response for the n-th request of a connection.
     */
    private class Server(private val firstDelayMs: Long, private val reply: (Int) -> String) : AutoCloseable {
        val socket = ServerSocket(0)
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        val url get() = "http://127.0.0.1:${socket.localPort}/generate_204"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val c = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) {
                        c.use {
                            val inp = c.getInputStream()
                            var n = 0
                            while (readRequest(inp)) {
                                requests.incrementAndGet()
                                if (n == 0) Thread.sleep(firstDelayMs)
                                c.getOutputStream().apply { write(reply(n).toByteArray()); flush() }
                                n++
                            }
                        }
                    }
                }
            }
        }

        private fun readRequest(inp: InputStream): Boolean {
            var match = 0
            while (true) {
                val b = inp.read()
                if (b < 0) return false
                match = if ((b == 13 && match % 2 == 0) || (b == 10 && match % 2 == 1)) match + 1 else if (b == 13) 1 else 0
                if (match == 4) return true
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun setupTimeIsNotCountedAndOneConnectionIsReused() {
        Server(firstDelayMs = 300) { "HTTP/1.1 204 No Content\r\n\r\n" }.use { srv ->
            val ms = UnifiedDelay.measure(Proxy.NO_PROXY, srv.url, "GET")
            assertTrue(ms in 1..150, "warm connection should be fast, got $ms ms")
            assertEquals(1, srv.connections.get())
            assertEquals(4, srv.requests.get()) // warm-up + 3 samples
        }
    }

    @Test
    fun bodiesWithLengthOrChunksKeepTheConnection() {
        Server(firstDelayMs = 200) { n ->
            if (n % 2 == 0) "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            else "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
        }.use { srv ->
            val ms = UnifiedDelay.measure(Proxy.NO_PROXY, srv.url, "GET")
            assertTrue(ms in 1..150, "got $ms ms")
            assertEquals(1, srv.connections.get())
        }
    }

    @Test
    fun connectionCloseFallsBackToTheFullRequestTime() {
        Server(firstDelayMs = 200) { "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n" }.use { srv ->
            val ms = UnifiedDelay.measure(Proxy.NO_PROXY, srv.url, "GET")
            assertTrue(ms >= 200, "no reuse possible, the setup-inclusive time is all we have: $ms ms")
        }
    }

    @Test
    fun errorsAndDeadTargetsAreNegative() {
        Server(firstDelayMs = 0) { "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n" }.use { srv ->
            assertEquals(-1, UnifiedDelay.measure(Proxy.NO_PROXY, srv.url, "GET"))
        }
        val dead = ServerSocket(0).use { it.localPort }
        assertEquals(-1, UnifiedDelay.measure(Proxy.NO_PROXY, "http://127.0.0.1:$dead/", "GET"))
    }
}
