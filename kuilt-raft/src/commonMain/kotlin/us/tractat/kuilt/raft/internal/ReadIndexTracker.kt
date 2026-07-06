package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.NodeId

/** Captures a pending readIndex() call, to be resolved when a quorum heartbeat round confirms freshness. */
internal data class PendingRead(val readIndex: Long, val sinceRound: Long, val deferred: CompletableDeferred<Long>)

/**
 * Leader-side linearizable-read (readIndex, §3.6–3.7 / §6.4) state machine. Owns the read-freshness
 * bookkeeping — the in-flight [PendingRead] queue, the per-voter last-ACK round, the monotonic
 * heartbeat [round] nonce, the current-term no-op gate index, and the parked re-invocations waiting on
 * that gate — plus the freshness/quorum arithmetic that decides when a read is confirmed. It is a
 * **synchronous, decision-returning** machine: it never sends, traces, mutates engine/[RaftState]
 * fields, or completes the read deferreds on the confirm/resolve path. The engine keeps every
 * `send(...)`, `emitTrace(...)`, `debug { }`, and `deferred.complete(...)` side-effect at the call
 * site; the machine returns *what* the engine should do next.
 *
 * The heartbeat [round] nonce lives here because it exists *for* read freshness: the engine stamps it
 * into outgoing `AppendEntries`/`InstallSnapshot` sends (`round = tracker.round`), bumps it once per
 * heartbeat tick ([bumpRound]), and followers echo it back so an ACK can be credited to the exact
 * round it answered.
 *
 * **BLOCKER 1 — round-slip nonce (do not regress).** [recordAck] stores the `echoedRound` the follower
 * actually answered — the round the leader stamped into the request that triggered the response — NOT
 * the current [round] at receipt. A read queued at `sinceRound = H` counts a voter as fresh only when
 * [lastAckRound]`[v] > H` ([resolve]). Without the nonce, an ACK generated in response to a round-H
 * heartbeat but arriving after the round advanced to `H+1` would be credited to `H+1` and wrongly
 * appear fresh for a read queued at `sinceRound = H` (round-slip). Crediting to the echoed round `H`
 * correctly excludes it. Pinned by `roundSlipAckDoesNotConfirmReadIndex` /
 * `staleAckDoesNotConfirmReadIndex`.
 *
 * **BLOCKER 2 — joint dual-majority (do not regress).** Freshness is checked via
 * [MembershipState.quorumOfContacts], which during a Joint configuration requires an independent fresh
 * majority in BOTH the old and new voter sets. Counting only the new (effective) config would let a
 * new-only majority confirm a read while the old majority is unreachable — violating linearizability
 * for writes committed under the old majority not yet covered by the new one. The single-voter
 * fast-path in [request] uses the same [MembershipState.quorumOfContacts] (with an empty contact set)
 * rather than `effectiveConfig.quorumSize == 1`, so a shrinking Joint (old={v1,v2,v3}, new={v1}) does
 * not bypass the old-majority requirement. Pinned by
 * `shrinkingJointFastPathDoesNotConfirmReadWithoutOldMajority` /
 * `jointConsensusReadRequiresBothOldAndNewMajority`.
 *
 * **Current-term no-op gate (§5.4.2 / §8 leader-completeness — do not regress).** A fresh leader's
 * commitIndex may not yet reflect all prior-term entries until its own current-term no-op commits, so a
 * read returned before that point could be stale. [request] parks the read's re-invocation in the
 * no-op gate while `commitIndex < currentTermNoOpIndex`; [onNoOpCommitted] releases the parked
 * re-invocations once the no-op commits. Pinned by `freshLeaderReadIndexWaitsForCurrentTermNoOpToCommit`.
 *
 * **Design note (Raft §6.4):** reads fail only on step-down (no per-read timeout). A partitioned
 * leader that cannot form a quorum is stepped down by CheckQuorum within one election-timeout window,
 * at which point [failAll] delivers `LeadershipLostException` to all callers. Adding per-read timeouts
 * would require a timer per read and improve latency only in the partition case, not safety.
 *
 * **Deferred completion is exactly-once — for queued [PendingRead]s.** Every [PendingRead]'s deferred is
 * completed exactly once — by the engine on the resolve/self-quorum path (from [resolve] /
 * [ReadDecision.ResolveNow]) or by [failAll] on step-down/teardown. [reset] (leader re-election) drops any
 * residual [PendingRead]s without completing them; by construction it only runs after
 * `relinquishToFollower` has already failed the prior term's reads, so nothing is leaked there.
 * **Known exception — no-op-gated reads (#1235).** A read still parked in [pendingNoOpGate] (§8 gate not
 * yet crossed) is NOT a [PendingRead] yet: its live `readIndex()` caller deferred is captured inside the
 * parked re-invocation closure. [failAll] `clear()`s that queue WITHOUT completing those closures, so a
 * gated read whose leader loses leadership before the no-op commits hangs its caller forever instead of
 * throwing `LeadershipLostException`. This is a pre-existing §6.4 liveness gap faithfully preserved by the
 * extraction (behavior-identical to before); the fix lands separately under #1235.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks, launches no coroutines, and
 * must never be handed to a coroutine that isn't an actor message handler.
 */
