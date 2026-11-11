package app.ghostly.core.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Server names in an Xray config, swapped for addresses resolved by the app itself.
 *
 * The core looks a server's name up through the system resolver. On a PC that something else hijacks
 * (DNS "unblockers" answering every lookup with a fake 127.x.x.x, or the ISP's spoofed answers) the core
 * then dials a fake address: TCP may limp through the hijacker's local proxy, UDP (Hysteria) never does.
 * The app resolves the names over DNS-over-HTTPS and puts the addresses in; the name stays where it is
 * needed (TLS server name, WebSocket / XHTTP host), so the handshake is exactly the same.
 */
object ServerAddressPin {

    private val SKIP = setOf("freedom", "blackhole", "dns", "loopback")

    /** True for a name worth resolving: not an IP literal, not localhost. */
    fun isDomain(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || ':' in host) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        return '.' in host
    }

    /** Server names of the proxy outbounds in [config]. */
    fun names(config: JsonObject): Set<String> {
        val out = linkedSetOf<String>()
        outbounds(config).forEach { ob -> serverObjects(ob).forEach { s -> s.str("address")?.takeIf(::isDomain)?.let(out::add) } }
        return out
    }

    /** [config] with each server name found in [ips] replaced by its address; TLS / Host keep the name. */
    fun apply(config: JsonObject, ips: Map<String, String>): JsonObject {
        if (ips.isEmpty()) return config
        val obs = config["outbounds"] as? JsonArray ?: return config
        return JsonObject(config + ("outbounds" to JsonArray(obs.map { el -> (el as? JsonObject)?.let { pinOutbound(it, ips) } ?: el })))
    }

    private fun outbounds(config: JsonObject): List<JsonObject> =
        (config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("protocol") !in SKIP }

    /** The objects that carry a server's `address`: vnext[] / servers[] entries, or the settings themselves. */
    private fun serverObjects(ob: JsonObject): List<JsonObject> {
        val st = ob["settings"] as? JsonObject ?: return emptyList()
        val list = ((st["vnext"] as? JsonArray).orEmpty() + (st["servers"] as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }
        return if (st["address"] != null) list + st else list
    }

    private fun pinOutbound(ob: JsonObject, ips: Map<String, String>): JsonObject {
        if (ob.str("protocol") in SKIP) return ob
        val st = ob["settings"] as? JsonObject ?: return ob
        var name: String? = null
        fun pin(o: JsonObject): JsonObject {
            val a = o.str("address") ?: return o
            val ip = ips[a] ?: return o
            name = name ?: a
            return JsonObject(o + ("address" to JsonPrimitive(ip)))
        }
        fun pinList(key: String): Pair<String, JsonElement>? =
            (st[key] as? JsonArray)?.let { arr -> key to JsonArray(arr.map { el -> (el as? JsonObject)?.let(::pin) ?: el }) }
        var newSt = pin(st)
        listOfNotNull(pinList("vnext"), pinList("servers")).forEach { newSt = JsonObject(newSt + it) }
        val host = name ?: return ob
        return JsonObject(ob + ("settings" to newSt) + ("streamSettings" to keepName(ob["streamSettings"] as? JsonObject, host)))
    }

    /** Where the core would have taken the name from the address, write it in explicitly. */
    private fun keepName(ss: JsonObject?, host: String): JsonObject {
        var s = ss ?: JsonObject(emptyMap())
        fun fill(section: String, key: String) {
            val sec = s[section] as? JsonObject ?: JsonObject(emptyMap())
            if (sec.str(key).isNullOrEmpty()) s = JsonObject(s + (section to JsonObject(sec + (key to JsonPrimitive(host)))))
        }
        if (s.str("security") == "tls") fill("tlsSettings", "serverName")
        when (s.str("network")) {
            "ws" -> if ((s["wsSettings"] as? JsonObject)?.str("host").isNullOrEmpty() && (s["wsSettings"] as? JsonObject)?.let { hasHostHeader(it) } != true) fill("wsSettings", "host")
            "httpupgrade" -> fill("httpupgradeSettings", "host")
            "xhttp", "splithttp" -> fill(if (s["splithttpSettings"] != null) "splithttpSettings" else "xhttpSettings", "host")
        }
        return s
    }

    private fun hasHostHeader(ws: JsonObject): Boolean =
        (ws["headers"] as? JsonObject)?.keys?.any { it.equals("host", ignoreCase = true) } == true

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
