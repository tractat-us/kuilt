package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId

/**
 * A forward awaiting its `ForwardResponse`: the caller's deferred, the original command, the
 * proposer-stamped [dedupKey], and [sentTo] — the peer the `Forward` was actually handed to.
 * Re-wrapped with the proposer's own [dedupKey] on completion so the returned [LogEntry] matches what
 * the leader appended.
 *
 * [sentTo] is the **response-provenance key** (#1911, §8). A `ForwardResponse` carries no term and no
 * leader identity, and its correlation id is a follower-local nonce that starts at `0` on every node —
 * so the transport-level sender is the only thing tying a receipt to the peer that could have produced
 * it. `null` means the forward is parked in `waitingForLeader` and has not been sent to anyone yet, so
 * *no* response for it can be genuine. It is stamped at each send site (registration and flush), never
 * only at construction.
 */
internal data class PendingForward(
    val deferred: CompletableDeferred<LogEntry>,
    val command: ByteArray,
    val dedupKey: DedupKey?,
    val sentTo: NodeId?,
)

/**
 * Client-proposal forwarding state machine (Raft §8). A follower/candidate/learner can't append to the
 * log, so when its consumer calls `propose()` it forwards the request to the leader and waits for the
 * leader's reply. This machine owns that in-flight bookkeeping — the outstanding forwards awaiting a
 * reply ([forwardedProposals]), the ones parked because no leader is known yet ([waitingForLeader]),
 * and the monotonic correlation nonce ([nextForwardId]) that ties each forward to its response.
 *
 * It is a **synchronous, decision-returning** machine: it never sends, traces, or completes the forward
 * deferreds on the normal forward/response/flush path. The engine keeps every `send(...)` and
 * `deferred.complete(...)` side-effect at the call site; the machine returns *what* the engine should do
 * next ([ForwardDecision] / [FlushAction]) and *which* [PendingForward] a response resolved. The one
 * exception is [failAll] (the relinquish/actor-teardown path), which completes the residual deferreds
 * directly — exactly like the sibling machines' teardown methods.
 *
 * **Correlation + exactly-once dedup (do not regress).** [forward] assigns a monotonic [nextForwardId]
 * as the correlation id and stamps the *proposer's own* [DedupKey] into the [PendingForward]; the leader
 * appends under that unchanged key so a retry of an already-committed proposal coalesces (§8 exactly-once)
 * rather than double-appending. The correlation id — not the dedupKey — is what [onResponse] matches a
 * `ForwardResponse` back to its pending forward.
 *
 * **Response provenance (do not regress, #1911).** The correlation id alone is *not* sufficient to
 * accept a `ForwardResponse`: it is a follower-local nonce starting at `0` on every node, and the frame
 * carries neither a term nor a leader identity. Every [PendingForward] therefore records
 * [PendingForward.sentTo] — the peer its `Forward` was handed to — stamped at **both** send sites
 * ([forward] and the [flush] leader path), and [onResponse] accepts a receipt only from that peer.
 *
 * **Queue-while-no-leader → flush-on-leader-known (do not regress).** When no leader is known, [forward]
 * parks the correlation id in [waitingForLeader] instead of sending; [flush] (called by the actor loop
 * after every non-Close command) drains the queue once a leader appears — re-proposing locally if this
 * node has since become leader, or sending the parked forwards to the current leader otherwise.
 *
 * **Deferred completion is exactly-once — every [PendingForward] deferred is completed exactly once.**
 * A forward's deferred is completed by exactly one of: the engine on the [onResponse] resolve path
 * (leader replied); the local re-propose path (a [FlushAction.ReProposeLocally] hands the deferred to the
 * engine's propose path, which completes it via its own `pending` queue); or [failAll] on
 * step-down/teardown. Cancellation is safe throughout: [proposeWithRequestId]'s `finally` cancels the
 * caller deferred, [flush] drops any entry whose deferred is already completed (never re-sending a
 * cancelled forward), and `completeExceptionally` on an already-cancelled deferred is a harmless no-op —
 * so a cancelled forwarding propose never commits later, and a follower propose cancelled while parked in
 * [waitingForLeader] is dropped rather than left to hang.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks, launches no coroutines, and must
 * never be handed to a coroutine that isn't an actor message handler. In particular the caller-thread
 * `deferred.cancel()` in `proposeWithRequestId` deliberately does NOT mutate these maps — the actor loop
 * is their sole owner; cancelled entries are reaped on the actor thread by [flush] and [failAll].
 */
internal class ProposalForwarder {
    /** reqId -> pending forward awaiting a ForwardResponse. */
    private val forwardedProposals = mutableMapOf<Long, PendingForward>()

    /** reqIds queued because no leader was known yet; flushed when a leader appears. */
    private val waitingForLeader = mutableListOf<Long>()

