package app.ghostly.core.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The direct check behind [BlockVerdict] for Android and desktop. Must run on sockets that bypass
 * the tunnel (Android excludes the app from it; desktop checks go out directly).
 *
 * A TLS server is asked for its page over the same SNI the client uses: Reality forwards such a
 * stranger to its real "dest" site, so a big page comes back — and the TSPU's 16 KB freeze shows up
 * as a stall somewhere between ~8 and ~48 KB.
 */
object JvmBlockCheck {

    suspend fun run(t: BlockTarget): BlockVerdict = withContext(Dispatchers.IO) {
        if (!tcpOk(t.host, t.port, 3500)) {
            return@withContext if (online()) BlockVerdict.IP_BLOCKED else BlockVerdict.OFFLINE
        }
        val sni = t.sni ?: return@withContext BlockVerdict.REACHABLE
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(t.host, t.port), 3500)
            raw.soTimeout = 5000
            val ssl = trustAll.socketFactory.createSocket(raw, t.host, t.port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            try {
                ssl.startHandshake()
            } catch (_: IOException) {
                return@withContext BlockVerdict.TLS_BLOCKED
            }
            ssl.soTimeout = 4000
            val req = "GET / HTTP/1.1\r\nHost: $sni\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36\r\n" +
                "Accept: text/html,*/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            ssl.outputStream.apply { write(req.toByteArray()); flush() }
            val input = ssl.inputStream
            val buf = ByteArray(8192)
            var total = 0
            try {
                while (total < 128 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            } catch (_: IOException) {
                // Stalled or cut mid-stream: the TSPU freeze lands between ~8 and ~48 KB.
                if (total in 8 * 1024..48 * 1024) return@withContext BlockVerdict.TSPU_16KB
            }
            BlockVerdict.REACHABLE
        } catch (_: IOException) {
            BlockVerdict.TLS_BLOCKED
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun tcpOk(host: String, port: Int, timeout: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        true
    }.getOrDefault(false)

    /** Russian hosts that open on every network, white lists included. */
    private fun online() = listOf("ya.ru", "vk.com", "gosuslugi.ru").any { tcpOk(it, 443, 2500) }

    /** Only a probe: nothing secret is ever sent over it, so any certificate will do. */
    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }
    }
}
