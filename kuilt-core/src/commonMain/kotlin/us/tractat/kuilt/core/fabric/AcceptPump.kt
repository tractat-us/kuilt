package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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
 * An [Exception] from [ConnectionSource.accept] is reported through [onFailure], then the pump yields
 * and accepts again. This includes a cancellation minted by the source while the pump remains active;
 * cancellation of the pump itself always propagates. An [Error] escapes. No conn was returned on this
 * path, so the source owns cleanup of any partially accepted conn. Exceptions from [onFailure] while
 * reporting an accept failure are absorbed too; an [Error] from the callback still escapes.
 *
 * **Cancelling the pump [Job] is a third exit, and it is [handle]'s to clean up — not this pump's**
 * (#2587). Both closes above are keyed on a *failure*: [runCatchingCancellable] rethrows a
 * `CancellationException` by construction so `onFailure` never runs, and [withTimeoutOrNull] converts
 * only the timeout it minted itself, so an external cancellation escapes the child `launch` with the
 * conn untouched. Nothing here can close it, because at that instant the conn is suspended somewhere
 * inside [handle] and only [handle] knows what it has already taken ownership of. Every in-tree
 * [handle] discharges the **mid-preamble half** of this by going through the mesh handshake, which
 * closes a conn abandoned during the `MeshHello` exchange under a `NonCancellable` shield; **a
 * third-party [handle] that suspends while holding a conn owes the same guarantee.**
 *
 * The **tail is not discharged here**, and no `acceptPump` call site carries the per-conn register
 * `assembleVoterMesh` keeps for its *dials*: a cancellation landing after the handshake returns a
 * `Link` but before `addLink` returns — inside `admission.admit`, or `startDrain`'s ordering hold —
 * still escapes with the conn unclosed. Tracked by #2710.
 *
 * @param source the accept source drained forever until the pump [Job] is cancelled.
 * @param handshakeTimeout the ceiling on a single conn's [handle]; a conn whose handling exceeds it is
 *   abandoned (its child coroutine cancelled) and closed, and [onFailure] sees a [HandshakeTimeoutException].
 * @param onFailure invoked with a recoverable accept failure, or whenever a conn's [handle] throws or
 *   times out. Best-effort, non-suspending; defaults to a silent absorb.
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
        val conn = try {
            source.accept()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            try {
                onFailure(failure)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
            // A source that fails without suspending must still let other coroutines make progress.
            yield()
            continue
        }
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
