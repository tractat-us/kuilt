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
 * A leader identified by [node] **and** the leadership [term] a forward was sent under. The reap key for
 * [ProposalForwarder.onLeaderChanged]: a *change* in either component (different node, OR the same node at
 * a higher term after a crash+restart+re-election) strands any forward still awaiting a `ForwardResponse`.
 */
internal data class LeaderRef(val node: NodeId, val term: Long)

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
 * engine's propose path, which completes it via its own `pending` queue); the leader-change reap
 * ([onLeaderChanged], the engine fails it with `LeadershipLostException` when a *crashed* leader's sent
 * forward is stranded by a new election); or [failAll] on step-down/teardown. Each of these removes the
 * entry from [forwardedProposals] first, so no other path can touch it again. Cancellation is safe
 * throughout: [proposeWithRequestId]'s `finally` cancels the
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
     * The leader **and its leadership term** every currently *sent* forward (in [forwardedProposals] but
     * no longer in [waitingForLeader]) was dispatched to, or `null` when none are in flight to a leader.
     * Because [onLeaderChanged] reaps all sent forwards the instant the known leader changes, every sent
     * forward at any moment targets this one [LeaderRef] — so a single field, not a per-forward record,
     * suffices. Never this node's own id: [forward]/[flush] only send when the leader is a *distinct* peer.
     *
     * The term is part of the key, not just the node: a leader can crash, restart (term/vote persisted),
     * and re-win at a **higher** term while a forward it never answered is still outstanding. Keying on
     * node alone would treat that re-elected same-node leader as "unchanged" and never reap — the caller
     * would park forever again (#1238). Reaping on a term advance is safe: once a leader's term has moved
     * past the one a forward was sent under, that forward can only have been lost in transit or lost to the
     * crash — had the leader received it, it either replied `Committed` or its `onForward` watcher replied
     * `NotLeader` on losing leadership.
     */
    private var sentLeader: LeaderRef? = null

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
        currentTerm: Long,
    ): ForwardDecision {
        val id = nextForwardId++
        forwardedProposals[id] = PendingForward(response, command, dedupKey)
        return if (leaderId != null && leaderId != selfId) {
            sentLeader = LeaderRef(leaderId, currentTerm)
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
     * removed here — never re-sent or re-proposed. Leader-path entries are LEFT in the map here and evicted
     * by the engine via [reProposed] only after its `onPropose` returns, so [failAll] still owns the
     * deferred across the suspendable propose window; non-leader-path entries stay in the map awaiting their
     * `ForwardResponse`.
     */
    fun flush(leaderId: NodeId?, selfId: NodeId, amLeader: Boolean, currentTerm: Long): List<FlushAction> {
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
                sentLeader = LeaderRef(target, currentTerm)
                actions += FlushAction.SendToLeader(target, id, pf.command, pf.dedupKey)
            }
        }
        return actions
    }

    /**
     * Reap the sent forwards stranded by a leader change (Raft §8). Called by the engine the instant a
     * *different* concrete leader becomes known (a follower learning a new leader via AppendEntries /
     * InstallSnapshot, or this node winning an election). Every forward already *sent* to the old leader
     * — in [forwardedProposals] but not still parked in [waitingForLeader] — is removed and returned so
     * the engine fails its deferred with [LeadershipLostException]; the caller then retries, exactly-once
     * under its `requestId`, via the new leader. Without this the deferred would only ever be completed by
     * a `ForwardResponse` that a *crashed* leader never sends — the [proposeWithRequestId] caller parks
     * forever (#1238).
     *
     * **Only a genuine CHANGE reaps.** The key is ([newLeaderId], [newTerm]) — not the node alone. Nothing
     * is reaped only when BOTH match the [LeaderRef] the sent forwards went to ([sentLeader]): the same
     * leader at the same term, i.e. a repeated heartbeat, whose healthy in-flight forward keeps awaiting
     * its response. A *different* node — OR the *same* node at a **higher** term (crash → restart → re-win)
     * — reaps: that latter case is the residual #1238 hang a node-only key would miss. Queued (not-yet-sent)
     * forwards are untouched: [flush] dispatches them to the new leader. Reaping clears [sentLeader] (no
     * sent forwards remain).
     *
     * **Exactly-once holds.** A reaped id is removed from [forwardedProposals], so a late `ForwardResponse`
     * from the old leader resolves to `null` in [onResponse] and completes nothing a second time; and
     * `completeExceptionally` on an already-cancelled caller deferred is a harmless no-op.
     */
    fun onLeaderChanged(newLeaderId: NodeId, newTerm: Long): List<PendingForward> {
        val sent = sentLeader
        // Same leader AND same term — a repeated heartbeat, not a change. Do not disturb a healthy forward.
        if (sent != null && newLeaderId == sent.node && newTerm == sent.term) return emptyList()
        val reaped = mutableListOf<PendingForward>()
        val it = forwardedProposals.iterator()
        while (it.hasNext()) {
            val (id, pf) = it.next()
            if (id in waitingForLeader) continue            // not yet sent — flush will dispatch it to the new leader
            it.remove()
            reaped += pf
        }
        sentLeader = null
        return reaped
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
        sentLeader = null
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
