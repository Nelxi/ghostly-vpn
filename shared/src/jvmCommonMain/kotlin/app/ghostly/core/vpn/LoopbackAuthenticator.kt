package app.ghostly.core.vpn

import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication

/**
 * Answers java.net's SOCKS5 login prompt with [LoopbackAuth] for loopback proxies, so every
 * `Socket(Proxy(SOCKS, 127.0.0.1:port))` — OkHttp (subscriptions) and [UnifiedDelay] (pings) — logs
 * into the core's `app-in` port. Anything else gets no credentials from us.
 */
object LoopbackAuthenticator {
    @Volatile private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
                val host = requestingSite ?: runCatching { InetAddress.getByName(requestingHost) }.getOrNull()
                if (host?.isLoopbackAddress != true) return null
                return PasswordAuthentication(LoopbackAuth.user, LoopbackAuth.pass.toCharArray())
            }
        })
    }
}
