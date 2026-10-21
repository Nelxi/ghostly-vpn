package app.ghostly.core.vpn

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** What the network itself lets through, measured outside the tunnel. */
enum class NetMode {
    /** Foreign sites answer: no white lists, a regular server should work. */
    OPEN,

    /** Only Russian (white-listed) sites answer: the operator's white lists are on. */
    WHITELIST,

    /** Nothing answers, not even Yandex: no internet at all (unpaid, mobile data off, no signal). */
    OFFLINE,
}

/**
 * The white-list test: a TLS handshake straight to well-known sites, bypassing the tunnel.
 * A foreign site that completes a handshake proves the white lists are off; Yandex / VK answering
 * while every foreign site stays silent proves they are on; nobody answering means no internet.
 * A handshake (not a bare TCP connect) is what counts: white lists often let the SYN through and
 * then drop the payload.
 */
object NetProbe {
    val RUSSIAN = listOf("ya.ru", "vk.ru")
    val FOREIGN = listOf("www.google.com", "one.one.one.one", "www.wikipedia.org")
    const val TIMEOUT_MS = 3500

    fun classify(russianOk: Int, foreignOk: Int): NetMode = when {
        foreignOk > 0 -> NetMode.OPEN
        russianOk > 0 -> NetMode.WHITELIST
        else -> NetMode.OFFLINE
    }

    /** All probes at once ([tls] returns the handshake time in ms, negative on failure). */
    suspend fun run(tls: suspend (host: String, timeoutMs: Int) -> Long): NetMode = coroutineScope {
        val ru = RUSSIAN.map { async { tls(it, TIMEOUT_MS) > 0 } }
        val foreign = FOREIGN.map { async { tls(it, TIMEOUT_MS) > 0 } }
        classify(ru.count { it.await() }, foreign.count { it.await() })
    }
}
