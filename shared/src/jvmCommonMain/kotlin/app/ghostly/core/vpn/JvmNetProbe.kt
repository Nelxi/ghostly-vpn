package app.ghostly.core.vpn

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
 * TLS handshake to host:443 on a direct socket (Android excludes the app from its own tunnel; the
 * desktop asks only when no TUN would catch it). Used by [NetProbe]: a finished handshake means
 * payload passes both ways, which a TCP connect alone does not prove under white lists.
 */
object JvmNetProbe {
    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }
    }

    suspend fun tls(host: String, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        runCatching {
            val addr = InetSocketAddress(host, 443) // DNS outside the timer
            if (addr.isUnresolved) return@runCatching -1L
            Socket().use { raw ->
                val t0 = System.nanoTime()
                raw.connect(addr, timeoutMs)
                raw.soTimeout = timeoutMs
                (trustAll.socketFactory.createSocket(raw, host, 443, false) as SSLSocket).use { ssl ->
                    ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                    ssl.startHandshake()
                }
                ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            }
        }.getOrDefault(-1L)
    }
}