    /** Monotonic correlation nonce for outbound forwards. */
    private var nextForwardId: Long = 0L

    /**
     * Register a proposal this non-leader node must forward (Raft §8). Records a [PendingForward] under a
     * fresh correlation id and returns [ForwardDecision.SendToLeader] when a distinct leader is known — the
     * engine then sends the `Forward` — or [ForwardDecision.Queued] when none is (parked in
     * [waitingForLeader] to be drained by [flush] once a leader appears). The [PendingForward] carries the
     * proposer's own [dedupKey] unchanged so the leader's append preserves exactly-once semantics.
     */
    fun forward(
        response: CompletableDeferred<LogEntry>,
        command: ByteArray,
        dedupKey: DedupKey?,
        leaderId: NodeId?,
        selfId: NodeId,
    ): ForwardDecision {
        val id = nextForwardId++
        val target = leaderId?.takeIf { it != selfId }
        forwardedProposals[id] = PendingForward(response, command, dedupKey, sentTo = target)
        return if (target != null) {
            ForwardDecision.SendToLeader(target, id, command, dedupKey)
        } else {
            waitingForLeader += id
            ForwardDecision.Queued
        }
    }

    /**
     * Resolve the `ForwardResponse` correlated to [clientRequestId] **and sent by [from]**: on
     * [ResponseResolution.Resolved] the matching [PendingForward] is removed and handed back so the
     * engine completes its deferred (with the committed entry, or exceptionally on NotLeader/Failed).
     * Removal on that path means [failAll]/[flush] will never touch this deferred again (exactly-once).
     * Every other resolution leaves the pending entry untouched.
     *
     * **Provenance (#1911, §8 client interaction).** Without the [from] check any admitted peer could
     * fabricate a commit receipt: `propose()` would return a `LogEntry` for a command no node ever
     * appended, and the consumer's exactly-once bookkeeping would mark that write done — so the retry
     * exactly-once exists to provide never happens and the write is lost permanently. A mismatched
     * receipt is **dropped**, not clamped and not thrown: a correlation id is a nonce with no
     * conservative in-range reading (#1817), and a throw on the engine's actor loop is permanent node
     * death (#1818). Dropping leaves the forward outstanding exactly as if the response had been lost on
     * the wire, so the genuine reply — or the caller's own timeout/retry — still resolves it.
     *
     * **Why the three refusals are distinguished rather than collapsed into one `null`.** They have
     * opposite causes and the engine can only log what it is told. [ResponseResolution.WrongSender] is
     * a forgery (or a stale reply from a deposed leader); [ResponseResolution.NoSuchForward] is an
     * unknown/duplicate/already-reaped id; and [ResponseResolution.NotYetSent] means *this node* failed
     * to stamp [PendingForward.sentTo] at a send site — the regression a lost [flush] stamp produces,
     * whose symptom is a `propose()` that hangs forever on a genuine reply from the real leader. Under
     * a single `null` the only runtime signal would report a forgery in exactly the case where the
     * local bookkeeping, not the sender, is at fault.
     */
    fun onResponse(clientRequestId: Long, from: NodeId): ResponseResolution {
        val pf = forwardedProposals[clientRequestId] ?: return ResponseResolution.NoSuchForward
        val sentTo = pf.sentTo ?: return ResponseResolution.NotYetSent
        if (sentTo != from) return ResponseResolution.WrongSender(sentTo)
        forwardedProposals.remove(clientRequestId)
        return ResponseResolution.Resolved(pf)
    }

