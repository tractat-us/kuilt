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
 * **Deferred completion is exactly-once.** Every resolution path ([onTimeout], [onCancel],
 * [onLeadershipRelinquished], [fail]) both cancels the timer and completes the deferred exactly once,
 * then clears [InFlight] so no later path can double-complete. [reset] (leader re-election) is the one
 * path that clears *without* completing — it mirrors the pre-extraction `becomeLeader` clear, which by
 * construction only ever runs after `relinquishToFollower` has already resolved any in-flight transfer.
 *
 * **The timer.** [start] launches a single coroutine on [scope] that, after
 * [RaftConfig.electionTimeoutMax], calls [signalTimeout] — which the engine wires to
 * `cmd.trySend(EngineCommand.TransferTimeout)`, re-entering the actor. This is the only coroutine the
 * machine launches, and it does nothing but re-enter the actor via that channel send: asynchrony is
 * always expressed as an [us.tractat.kuilt.raft.internal.EngineCommand]. The job is cancelled on every
 * resolution.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks and must never be handed to a
 * coroutine that isn't an actor message handler; the timer coroutine only ever `trySend`s.
 *
 * @property scope the engine's coroutine scope — parent of the auto-timeout timer.
 * @property raftConfig supplies [RaftConfig.electionTimeoutMax], the auto-abandon window.
 * @property signalTimeout re-enters the actor when the timer fires (the engine wires
 *   `cmd.trySend(EngineCommand.TransferTimeout)`).
 */
internal class LeadershipTransferMachine(
    private val scope: CoroutineScope,
    private val raftConfig: RaftConfig,
    private val signalTimeout: () -> Unit,
) {
    /** One in-flight transfer: its [target], the caller's [deferred], and the auto-abandon [timeoutJob]. */
    private data class InFlight(
        val target: NodeId,
        val deferred: CompletableDeferred<Unit>,
        val timeoutJob: Job,
    )

    private var inFlight: InFlight? = null

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
        val timeoutJob = scope.launch {
            delay(raftConfig.electionTimeoutMax.inWholeMilliseconds)
            signalTimeout()
        }
        inFlight = InFlight(target, response, timeoutJob)
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
     * to a not-caught-up target, whose election then fails. The propose/membership gates (§3.10 step 1) hold
     * `lastLogIdx` stable for the duration of the transfer.
     */
    fun onPeerAck(from: NodeId, matchIdx: Long, lastLogIdx: Long): Boolean {
        val current = inFlight ?: return false
        if (from != current.target) return false
        return matchIdx >= lastLogIdx
    }

    /**
     * The auto-abandon timer fired: fail the deferred and clear state. Returns the abandoned target for the
     * engine to trace, or null if the transfer already resolved (a stale timer signal — ignore it).
     */
    fun onTimeout(): NodeId? {
        val current = inFlight ?: return null
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
     * The leader is stepping down while a transfer is in flight. A [StepDownReason.HigherTermObserved]
     * step-down means the target won its election — complete the deferred successfully; any other reason is
     * an unrelated step-down — fail it. No-op when no transfer is in flight.
     */
    fun onLeadershipRelinquished(reason: StepDownReason) {
        val current = inFlight ?: return
        if (reason == StepDownReason.HigherTermObserved) {
            current.timeoutJob.cancel()
            current.deferred.complete(Unit)
            inFlight = null
        } else {
            failWith(current, LeadershipTransferException("leader stepped down before transfer completed: $reason"))
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