internal class ReadIndexTracker {
    /**
     * Monotonically increasing per-leadership heartbeat round counter; bumped on each broadcast. The
     * engine stamps it into outgoing `AppendEntries`/`InstallSnapshot` sends and followers echo it back.
     */
    var round: Long = 0L
        private set

    /** In-flight readIndex() calls awaiting a quorum heartbeat ACK in a round after sinceRound. */
    private val pendingReads = mutableListOf<PendingRead>()

    /**
     * Per-voter last-ACK round: maps each voter to the `echoedRound` its most recent
     * AppendEntriesResponse/InstallSnapshotResponse actually answered (BLOCKER 1 fix — the round the
     * follower confirmed, not the current [round] at receipt). Read by [resolve] to count only voters
     * whose ACK arrived *strictly after* a read was queued.
     */
    private val lastAckRound = mutableMapOf<NodeId, Long>()

    /**
     * The log index of the no-op appended on becoming leader (§5.4.2). readIndex() must wait until
     * commitIndex reaches this before returning — the leader-completeness gate (§8).
     */
    private var currentTermNoOpIndex = 0L

    /**
     * Deferred readIndex handlers waiting for the current-term no-op to commit. Each is a re-invocation
     * of the engine's read-request entry point, drained by [onNoOpCommitted] once
     * commitIndex ≥ [currentTermNoOpIndex].
     */
    private val pendingNoOpGate = mutableListOf<() -> Unit>()

    /** Bump the heartbeat round before a broadcast so ACKs referencing it are strictly newer than any pre-send read. */
    fun bumpRound() {
        round++
    }

    /**
     * Record that [from] answered a heartbeat that echoed [echoedRound] (BLOCKER 1a). Crediting the ACK
     * to the round it actually responded to — not the current [round] — is what defeats round-slip.
     */
    fun recordAck(from: NodeId, echoedRound: Long) {
        lastAckRound[from] = echoedRound
    }

    /** Arm the §5.4.2 leader-completeness gate: reads must not resolve before commit reaches [index]. */
    fun onNoOpAppended(index: Long) {
        currentTermNoOpIndex = index
    }

    /**
     * Handle a readIndex() request. The engine has already confirmed leadership; this decides freshness.
     *
     * - `commitIndex < currentTermNoOpIndex`: the §8 gate is not yet crossed — park [reinvoke] to be
     *   redelivered once the no-op commits ([onNoOpCommitted]) and return [ReadDecision.Gated].
     * - self alone is a fresh quorum ([MembershipState.quorumOfContacts] with no contacts — Simple
     *   single-voter, or a Joint where self majorities BOTH sets): return [ReadDecision.ResolveNow] so
     *   the engine completes the deferred immediately.
     * - otherwise: queue a [PendingRead] captured at the current [round] and return [ReadDecision.Queued]
     *   for the engine to log; it resolves on a later post-queue quorum ACK ([resolve]).
     */
    fun request(
        deferred: CompletableDeferred<Long>,
        commitIndex: Long,
        membership: MembershipState,
        selfId: NodeId,
        reinvoke: () -> Unit,
    ): ReadDecision {
        // §8 leader-completeness gate: block until the current-term no-op commits.
        if (commitIndex < currentTermNoOpIndex) {
            pendingNoOpGate += reinvoke
            return ReadDecision.Gated
        }
        val ri = commitIndex
        if (membership.quorumOfContacts(emptySet(), selfId)) {
            // Self alone constitutes a quorum of every active voter set (Simple single-voter, or Joint
            // where self is a majority of BOTH old and new). Freshness is trivially satisfied.
            // NOTE: gating on effectiveConfig.quorumSize == 1 is wrong during a shrinking Joint:
            // effectiveConfig = new, so quorumSize = 1 fires even when old still needs a majority.
            return ReadDecision.ResolveNow(ri)
        }
        // Multi-voter: queue the read to be resolved when a post-queue heartbeat round ACK majority arrives.
        pendingReads += PendingRead(ri, round, deferred)
        return ReadDecision.Queued(ri, round, pendingReads.size)
    }

