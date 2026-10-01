package app.ghostly.core

import app.ghostly.core.vpn.LoopbackAuth
import app.ghostly.core.vpn.LoopbackAuthenticator
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** java.net's SOCKS client (used by OkHttp and the pings) logs into a password-only SOCKS5 port. */
class LoopbackAuthenticatorTest {

    @Test
    fun socksClientSendsLoopbackCredentials() {
        LoopbackAuthenticator.install()
        var got: Pair<String, String>? = null
        ServerSocket(0).use { ss ->
            val srv = thread {
                ss.accept().use { c ->
                    val inp = DataInputStream(c.getInputStream())
                    val out = c.getOutputStream()
                    check(inp.readUnsignedByte() == 5)
                    val methods = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
                    check(2.toByte() in methods) { "client didn't offer user/password" }
                    out.write(byteArrayOf(5, 2)); out.flush()
                    check(inp.readUnsignedByte() == 1)
                    val user = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                    val pass = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                    got = user to pass
                    out.write(byteArrayOf(1, 0)); out.flush()
                    // CONNECT request: ver cmd rsv atyp(3) len host port
                    inp.readFully(ByteArray(4))
                    inp.readFully(ByteArray(inp.readUnsignedByte() + 2))
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                }
            }
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ss.localPort))).use {
                it.connect(InetSocketAddress.createUnresolved("example.com", 80), 3000)
            }
            srv.join(3000)
        }
        assertEquals(LoopbackAuth.user to LoopbackAuth.pass, got)
    }
}
