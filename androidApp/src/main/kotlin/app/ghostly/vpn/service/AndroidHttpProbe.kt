package app.ghostly.vpn.service

import app.ghostly.core.JsonX
import app.ghostly.core.vpn.Probe
import app.ghostly.core.vpn.UnifiedDelay
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * HTTP latency probes with our own request (GET or HEAD, see [Probe]) through a loopback SOCKS port.
 * libv2ray's built-in delay test times a fresh connection (proxy handshake + TCP + TLS to the target),
 * so its number is several round-trips and jumps a lot; [UnifiedDelay] times the tunnel itself.
 */
object AndroidHttpProbe {

    /** Round-trip of the tunnel through a loopback SOCKS port, ms; negative on failure. */
    fun socks(port: Int, url: String): Long = UnifiedDelay.viaSocks(port, url, Probe.httpMethod)

    /** A throwaway Xray instance with one SOCKS inbound in front of [pingConfig]'s outbound. */
    fun viaXray(pingConfig: JsonObject, url: String): Long {
        val port = ServerSocket(0).use { it.localPort }
        val config = JsonObject(
            pingConfig + ("inbounds" to JsonArray(listOf(XrayConfigBuilder.pingInbound(port)))),
        )
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long = 0
        })
        return try {
            core.startLoop(JsonX.encodeToString(JsonObject.serializer(), config), 0)
            if (!waitForPort(port, 4000)) -1L else socks(port, url)
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { core.stopLoop() }
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return true
            Thread.sleep(25)
        }
        return false
    }
}
