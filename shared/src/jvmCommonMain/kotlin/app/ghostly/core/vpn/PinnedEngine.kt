package app.ghostly.core.vpn

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress

/**
 * OkHttp client whose DNS answers [host] with [address]: the request keeps [host] in SNI, Host and certificate
 * checks but goes to the chosen node. Other names (a redirect elsewhere) resolve as usual.
 */
object PinnedEngine {
    fun client(host: String, address: String, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname.equals(host, ignoreCase = true)) InetAddress.getAllByName(address).toList()
                        else okhttp3.Dns.SYSTEM.lookup(hostname)
                })
            }
        }
        configure()
    }
}
