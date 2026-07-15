package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Duration

/**
 * Persistent, concurrent, handshake-timed accept loop. Drains [source] forever; each accepted
 * [Connection] is handled in its OWN child coroutine under [handshakeTimeout], so one conn that
 * connects but never completes its handshake can never starve later conns — the sequential
 * `while (isActive) { handle(source.accept()) }` pattern wedges on such a conn; this replaces it.
 *
 * On success the conn is left live (owned by [handle] — e.g. now published into a mesh). On [handle]
 * failure or a handshake timeout, [onFailure] is invoked and the conn is closed. `kuilt-core` is
 * logger-free, so [onFailure] is how a host surfaces a per-link rejection/timeout to its own logger.
 *
 * @param source the accept source drained forever until the pump [Job] is cancelled.
 * @param handshakeTimeout the ceiling on a single conn's [handle]; a conn whose handling exceeds it is
 *   abandoned (its child coroutine cancelled) and closed, and [onFailure] sees a [HandshakeTimeoutException].
 * @param onFailure invoked with the failure whenever a conn's [handle] throws or times out. Best-effort,
 *   non-suspending; defaults to a silent absorb.
 * @param handle handles one accepted conn to completion (the handshake + publication). Runs in its own
 *   child coroutine under [handshakeTimeout].
 * @return the pump [Job] (a child of the receiver scope); cancel it to stop accepting.
 */
public fun CoroutineScope.acceptPump(
    source: ConnectionSource,
    handshakeTimeout: Duration,
    onFailure: (Throwable) -> Unit = {},
    handle: suspend (Connection) -> Unit,
): Job = launch {
    while (isActive) {
        val conn = source.accept()
        launch {
            val completed = withTimeoutOrNull(handshakeTimeout) {
                runCatchingCancellable { handle(conn) }
                    .onFailure { failure ->
                        onFailure(failure)
                        runCatchingCancellable { conn.close() }
                    }
                true
            }
            if (completed == null) {   // handshake exceeded the timeout — abandon + close
                onFailure(HandshakeTimeoutException(handshakeTimeout))
                runCatchingCancellable { conn.close() }
            }
        }
    }
}

/** Raised through [acceptPump]'s `onFailure` when a conn's `handle` does not finish within the timeout. */
public class HandshakeTimeoutException(timeout: Duration) :
    Exception("accept-pump: handshake did not complete within $timeout")
