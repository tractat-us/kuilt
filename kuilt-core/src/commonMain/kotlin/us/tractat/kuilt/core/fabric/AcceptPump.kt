package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Duration

// Retry brief source failures promptly without spinning while a listener remains unavailable.
private const val INITIAL_ACCEPT_RETRY_DELAY_MILLIS: Long = 10
// Bound recovery latency after a prolonged outage while limiting repeated failures to once per second.
private const val MAX_ACCEPT_RETRY_DELAY_MILLIS: Long = 1_000

/**
 * Persistent, concurrent, handshake-timed accept loop. Drains [source] forever; each accepted
 * [Connection] is handled in its OWN child coroutine under [handshakeTimeout], so one conn that
 * connects but never completes its handshake can never starve later conns — the sequential
 * `while (isActive) { handle(source.accept()) }` pattern wedges on such a conn; this replaces it.
 *
 * On success the conn is left live (owned by [handle] — e.g. now published into a mesh). On [handle]
 * failure or a handshake timeout, [onFailure] is invoked and the conn is closed. `kuilt-core` is
 * logger-free, so [onFailure] surfaces accept-source failures and per-link rejections/timeouts to a host's logger.
 *
 * An [Exception] from [ConnectionSource.accept] is reported through [onFailure], then the pump delays
 * before accepting again: 10 ms initially, doubling on consecutive failures up to 1 s. A successful
 * accept resets the delay. Source-minted cancellation is retried while the pump remains active;
 * cancellation of the pump itself always propagates. An [Error] escapes. No conn was returned on this
 * path, so the source owns cleanup of any partially accepted conn. Exceptions from [onFailure] while
 * reporting an accept failure are absorbed too; an [Error] from the callback still escapes.
 *
 * **Cancelling the pump [Job] is a third exit, and it is [handle]'s to clean up — not this pump's**
 * (#2587). Both closes above are keyed on a *failure*: [runCatchingCancellable] rethrows a
 * `CancellationException` by construction so `onFailure` never runs, and [withTimeoutOrNull] converts
 * only the timeout it minted itself, so an external cancellation escapes the child `launch` with the
 * conn untouched. Nothing here can close it, because only [handle] knows what it has already taken
 * ownership of — and **[handle] is always handed the conn**: the child starts
 * [UNDISPATCHED][kotlinx.coroutines.CoroutineStart.UNDISPATCHED], which runs its body even on an
 * already-cancelled [Job], so [handle] is entered before the pump next suspends and a cancellation
 * surfaces at a suspension point inside [handle], not in a dispatch queue ahead of it (#2954).
 * In-tree handlers discharge this through [Mesh.addLink]'s ownership guard and Mux admission's
 * temporary-seam guard: cancellation before adoption closes the resource, while startup after
 * adoption belongs to the seam. A third-party [handle] that suspends while holding a conn owes the
 * same guarantee. After adoption it must also avoid a cancellable wait that could outlast this
 * pump's [handshakeTimeout], since the timeout path closes the accepted conn.
 *
 * @param source the accept source drained forever until the pump [Job] is cancelled.
 * @param handshakeTimeout the ceiling on a single conn's [handle]; a conn whose handling exceeds it is
 *   abandoned (its child coroutine cancelled) and closed, and [onFailure] sees a [HandshakeTimeoutException].
 * @param onFailure invoked with a recoverable accept failure, or whenever a conn's [handle] throws or
 *   times out. Best-effort, non-suspending; defaults to a silent absorb.
 * @param handle handles one accepted conn to completion (the handshake + publication). Runs in its own
 *   child coroutine under [handshakeTimeout], started undispatched: everything before its first
 *   suspension runs inline on the pump, ahead of the next accept, and [handshakeTimeout] cannot
 *   interrupt it. A [handle] that blocks before suspending therefore delays later accepts.
 * @return the pump [Job] (a child of the receiver scope); cancel it to stop accepting.
 */
public fun CoroutineScope.acceptPump(
    source: ConnectionSource,
    handshakeTimeout: Duration,
    onFailure: (Throwable) -> Unit = {},
    handle: suspend (Connection) -> Unit,
): Job = launch {
    var retryDelayMillis = INITIAL_ACCEPT_RETRY_DELAY_MILLIS
    while (isActive) {
        val conn = try {
            source.accept()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            try {
                onFailure(failure)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_ACCEPT_RETRY_DELAY_MILLIS)
            continue
        }
        retryDelayMillis = INITIAL_ACCEPT_RETRY_DELAY_MILLIS
        // UNDISPATCHED: [handle] receives the conn before the pump can next observe its own cancellation.
        // A queued DEFAULT child cancelled before dispatch never runs, so nothing would close the conn (#2954).
        launch(start = CoroutineStart.UNDISPATCHED) {
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