    /**
     * Resolve any pending reads whose `sinceRound` predates the current [round], provided a voter-quorum
     * has ACKed in a round *strictly after* the read was queued. Returns the reads the engine should
     * confirm (emit `ReadIndexConfirmed` + complete the deferred); the machine has already removed them
     * from its queue. Also opportunistically drops reads whose deferred the caller already cancelled.
     *
     * BLOCKER 1 (round-slip): a voter counts as fresh only when [lastAckRound]`[v] > read.sinceRound`,
     * and [lastAckRound] holds the echoed round the follower answered (see [recordAck]).
     * BLOCKER 2 (joint dual-majority): [MembershipState.quorumOfContacts] requires a fresh majority in
     * BOTH the old and new voter sets during a Joint config.
     */
    fun resolve(membership: MembershipState, selfId: NodeId): List<PendingRead> {
        if (pendingReads.isEmpty()) return emptyList()
        // Drop reads whose caller cancelled before we resolved them.
        pendingReads.removeAll { it.deferred.isCompleted }
        if (pendingReads.isEmpty()) return emptyList()
        val now = round
        val ready = pendingReads.filter { read ->
            // Only voters whose ACK arrived strictly after the read was queued count as fresh.
            val freshContacts = lastAckRound.filterValues { ackRound -> ackRound > read.sinceRound }.keys
            // BLOCKER 2: require fresh quorum of BOTH old and new voter sets for Joint config.
            membership.quorumOfContacts(freshContacts, selfId) && now > read.sinceRound
        }
        if (ready.isEmpty()) return emptyList()
        pendingReads.removeAll(ready)
        return ready
    }

    /**
     * The commit index reached [commitIndex]; if that crosses the current-term no-op gate, return the
     * parked read re-invocations for the engine to re-run (and clear them). Returns empty when the gate
     * is not yet crossed or nothing is parked.
     */
    fun onNoOpCommitted(commitIndex: Long): List<() -> Unit> {
        if (commitIndex < currentTermNoOpIndex || pendingNoOpGate.isEmpty()) return emptyList()
        val gated = pendingNoOpGate.toList()
        pendingNoOpGate.clear()
        return gated
    }

    /**
     * Reset read state for a new leadership term (becomeLeader): clear the pending reads, the no-op
     * gate, the round nonce, and the last-ACK rounds. Does NOT clear [currentTermNoOpIndex] — the caller
     * arms it immediately afterwards via [onNoOpAppended] when it appends the term's no-op, matching the
     * pre-extraction ordering. Any reads from a prior term were already failed by [failAll] on
     * relinquish, so nothing is leaked.
     */
    fun reset() {
        pendingReads.clear()
        pendingNoOpGate.clear()
        round = 0L
        lastAckRound.clear()
    }

    /**
     * Fail the *queued* readIndex() deferreds with [cause] and clear the no-op gate — the
     * relinquish/step-down and actor-teardown path. Each queued [PendingRead] deferred is completed
     * exactly once.
     *
     * **Known gap (#1235):** the [pendingNoOpGate] closures are `clear()`ed but NOT invoked, so the live
     * `readIndex()` caller deferreds captured inside them are dropped, not completed — a read still gated on
     * the current-term no-op at the moment leadership is lost hangs its caller forever instead of throwing
     * `LeadershipLostException`. This preserves the pre-extraction behavior verbatim; the fix lands in #1235.
     */
    fun failAll(cause: Throwable) {
        pendingReads.forEach { it.deferred.completeExceptionally(cause) }
        pendingReads.clear()
        pendingNoOpGate.clear()
    }

    /** The engine's next action after a readIndex() request whose leadership was already confirmed. */
    sealed interface ReadDecision {
        /** Gated on the current-term no-op — the request's re-invocation was parked for redelivery. */
        object Gated : ReadDecision

        /** Self alone is a fresh quorum — the engine completes the deferred with [readIndex] now. */
        data class ResolveNow(val readIndex: Long) : ReadDecision

        /** Queued at [sinceRound] ([pendingCount] now outstanding) — the engine logs and awaits a quorum ACK. */
        data class Queued(val readIndex: Long, val sinceRound: Long, val pendingCount: Int) : ReadDecision
    }
}
