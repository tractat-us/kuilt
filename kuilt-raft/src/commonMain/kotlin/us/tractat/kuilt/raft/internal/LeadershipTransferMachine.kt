package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.LeadershipTransferException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig

/**
 * Leader-side leadership-transfer state machine (§3.10). Owns the in-flight transfer target, the
 * caller's completion deferred, and the one-election-timeout auto-abandon timer. It is a
 * **synchronous, decision-returning** machine — it never sends, traces, or mutates engine/[RaftState]
 * fields. The engine keeps every `send(...)`, `emitTrace(...)`, and `debug { }` side-effect at the
 * call site; the machine only reports *what* the engine should do next (send a `TimeoutNow`,
 * trace-abandon a target) and completes the caller's deferred.
 *
 * **Success is gated on a leader-authored message from the target (#1243).** A transfer completes
 * successfully **only** via [onLeaderElected] — the engine's cue that a leader-authored message
 * (`AppendEntries`/`InstallSnapshot` with `leaderId == target`) arrived at a term above the transfer's
 * start term, i.e. the target *actually is* leader. The old leader's step-down is deliberately NOT a
 * resolution point: the *sender* of the first higher-term message identifies neither the winner nor
 * even a campaigner — a higher-term echo *from* the target (adopted from an unrelated new leader)
 * would be a false SUCCESS, and a non-target voter's higher-term reject outracing the target's genuine
 * win would be a false FAILURE. The transfer therefore stays pending across a step-down and resolves
 * by confirmation ([onLeaderElected]), by the auto-timeout ([onTimeout]), by explicit cancel
 * ([onCancel]), by this node re-winning leadership itself ([onSelfElected]), or at actor teardown
 * ([fail]).
 *
 * **All-or-none invariant, made structural.** The pre-extraction engine held the target, the
 * deferred, and the timer as three independent `var`s that had to be set and cleared together. Here
 * they are one nullable [InFlight] record: non-null iff a transfer is in flight; setting and clearing
 * it is a single atomic assignment, so they can never drift out of step.
 *
 * **Deferred completion is exactly-once — and done *inside* the machine.** Unlike [ReadIndexTracker] and
 * [ProposalForwarder], which hand their outcome back for the engine to complete the caller's deferred at
 * the call site, this machine completes its own deferred: the single `Unit` deferred lives inside the
 * all-or-none [InFlight] record, so setting and clearing it is one atomic assignment and exactly-once is a
 * trivially *local* property here — routing it back through the engine would only add a boundary crossing
 * with no safety gained. Every resolution path ([onLeaderElected], [onTimeout], [onCancel],
 * [onSelfElected], [fail]) both cancels the timer and completes the deferred exactly once, then clears
 * [InFlight] so no later path can double-complete.
 *
 * **The timer.** [start] launches a single coroutine on [scope] that, after
 * [RaftConfig.electionTimeoutMax], calls [signalTimeout] with this transfer's generation epoch — which the
 * engine wires to `cmd.trySend(EngineCommand.TransferTimeout(epoch))`, re-entering the actor. This is the
 * only coroutine the machine launches, and it does nothing but re-enter the actor via that channel send:
 * asynchrony is always expressed as an [us.tractat.kuilt.raft.internal.EngineCommand]. The job is cancelled
 * on every resolution. The epoch is the generation guard: [onTimeout] ignores a signal whose epoch no longer
 * matches the in-flight transfer, so a late timeout from an already-resolved transfer cannot abort its
 * successor (#1232) — a `cancel()` cannot stop a timer already past its `delay` and mid-`trySend`. Because
 * a pending transfer now survives the old leader's step-down, this timer (plus [onSelfElected] and actor
 * teardown) is what bounds the wait: an unconfirmed transfer always fails within one election timeout.
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
     * One in-flight transfer: its [target], the term the transfer [start]ed at ([startTerm] — success
     * requires a leader-authored message from the target at a term strictly above it), the caller's
     * [deferred], the auto-abandon [timeoutJob], and the [epoch] that stamps this transfer's timer. The
     * epoch is the generation guard for the auto-timeout: the timer coroutine captures it and passes it
     * back through [signalTimeout]/[onTimeout], so a late `TransferTimeout` from an already-resolved
     * transfer's timer is recognised as stale and ignored (#1232).
     */
    private data class InFlight(
        val target: NodeId,
        val startTerm: Long,
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
     * Begin a transfer to [target] at the leader's current term [startTerm], parking [response] until it
     * resolves and arming the one-election-timeout auto-abandon timer. Returns false (and does nothing)
     * if a transfer is already in flight — the caller rejects the second request; the existing transfer
     * keeps its own deferred and timer.
     */
    fun start(target: NodeId, startTerm: Long, response: CompletableDeferred<Unit>): Boolean {
        if (inFlight != null) return false
        val epoch = ++epochCounter
        val timeoutJob = scope.launch {
            delay(raftConfig.electionTimeoutMax.inWholeMilliseconds)
            signalTimeout(epoch)
        }
        inFlight = InFlight(target, startTerm, response, timeoutJob, epoch)
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
     * A **leader-authored** message (`AppendEntries`/`InstallSnapshot`) from [leaderId] at [term] reached
     * this node — the engine calls this wherever it resolves its leader StateFlow from a live leader's
     * message. Returns true iff this confirmed the in-flight transfer: [leaderId] is the transfer target
     * and [term] is strictly above the transfer's start term — proof the target actually won an election
     * (#1243, the §3.10 success condition). Completes the deferred successfully and clears state. Returns
     * false otherwise (no transfer in flight, a different leader, or a not-higher term — the latter only
     * reachable under an Election Safety violation, which must not report transfer success).
     */
    fun onLeaderElected(leaderId: NodeId, term: Long): Boolean {
        val current = inFlight ?: return false
        if (leaderId != current.target || term <= current.startTerm) return false
        current.timeoutJob.cancel()
        current.deferred.complete(Unit)
        inFlight = null
        return true
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
     * This node won an election itself while a transfer was still pending (it stepped down mid-transfer,
     * the target never confirmed, and this node was re-elected): the target did not become leader first —
     * fail the deferred and clear state, so the resumed leadership does not inherit a stale transfer (whose
     * propose gate would otherwise re-engage). Returns the abandoned target for the engine to trace, or
     * null when nothing was in flight (the normal first-election case — no-op).
     */
    fun onSelfElected(): NodeId? {
        val current = inFlight ?: return null
        failWith(
            current,
            LeadershipTransferException(
                "this node was re-elected leader before the transfer target ${current.target.value} was confirmed",
            ),
        )
        return current.target
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
