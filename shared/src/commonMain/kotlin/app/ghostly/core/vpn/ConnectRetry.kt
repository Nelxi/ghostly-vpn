package app.ghostly.core.vpn

/**
 * A failed connect is tried again on the same server before anything else happens: most failures are
 * momentary (the TUN adapter of the previous session still closing, a port taken for a second, the
 * network changing under the core), and one error used to leave the app sitting in «Ошибка» for good.
 */
object ConnectRetry {
    /** Tries after the first failure. */
    const val MAX = 5

    /** Pause before try [attempt] (1-based): 1 s, 2 s, 3 s, 4 s, 5 s. */
    fun delayMs(attempt: Int): Long = attempt.coerceIn(1, MAX) * 1_000L

    /** Another try is due after [attempts] tries, for a failure the backend marked [retryable]. */
    fun shouldRetry(attempts: Int, retryable: Boolean): Boolean = retryable && attempts < MAX
}
