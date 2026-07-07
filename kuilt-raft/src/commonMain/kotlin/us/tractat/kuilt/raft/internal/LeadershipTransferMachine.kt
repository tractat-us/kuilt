package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.LeadershipTransferException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.StepDownReason

/**
 * Leader-side leadership-transfer state machine (§3.10). Owns the in-flight transfer target, the
 * caller's completion deferred, and the one-election-timeout auto-abandon timer. It is a
 * **synchronous, decision-returning** machine — it never sends, traces, or mutates engine/[RaftState]
 * fields. The engine keeps every `send(...)`, `emitTrace(...)`, and `debug { }` side-effect at the
 * call site; the machine only reports *what* the engine should do next (send a `TimeoutNow`,
 * trace-abandon a target) and completes the caller's deferred.
 *
 * **All-or-none invariant, made structural.** The pre-extraction engine held the target, the
 * deferred, and the timer as three independent `var`s that had to be set and cleared together. Here
 * they are one nullable [InFlight] record: non-null iff a transfer is in flight; setting and clearing
 * it is a single atomic assignment, so the three can never drift out of step. This is a
 * representation change only — behavior is byte-for-byte the pre-extraction engine's.
 *
 * **Deferred completion is exactly-once — and done *inside* the machine.** Unlike [ReadIndexTracker] and
 * [ProposalForwarder], which hand their outcome back for the engine to complete the caller's deferred at
 * the call site, this machine completes its own deferred: the single `Unit` deferred lives inside the
 * all-or-none [InFlight] record, so setting and clearing it is one atomic assignment and exactly-once is a
 * trivially *local* property here — routing it back through the engine would only add a boundary crossing
 * with no safety gained. Every resolution path ([onTimeout], [onCancel], [onLeadershipRelinquished],
 * [fail]) both cancels the timer and completes the deferred exactly once, then clears [InFlight] so no
 * later path can double-complete. [reset] (leader re-election) is the one path that clears *without*
 * completing — it mirrors the pre-extraction `becomeLeader` clear, which by construction only ever runs
 * after `relinquishToFollower` has already resolved any in-flight transfer.
 *
 * **The timer.** [start] launches a single coroutine on [scope] that, after
 * [RaftConfig.electionTimeoutMax], calls [signalTimeout] with this transfer's generation epoch — which the
 * engine wires to `cmd.trySend(EngineCommand.TransferTimeout(epoch))`, re-entering the actor. This is the
 * only coroutine the machine launches, and it does nothing but re-enter the actor via that channel send:
 * asynchrony is always expressed as an [us.tractat.kuilt.raft.internal.EngineCommand]. The job is cancelled
 * on every resolution. The epoch is the generation guard: [onTimeout] ignores a signal whose epoch no longer
 * matches the in-flight transfer, so a late timeout from an already-resolved transfer cannot abort its
 * successor (#1232) — a `cancel()` cannot stop a timer already past its `delay` and mid-`trySend`.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks and must never be handed to a
 * coroutine that isn't an actor message handler; the timer coroutine only ever `trySend`s.
 *
 * @property scope the engine's coroutine scope — parent of the auto-timeout timer.
 * @property raftConfig supplies [RaftConfig.electionTimeoutMax], the auto-abandon window.
 * @property signalTimeout re-enters the actor when the timer fires, carrying the firing transfer's epoch
 *   (the engine wires `cmd.trySend(EngineCommand.TransferTimeout(epoch))`).
 */
