package app.ghostly.core.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a running tunnel in step with what the user picked, without reconnect storms.
 *
 * Flicking through modes or servers used to start one reconnect per tap: on desktop they queued up and the
 * tunnel went down and up several times in a row; on Android they overlapped; and a change made while a
 * reconnect was already running was dropped, so the tunnel ended up on an older mode than the one shown.
 * Here every request lands in a conflated channel: after a short pause the *latest* state is applied once,
 * and only if it differs from what the tunnel was last built from (or the caller forces it).
 * All (re)connects go through [connect], one at a time.
 */
class TunnelApplier<S>(
    scope: CoroutineScope,
    private val debounceMs: Long,
    /** Is there a tunnel that should follow changes (connected or connecting, and wanted)? */
    private val live: () -> Boolean,
    /** What the tunnel should be built from right now. */
    private val desired: () -> S,
    /** Rebuild the tunnel from [desired]; expected to call [connect]. */
    private val reapply: suspend () -> Unit,
) {
    private val lock = Mutex()
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val forcedGeneration = MutableStateFlow(0L)
    private var appliedForce = 0L

    /** What the tunnel was last built from (null: nothing yet). */
    var applied: S? = null
        private set

    init {
        scope.launch {
            for (r in requests) {
                // Wait for a quiet moment: every new request during the pause starts it over.
                var quiet = r
                while (true) {
                    val next = withTimeoutOrNull(quiet) { requests.receiveCatching().getOrNull() } ?: break
                    quiet = maxOf(quiet, next)
                }
                if (!live()) {
                    appliedForce = forcedGeneration.value
                    continue
                }
                val generation = forcedGeneration.value
                if (generation == appliedForce && desired() == applied) continue
                reapply()
                appliedForce = generation
            }
        }
    }

    /** The tunnel should follow the current state; [force]: even if it looks the same (new provider config). */
    fun request(force: Boolean = false, quietMs: Long = debounceMs) {
        if (force) forcedGeneration.update { it + 1 }
        requests.trySend(quietMs.coerceAtLeast(1))
    }

    /** Runs a (re)connect exclusively and records [state] as what the tunnel is built from. */
    suspend fun <T> connect(state: S, block: suspend () -> T): T = lock.withLock {
        block().also { applied = state }
    }

    /** Take the config after acquiring the connect lock, so queued work cannot record an older key. */
    suspend fun <V, T> connectSnapshot(snapshot: () -> Pair<S, V>, block: suspend (V) -> T): T = lock.withLock {
        val (key, value) = snapshot()
        block(value).also { applied = key }
    }

    /** A change applied in place (no reconnect), e.g. a mihomo selector moved. */
    suspend fun <T> inPlace(block: suspend () -> T): T = lock.withLock { block() }

    fun markApplied(state: S) {
        applied = state
    }
}