    /**
     * Drain forwards parked while no leader was known. Returns the [FlushAction]s the engine should carry
     * out — re-propose locally ([FlushAction.ReProposeLocally]) if this node is now the leader, or send to
     * the current leader ([FlushAction.SendToLeader]) otherwise. Returns empty when nothing is queued, or
     * when this node is neither leader nor knows a distinct leader yet (the parked entries stay queued).
     *
     * Entries whose deferred is already completed (cancelled by the caller, or resolved) are dropped and
     * removed here — never re-sent or re-proposed. Every entry sent on the non-leader path has its
     * [PendingForward.sentTo] stamped with the leader it is sent to, so the reply passes [onResponse]'s
     * provenance check. Leader-path entries are LEFT in the map here and evicted
     * by the engine via [reProposed] only after its `onPropose` returns, so [failAll] still owns the
     * deferred across the suspendable propose window; non-leader-path entries stay in the map awaiting their
     * `ForwardResponse`.
     */
    fun flush(leaderId: NodeId?, selfId: NodeId, amLeader: Boolean): List<FlushAction> {
        if (waitingForLeader.isEmpty()) return emptyList()
        if (!amLeader && (leaderId == null || leaderId == selfId)) return emptyList()
        val batch = waitingForLeader.toList()
        waitingForLeader.clear()
        val actions = mutableListOf<FlushAction>()
        for (id in batch) {
            val pf = forwardedProposals[id] ?: continue
            if (pf.deferred.isCompleted) {            // cancelled or already completed → drop, never send
                forwardedProposals.remove(id)
                continue
            }
            if (amLeader) {
                // Keep the entry in forwardedProposals across the engine's suspendable onPropose call so
                // teardown (failAll) still owns the deferred until it lands in the engine's `pending`; the
                // engine calls reProposed(id) to remove it once onPropose returns. Removing it here would
                // orphan the deferred in the onPropose suspension window (cancel/append-throw → hang).
                actions += FlushAction.ReProposeLocally(id, pf)
            } else {
                val target = requireNotNull(leaderId) { "flush: no leader known on the non-leader forward path" }
                // Stamp the provenance key at THIS send site too (#1911): a parked forward is first sent
                // here, to whichever leader turned up, so leaving sentTo at its registration-time null
                // would drop the leader's genuine reply and hang the caller forever.
                forwardedProposals[id] = pf.copy(sentTo = target)
                actions += FlushAction.SendToLeader(target, id, pf.command, pf.dedupKey)
            }
        }
        return actions
    }

    /**
     * Fail every forwarded proposal awaiting a leader or a `ForwardResponse` with [cause] and clear both
     * maps — the relinquish/step-down and actor-teardown path. Each residual [PendingForward] deferred is
     * completed exactly once (or is a harmless no-op if the caller already cancelled it).
     */
    fun failAll(cause: Throwable) {
        forwardedProposals.values.forEach { it.deferred.completeExceptionally(cause) }
        forwardedProposals.clear()
        waitingForLeader.clear()
    }

    /**
     * Remove the re-proposed forward [id] from [forwardedProposals] — called by the engine ONLY after its
     * [onPropose] has returned for a [FlushAction.ReProposeLocally] entry, i.e. once the deferred is safely
     * in the engine's `pending` queue. Until this call the entry stays in the map so [failAll] still owns
     * the deferred across the suspendable propose window (cancel/append-throw completes it rather than
     * leaking it). The brief overlap where the deferred is in BOTH `pending` and [forwardedProposals] is
     * safe: `pending` fails it first and `completeExceptionally` on an already-completed deferred is a no-op.
     */
    fun reProposed(id: Long) {
        forwardedProposals.remove(id)
    }

    /**
     * What a `ForwardResponse` resolved to. The engine completes the caller's deferred on
     * [Resolved] and otherwise drops the frame, logging *which* refusal fired — see [onResponse].
     */
    sealed interface ResponseResolution {
        /** The receipt came from the peer this forward was sent to; [pf] is removed from the map. */
        data class Resolved(val pf: PendingForward) : ResponseResolution

        /** No forward is registered under that correlation id — unknown, duplicate, or already reaped. */
        data object NoSuchForward : ResponseResolution

        /**
         * The forward exists but is still parked in [waitingForLeader] and has been sent to nobody, so
         * no receipt for it can be genuine. Also the signature of a **local** defect: a send site that
         * failed to stamp [PendingForward.sentTo].
         */
        data object NotYetSent : ResponseResolution

        /** The forward was sent to [sentTo] — the responder is not that peer, so the receipt is a forgery. */
        data class WrongSender(val sentTo: NodeId) : ResponseResolution
    }

    /** The engine's next action after registering a proposal to forward. */
    sealed interface ForwardDecision {
        /** A distinct leader is known — the engine sends `Forward([id], [command], [dedupKey])` to [leaderId]. */
        data class SendToLeader(val leaderId: NodeId, val id: Long, val command: ByteArray, val dedupKey: DedupKey?) :
            ForwardDecision

        /** No leader known yet — the forward was parked; the engine does nothing until [flush]. */
        data object Queued : ForwardDecision
    }

    /** One action the engine must carry out while draining the parked-forward queue in [flush]. */
    sealed interface FlushAction {
        /**
         * This node is now the leader — the engine re-runs its propose path for [pf] (which then owns
         * completion via `pending`), and calls [reProposed] with [id] AFTER `onPropose` returns to evict
         * the entry from [forwardedProposals]. The entry is intentionally NOT removed before the propose so
         * [failAll] still owns the deferred across the suspendable window.
         */
        data class ReProposeLocally(val id: Long, val pf: PendingForward) : FlushAction

        /** Send the parked forward `Forward([id], [command], [dedupKey])` to the now-known [leaderId]. */
        data class SendToLeader(val leaderId: NodeId, val id: Long, val command: ByteArray, val dedupKey: DedupKey?) :
            FlushAction
    }
}
