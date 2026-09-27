package app.ghostly.core.sub

/**
 * Turns a subscription download failure into a short Russian reason for the user. Platform
 * exceptions (UnknownHost, timeouts, TLS) arrive in English and name Java classes; they are
 * matched by class name and message text, since common code can't see the JVM/Darwin types.
 */
object FetchErrors {

    fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(5).toList()
        val text = chain.joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }.lowercase()
        (chain.firstOrNull { it is SubscriptionException } as? SubscriptionException)?.message?.let { msg ->
            http(msg)?.let { return it }
            if (msg.any { it in 'а'..'я' || it in 'А'..'Я' }) return msg.replaceFirstChar { it.lowercase() }
        }
        return when {
            "offline" in text || "network is unreachable" in text || "not connected to the internet" in text ->
                "нет подключения к интернету"
            "unknownhost" in text || "unable to resolve host" in text || "no address associated" in text ||
                "hostname could not be found" in text || "nodename nor servname" in text ->
                "не удаётся найти сервер подписки. Проверь интернет; если адрес блокируют, обнови с включённым VPN"
            "timeout" in text || "timed out" in text ->
                "сервер подписки не ответил вовремя, попробуй ещё раз"
            "ssl" in text || "certificate" in text || "certpath" in text || "trust anchor" in text || "handshake" in text ->
                "не удалось установить защищённое соединение с сервером подписки"
            "refused" in text || "connectexception" in text || "failed to connect" in text || "connection reset" in text ||
                "could not connect" in text || "unreachable" in text ->
                "сервер подписки недоступен, попробуй позже"
            else -> "не удалось загрузить подписку" + (e.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }
    }

    /** "HTTP 404: hint" from [SubscriptionClient] → what it means for the user, plus the provider's own words. */
    private fun http(msg: String): String? {
        val m = Regex("""^HTTP (\d{3})(?::\s*(.*))?$""").find(msg.trim()) ?: return null
        val code = m.groupValues[1].toInt()
        val reason = when (code) {
            401, 403 -> "доступ к подписке закрыт: она могла закончиться или превышен лимит устройств"
            404, 410 -> "подписка не найдена: ссылка устарела или подписку удалили"
            429 -> "слишком много запросов к серверу подписки, попробуй позже"
            in 500..599 -> "сервер подписки временно не работает (ошибка $code)"
            else -> "сервер подписки ответил ошибкой $code"
        }
        val hint = m.groupValues[2].trim().takeIf { it.isNotEmpty() && it.lowercase().trimEnd('.') !in STATUS_PHRASES }
        return if (hint != null) "$reason. Сообщение провайдера: $hint" else reason
    }

    /** Bodies that only repeat the status line add nothing to the reason above. */
    private val STATUS_PHRASES = setOf(
        "not found", "forbidden", "unauthorized", "gone", "too many requests", "internal server error",
        "bad gateway", "service unavailable", "gateway timeout", "error", "404 not found", "403 forbidden",
        "502 bad gateway", "503 service unavailable", "504 gateway timeout", "500 internal server error",
    )
}
