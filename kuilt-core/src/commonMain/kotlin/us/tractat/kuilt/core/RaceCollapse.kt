package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Thrown by [raceCollapse] when the [Seam] collapses mid-operation — the work being raced can no
 * longer complete because the peers it needs are gone. A collapse surfaces **two distinct ways** and
 * this exception covers both:
 *
 * - **Transport tear** — [Seam.state] latches [SeamState.Torn] (the fabric is gone for good, e.g. a
 *   2-peer mesh whose only link dropped).
 * - **Membership drain** — [Seam.peers] collapses (e.g. to `{selfId}`) while [Seam.state] stays
 *   [SeamState.Woven]. A torn-only watcher never fires; the drain is only visible on [Seam.peers].
 *
 * **Retryable, not fatal:** the collapse surfaces a terminal signal the caller can act on (rejoin,
 * re-elect, re-roll) rather than suspending forever on the missing message. [reason] is the seam's own
 * [CloseReason] when it tore, or [CloseReason.Unreachable] for a membership drain with no tear.
 */
public class SeamCollapsedException(public val reason: CloseReason) :
    Exception("seam collapsed mid-operation: $reason")

/**
 * Run [body] but abort it with a [SeamCollapsedException] the instant this [Seam] collapses mid-operation
 * — either the fabric latches [SeamState.Torn] (transport tear) OR the live peer set satisfies [abortWhen]
 * (membership drain). Whichever of `body`, the tear, or the drain resolves first wins; the losers are
 * cancelled.
 *
 * The mechanism generalises the hand-rolled "abort the handshake when the peer set collapses" watchers
 * that request/response protocols over a [Seam] need: a suspended `channel.receive()` / `flow.first { … }`
 * that waits on a peer's reply never wakes if that peer vanishes, because [Seam.incoming] simply stops
 * delivering — nothing else completes the waiter. Racing the wait against the collapse turns an indefinite
 * hang into a bounded throw.
 *
 * **Two collapse shapes, both covered.** A fabric that latches `Torn` on drain is caught by the always-present
 * torn watcher. A fabric whose membership drains while `state` stays `Woven` (the peer left the roster, no
 * transport death) is caught by [abortWhen] over [Seam.peers]. The default `{ it.size < 2 }` aborts once this
 * peer is alone; pass a predicate to key on specific required participants (e.g. `{ live -> required.any { it !in live } }`).
 *
 * **Eager entry checks.** A collapse already true at entry — the seam is already `Torn`, or [abortWhen] already
 * holds for the current peer set — throws immediately, before [body] is ever started.
 *
 * **Structured, cancellation-correct.** [body] and both watchers run as [CoroutineStart.UNDISPATCHED] siblings,
 * so each reaches its first suspension point before control returns — a collapse racing entry is never missed.
 * All three are cancelled in a `finally` when the race resolves. Cancellation of the caller propagates through
 * `body` untouched (this owns no long-lived scope of its own). Correct under a multi-threaded dispatcher: the
 * single [CompletableDeferred] is the only shared state and first-writer-wins is atomic.
 *
 * @param abortWhen predicate over the live [Seam.peers] set that marks a membership-drain collapse; the default
 *   aborts once this peer is alone (`size < 2`).
 * @param body the operation to run to completion unless the seam collapses first.
 * @throws SeamCollapsedException if the seam tears or drains before [body] completes.
 */
public suspend fun <T> Seam.raceCollapse(
    abortWhen: (Set<PeerId>) -> Boolean = { it.size < 2 },
    body: suspend () -> T,
): T =
    coroutineScope {
        // Eager entry checks: a collapse already true at entry throws before body starts.
        (state.value as? SeamState.Torn)?.let { throw SeamCollapsedException(it.reason) }
        if (abortWhen(peers.value)) throw SeamCollapsedException(collapseReason())

        val outcome = CompletableDeferred<T>()
        // Watchers launched BEFORE work: UNDISPATCHED, so both reach their `.first()` subscription before
        // `body` runs its first synchronous op. A collapse racing that first op (e.g. body's first transport
        // send hitting a just-torn seam) then surfaces as SeamCollapsedException rather than the body's own
        // low-level exception a caller can't retry on.
        val tornWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            val torn = state.filterIsInstance<SeamState.Torn>().first()
            outcome.completeExceptionally(SeamCollapsedException(torn.reason))
        }
        val drainWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            peers.first { abortWhen(it) }
            outcome.completeExceptionally(SeamCollapsedException(collapseReason()))
        }
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                outcome.complete(body())
            } catch (e: CancellationException) {
                // A CE from `work.cancel()` in the finally leaves `outcome` already completed → no-op. But a
                // CE that originates INSIDE `body` (e.g. a `withTimeout` firing) with nothing having cancelled
                // work would otherwise leave `outcome` uncompleted, hanging `outcome.await()` forever — the
                // exact indefinite suspension this primitive exists to prevent. Surface it, then rethrow.
                outcome.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }
        try {
            outcome.await()
        } finally {
            work.cancel()
            tornWatcher.cancel()
            drainWatcher.cancel()
        }
    }

/** [CloseReason] to report for a collapse: the seam's own if it has torn, else [CloseReason.Unreachable] (drain). */
private fun Seam.collapseReason(): CloseReason =
    (state.value as? SeamState.Torn)?.reason ?: CloseReason.Unreachable
