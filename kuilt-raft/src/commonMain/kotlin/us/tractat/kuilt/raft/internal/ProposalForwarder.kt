package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId

/**
 * A forward awaiting its `ForwardResponse`: the caller's deferred, the original command, and the
 * proposer-stamped [dedupKey]. Re-wrapped with the proposer's own [dedupKey] on completion so the
 * returned [LogEntry] matches what the leader appended.
 */
internal data class PendingForward(
    val deferred: CompletableDeferred<LogEntry>,
    val command: ByteArray,
    val dedupKey: DedupKey?,
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
        forwardedProposals[id] = PendingForward(response, command, dedupKey)
        return if (leaderId != null && leaderId != selfId) {
            ForwardDecision.SendToLeader(leaderId, id, command, dedupKey)
        } else {
            waitingForLeader += id
            ForwardDecision.Queued
        }
    }

    /**
     * Resolve the `ForwardResponse` correlated to [clientRequestId]: remove and return the matching
     * [PendingForward] so the engine completes its deferred (with the committed entry, or exceptionally on
     * NotLeader/Failed). Returns `null` for an unknown/duplicate/already-reaped id — the engine then does
     * nothing. Removal here means [failAll]/[flush] will never touch this deferred again (exactly-once).
     */
    fun onResponse(clientRequestId: Long): PendingForward? = forwardedProposals.remove(clientRequestId)

    /**
     * Drain forwards parked while no leader was known. Returns the [FlushAction]s the engine should carry
     * out — re-propose locally ([FlushAction.ReProposeLocally]) if this node is now the leader, or send to
     * the current leader ([FlushAction.SendToLeader]) otherwise. Returns empty when nothing is queued, or
     * when this node is neither leader nor knows a distinct leader yet (the parked entries stay queued).
     *
     * Entries whose deferred is already completed (cancelled by the caller, or resolved) are dropped and
     * removed here — never re-sent or re-proposed. Leader-path entries are removed from the map because
     * the local propose path then owns their completion via the engine's `pending` queue; non-leader-path
     * entries stay in the map awaiting their `ForwardResponse`.
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
                forwardedProposals.remove(id)         // leader path completes via the engine's `pending`
                actions += FlushAction.ReProposeLocally(pf)
            } else {
                actions += FlushAction.SendToLeader(
                    requireNotNull(leaderId) { "flush: no leader known on the non-leader forward path" },
                    id, pf.command, pf.dedupKey,
                )
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

    /** The engine's next action after registering a proposal to forward. */
    sealed interface ForwardDecision {
        /** A distinct leader is known — the engine sends `Forward([id], [command], [dedupKey])` to [leaderId]. */
        data class SendToLeader(val leaderId: NodeId, val id: Long, val command: ByteArray, val dedupKey: DedupKey?) :
            ForwardDecision

        /** No leader known yet — the forward was parked; the engine does nothing until [flush]. */
        object Queued : ForwardDecision
    }

    /** One action the engine must carry out while draining the parked-forward queue in [flush]. */
    sealed interface FlushAction {
        /** This node is now the leader — the engine re-runs its propose path for [pf] (which owns completion). */
        data class ReProposeLocally(val pf: PendingForward) : FlushAction

        /** Send the parked forward `Forward([id], [command], [dedupKey])` to the now-known [leaderId]. */
        data class SendToLeader(val leaderId: NodeId, val id: Long, val command: ByteArray, val dedupKey: DedupKey?) :
            FlushAction
    }
}