internal class LeadershipTransferMachine(
    private val scope: CoroutineScope,
    private val raftConfig: RaftConfig,
    private val signalTimeout: (epoch: Long) -> Unit,
) {
    /**
     * One in-flight transfer: its [target], the caller's [deferred], the auto-abandon [timeoutJob], and
     * the [epoch] that stamps this transfer's timer. The epoch is the generation guard for the auto-timeout:
     * the timer coroutine captures it and passes it back through [signalTimeout]/[onTimeout], so a late
     * `TransferTimeout` from an already-resolved transfer's timer is recognised as stale and ignored (#1232).
     */
    private data class InFlight(
        val target: NodeId,
        val deferred: CompletableDeferred<Unit>,
        val timeoutJob: Job,
        val epoch: Long,
    )

    private var inFlight: InFlight? = null

    /** Monotonic transfer generation — one per [start], stamped onto the [InFlight.epoch] and its timer. */
    private var epochCounter: Long = 0L

    /** The in-flight transfer target, or null when none is in flight — the propose gate + vote-stickiness query. */
    val inFlightTarget: NodeId?
        get() = inFlight?.target

    /**
     * Begin a transfer to [target], parking [response] until it resolves and arming the one-election-timeout
     * auto-abandon timer. Returns false (and does nothing) if a transfer is already in flight — the caller
     * rejects the second request; the existing transfer keeps its own deferred and timer.
     */
    fun start(target: NodeId, response: CompletableDeferred<Unit>): Boolean {
        if (inFlight != null) return false
        val epoch = ++epochCounter
        val timeoutJob = scope.launch {
            delay(raftConfig.electionTimeoutMax.inWholeMilliseconds)
            signalTimeout(epoch)
        }
        inFlight = InFlight(target, response, timeoutJob, epoch)
        return true
    }

    /**
     * A successful AppendEntries ack arrived from [from] (its [matchIdx]) while [lastLogIdx] is the leader's
     * last log index. Returns true iff a transfer to [from] is in flight AND the target's log now **fully
     * matches the leader's** (`matchIdx >= lastLogIdx`) — the engine's cue to send `TimeoutNow` now. Returns
     * false otherwise (no transfer, an ack from a non-target peer, or the target not yet fully caught up).
     *
     * §3.10 step 2 requires the target to be brought up to the leader's *last* log index — not merely its
     * commit index — before `TimeoutNow`: any uncommitted tail at transfer time (a just-appended proposal,
     * the §5.4.2 term-start no-op, a pending config entry) would otherwise trigger a premature `TimeoutNow`
     * to a not-caught-up target, whose election then fails. `lastLogIdx` stays stable for the duration of the
     * transfer because §3.10 step 1 is enforced in both directions: the engine rejects new proposals and new
     * membership changes while a transfer is in flight, and symmetrically refuses to *start* a transfer while a
     * membership change is still converging — so no entry can be appended between this ack and the `TimeoutNow`.
     */
    fun isTargetCaughtUp(from: NodeId, matchIdx: Long, lastLogIdx: Long): Boolean {
        val current = inFlight ?: return false
        if (from != current.target) return false
        return matchIdx >= lastLogIdx
    }

    /**
     * The auto-abandon timer for generation [epoch] fired: fail the deferred and clear state. Returns the
     * abandoned target for the engine to trace, or null if the transfer already resolved (a stale timer
     * signal — ignore it).
     */
    fun onTimeout(epoch: Long): NodeId? {
        val current = inFlight ?: return null
        if (epoch != current.epoch) return null   // stale timer from a resolved/superseded transfer — ignore
        failWith(current, LeadershipTransferException("leadership transfer to ${current.target.value} timed out"))
        return current.target
    }

    /**
     * Explicit application cancel: fail the deferred and clear state. Returns the cancelled target for the
     * engine to trace, or null if nothing was in flight (no-op).
     */
    fun onCancel(): NodeId? {
        val current = inFlight ?: return null
        failWith(current, LeadershipTransferException("leadership transfer to ${current.target.value} was cancelled"))
        return current.target
    }

    /**
     * The leader is stepping down while a transfer is in flight, having observed a higher term from [from]
     * (null when the step-down carries no originating peer — e.g. a same-term CheckQuorum step-down). The
     * transfer completes successfully **only** when the higher term came from the transfer target — a
     * **best-effort** confirmation: a higher term observed *from* the target strongly (but not conclusively,
     * on a degraded/partitioned network — the message may be an echo, or arrive at the target's RequestVote
     * before its win) indicates the target became leader. The conclusive signal — a leader-authored message
     * from the target — is tracked by #1243. Any other step-down (a higher term from an unrelated node,
     * CheckQuorum, RemovedFromConfig) fails the transfer. No-op when no transfer is in flight.
     */
    fun onLeadershipRelinquished(reason: StepDownReason, from: NodeId?) {
        val current = inFlight ?: return
        if (reason == StepDownReason.HigherTermObserved && from == current.target) {
            current.timeoutJob.cancel()
            current.deferred.complete(Unit)
            inFlight = null
        } else {
            failWith(current, LeadershipTransferException("leader stepped down before transfer completed: $reason (higher term from ${from?.value})"))
        }
    }

    /**
     * Clear any transfer state on becoming leader — cancels the timer and drops [InFlight] *without*
     * completing the deferred (a re-elected-after-stepdown node must not carry stale transfer state).
     * By construction this only runs after `relinquishToFollower` has already resolved any in-flight
     * transfer, so there is no deferred left to complete.
     */
    fun reset() {
        inFlight?.timeoutJob?.cancel()
        inFlight = null
    }

    /** Fail the in-flight transfer (if any) with [cause] — actor-teardown path. No-op when none is in flight. */
    fun fail(cause: LeadershipTransferException) {
        val current = inFlight ?: return
        failWith(current, cause)
    }

    private fun failWith(current: InFlight, cause: LeadershipTransferException) {
        current.timeoutJob.cancel()
        current.deferred.completeExceptionally(cause)
        inFlight = null
    }
}
