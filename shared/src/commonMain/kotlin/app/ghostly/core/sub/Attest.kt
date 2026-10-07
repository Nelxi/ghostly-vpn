package app.ghostly.core.sub

import kotlinx.serialization.Serializable

/**
 * "Prove you are the real app": Ghostly's server answers a trial subscription with this header when
 * the client says it is Ghostly for Android. The app makes a hardware-attested key for the challenge
 * (see `PlatformInfo.attestKey`), posts the certificate chain and fetches the subscription again.
 * Everything is decided by the server; other providers never send the header, so nothing changes
 * for their subscriptions.
 */
object Attest {
    /** Response header: `v1;<challenge id>;<base64 challenge>`. */
    const val HEADER = "ghostly-attest"

    data class Ask(val id: String, val challenge: String)

    fun parse(header: String?): Ask? {
        val parts = header?.trim()?.split(';') ?: return null
        if (parts.size != 3 || parts[0] != "v1" || parts[1].isBlank() || parts[2].isBlank()) return null
        return Ask(parts[1], parts[2])
    }

    /**
     * Where the answer goes: `<subscription url>/attest`, on the same address the subscription came
     * from (query and fragment dropped). Null when the link doesn't look like `…/sub/<id>`.
     */
    fun endpoint(subscriptionUrl: String): String? {
        val base = subscriptionUrl.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        if (!base.startsWith("https://", true) && !base.startsWith("http://", true)) return null
        val id = base.substringAfterLast('/')
        if (id.isBlank() || !base.removeSuffix("/$id").endsWith("/sub")) return null
        return "$base/attest"
    }

    /** The subscription link for the fetch right after an answer: same link plus `attested=<id>`. */
    fun again(subscriptionUrl: String, ask: Ask): String {
        val url = subscriptionUrl.trim()
        val fragment = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val base = url.substringBefore('#')
        return base + (if ('?' in base) "&" else "?") + "attested=" + ask.id + fragment
    }

    @Serializable
    data class Answer(
        val challenge: String,
        /** Certificate chain, DER in base64, leaf first; empty when the device can't attest. */
        val chain: List<String>,
        val root: Boolean,
        val rootSigns: String,
        val app: String,
        /** Why [chain] is empty, for the server's log. */
        val error: String? = null,
    )
}
