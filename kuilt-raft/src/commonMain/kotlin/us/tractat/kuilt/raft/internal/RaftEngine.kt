@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.DenyReason
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LeadershipTransferAbandonReason
import us.tractat.kuilt.raft.LeadershipTransferException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftMetric
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.SnapshotMeta
import us.tractat.kuilt.raft.StepDownReason
import kotlin.time.Duration
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("us.tractat.kuilt.raft.RaftEngine")

internal class RaftEngine(
    private val bootstrapConfig: ClusterConfig,
    private val transport: RaftTransport,
    private val storage: RaftStorage,
    private val raftConfig: RaftConfig,
    private val scope: CoroutineScope,
    private val onMetric: ((RaftMetric) -> Unit)?,
) : RaftNode {

    private val cmd = Channel<EngineCommand>(Channel.UNLIMITED)

    private val _role = MutableStateFlow<RaftRole>(RaftRole.Follower)
    override val role: StateFlow<RaftRole> = _role.asStateFlow()

    private val _leader = MutableStateFlow<NodeId?>(null)
    override val leader: StateFlow<NodeId?> = _leader.asStateFlow()

    private val _commitIndex = MutableStateFlow(0L)
    override val commitIndex: StateFlow<Long> = _commitIndex.asStateFlow()

    /**
     * Emits every committed [Committed] in index order. The overflow policy is [BufferOverflow.SUSPEND]
     * so the actor backpressures rather than silently dropping entries. Callers that fall behind will
     * slow the cluster — this is the correct trade-off for a consensus log where every entry must be
     * delivered.
     */
    private val _committed = MutableSharedFlow<Committed>(
        extraBufferCapacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val committed: Flow<Committed> = _committed

    override val snapshots = MutableStateFlow<Snapshot?>(null)

    private val _compactionFloor = MutableStateFlow(0L)
    override val compactionFloor: StateFlow<Long> = _compactionFloor.asStateFlow()

    override fun committedFrom(fromIndex: Long): Flow<Committed> = flow {
        coroutineScope {
            // Subscribe to the live tail BEFORE the actor captures the cut. UNDISPATCHED
            // runs the collector synchronously up to its first suspension (subscriber
            // registration), so by the time we send CommitCut we're guaranteed registered
            // — no entry committed after the cut can slip through the gap.
            val buffer = Channel<Committed>(Channel.UNLIMITED)
            val tail = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    _committed.collect { buffer.send(it) }
                } finally {
                    buffer.close()
                }
            }
            val result = CompletableDeferred<CommitCutResult>()
            cmd.send(EngineCommand.CommitCut(fromIndex, result))
            val cut = result.await()
            cut.install?.let { emit(Committed.Install(it)) }     // null until Task 3 wires it
            cut.replay.forEach { emit(Committed.Entry(it)) }
            // Tail live, deduped against the replayed prefix. Entries with index <= cutIndex
            // were already replayed from the snapshot; no-ops never surface.
            for (committed in buffer) {
                if (committed is Committed.Entry && committed.entry.index > cut.cutIndex) emit(committed)
                else if (committed is Committed.Install) emit(committed)
            }
            tail.cancel()
        }
    }

    private var traceClock = 0L
    private val _trace = MutableSharedFlow<RaftTraceEvent>(
        extraBufferCapacity = 512,
        // trace is losable debug data — drop oldest on overflow rather than backpressuring consensus
        // (contrast with _committed above, which SUSPENDs to guarantee delivery)
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val trace: Flow<RaftTraceEvent> = _trace

    // ── Effective membership (actor-only mutable state) ──────────────────────

    /**
     * The effective membership: pure function of (log + snapshotConfig + bootstrapConfig).
     * Recomputed by [recomputeMembership] on every append, truncate, and snapshot install —
     * never mutated ad hoc. Starts as Simple(bootstrapConfig) until the first log load.
     */
    private var membership: MembershipState = MembershipState.Simple(bootstrapConfig)

    /**
     * The effective config as of the baseline of the most recently installed or compacted snapshot
     * ([SnapshotMeta.config]), or null when no snapshot is in force or its covered prefix held no config
     * change. Seeded on restart-load, on snapshot install, and on local compaction; consumed by
     * [recomputeMembership] as the "else snapshot's config" branch when the live log no longer carries a
     * config entry (the entry that set the membership was compacted away).
     */
    private var snapshotConfig: ConfigPayload? = null

    // ── Peer-set helpers ─────────────────────────────────────────────────────

    /**
     * Voters other than self — the RequestVote recipients.
     * Joint: union of both voter sets minus self.
     */
    private val otherVoters: Set<NodeId>
        get() = membership.electionTargets(transport.selfId)

    /**
     * All members (voters + learners) other than self — the AppendEntries recipients.
     * Joint: union of all members from both configs minus self.
     */
    private val otherMembers: Set<NodeId>
        get() = membership.replicationTargets(transport.selfId)

    // ── Actor-only mutable state ──────────────────────────────────────────────
    private var currentTerm = 0L
    private var votedFor: NodeId? = null
    private val log = mutableListOf<LogEntry>()
    private var currentCommitIndex = 0L

    // Snapshot / compaction state
    private var snapshotIndex = 0L
    private var snapshotTerm = 0L

    // Candidate state
    private val votesGranted = mutableSetOf<NodeId>()

    // Pre-vote probe state — set while a pre-vote round is in flight, null otherwise
    private var preVoteTerm: Long? = null
    private var preVoteRound: Long = 0L
    private val preVotesGranted = mutableSetOf<NodeId>()

    // Leader state
    private val nextIndex = mutableMapOf<NodeId, Long>()
    private val matchIndex = mutableMapOf<NodeId, Long>()
    private val pending = mutableListOf<Pair<Long, CompletableDeferred<LogEntry>>>()

    /**
     * At most one membership change in flight at a time (one-change-at-a-time rule).
     * Completed when C_new commits; failed on any leadership relinquish or close.
     * Not on [pending] — config entries are internal, withheld from [RaftNode.committed].
     */
    private var pendingConfigChange: CompletableDeferred<ClusterConfig>? = null

    // §7 InstallSnapshot transfer state — leader-only (one chunk in flight per peer; await-ack-then-next).
    private class SnapshotXfer(val meta: SnapshotMeta, val state: ByteArray, var nextOffset: Long)
    private val snapshotXfer = mutableMapOf<NodeId, SnapshotXfer>()

    // §7 InstallSnapshot reassembly state — follower-only (in-order chunk accumulation).
    private class SnapshotReassembly(val meta: SnapshotMeta, val buffer: ArrayList<Byte> = ArrayList())
    private var incomingSnapshot: SnapshotReassembly? = null

    // Timer jobs (cancelled/restarted by actor)
    // timer jobs are children of scope and die with it; Close only stops the actor loop
    private var electionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var quorumCheckJob: Job? = null

    // CheckQuorum: voters (other than self) from whom any response arrived in the current window.
    // Reset each tick. Leader-only.
    private val recentVoterContacts = mutableSetOf<NodeId>()

    // ReadIndex state — leader-only. Cleared on becomeLeader; failed on relinquishToFollower.

    /** Captures a pending readIndex() call, to be resolved when a quorum heartbeat round confirms freshness. */
    private data class PendingRead(val readIndex: Long, val sinceRound: Long, val deferred: CompletableDeferred<Long>)

    /** In-flight readIndex() calls awaiting a quorum heartbeat ACK in a round after sinceRound. */
    private val pendingReads = mutableListOf<PendingRead>()

    /**
     * Per-voter last-ACK round: maps each voter to the [heartbeatRound] value at the time of its
     * most recent AppendEntriesResponse or InstallSnapshotResponse ACK. Used by
     * [resolveReadsIfQuorumFresh] to count only voters whose ACK arrived *strictly after* a read
     * was queued (BLOCKER 1 fix: replaces the cumulative [recentVoterContacts] check which allowed
     * a stale in-flight ACK from a prior round to inflate the fresh-voter count).
     *
     * Seeded/cleared on [becomeLeader]. Leader-only.
     */
    private val lastAckRound = mutableMapOf<NodeId, Long>()

    /** Monotonically increasing per-leadership heartbeat round counter; bumped on each broadcast. */
    private var heartbeatRound = 0L

    /**
     * The log index of the no-op appended on becoming leader (§5.4.2).
     * readIndex() must wait until commitIndex reaches this before returning — the
     * leader-completeness gate (§8): the leader's commitIndex may not yet reflect all
     * prior-term entries until the no-op commits, so a read before that point could
     * return a stale index.
     */
    private var currentTermNoOpIndex = 0L

    /**
     * Deferred readIndex handlers waiting for the current-term no-op to commit.
     * Each is a re-invocation lambda for onRequestReadIndex, drained from advanceCommit
     * once commitIndex ≥ currentTermNoOpIndex.
     */
    private val pendingNoOpGate = mutableListOf<() -> Unit>()

    // Leader-lease state: true while this node has heard from a live leader recently enough
    // that triggering an election would be disruptive. Cleared on stepDown and after electionTimeoutMin.
    private var leaderAlive = false
    private var leaderLeaseJob: Job? = null

    // §3.10 Leadership transfer state — leader-only. Cleared on becomeLeader and relinquishToFollower.

    /**
     * In-flight leadership transfer target. Non-null iff a transfer is in progress.
     * When set, [onPropose] rejects all new proposals with [NotLeaderException].
     */
    private var transferTarget: NodeId? = null

    /**
     * Deferred completed when the transfer resolves (success = Unit, failure = exception).
     * Non-null iff [transferTarget] is non-null.
     */
    private var transferDeferred: CompletableDeferred<Unit>? = null

    /** Timer job that fires [EngineCommand.TransferTimeout] after one election-timeout window. */
    private var transferTimeoutJob: Job? = null

    // ── Metric instrumentation state (actor-only) ─────────────────────────────

    /** Start mark for each in-flight propose, keyed by log index. */
    private val proposeStartTimes = mutableMapOf<Long, TimeSource.Monotonic.ValueTimeMark>()

    /**
     * Start mark for the current election term, or `null` if this node is not a candidate.
     * Reset to `null` on [becomeLeader] or when a new election fires (the old term timed out).
     */
    private var electionStartTime: TimeSource.Monotonic.ValueTimeMark? = null

    /** Term for which [electionStartTime] was recorded — used to emit [RaftMetric.ElectionTimedOut]. */
    private var electionStartTerm: Long = 0L

    init {
        scope.launch {
            // Restore persisted state
            currentTerm = storage.term()
            votedFor = storage.votedFor()
            log.addAll(storage.entries())
            // Recover the snapshot baseline: a persisted snapshot is by definition committed, so
            // seed snapshotIndex/Term, the compaction floor, and commitIndex from it. The persisted
            // log already excludes the discarded prefix, so `entries()` above loaded only entries
            // with index > snapshotIndex.
            storage.loadSnapshot()?.let { stored ->
                snapshotIndex = stored.meta.lastIncludedIndex
                snapshotTerm = stored.meta.lastIncludedTerm
                // Seed the membership baseline from the snapshot so a node that crashed after compacting
                // past a config change recovers under that change (the config entry is gone from the log).
                snapshotConfig = stored.meta.config
                _compactionFloor.value = snapshotIndex
                if (currentCommitIndex < snapshotIndex) {
                    currentCommitIndex = snapshotIndex
                    _commitIndex.value = snapshotIndex
                }
            }
            // Recompute effective membership from the recovered log + snapshot (restart recovery).
            // This is load-bearing: a node that crashed mid-transition comes back under exactly
            // the config its durable log justifies — no special restart path needed.
            recomputeMembership()
            // Set initial role (consults membership.isLearner, so must run after recomputeMembership)
            _role.value = followerRole
            // Start actor and message subscription
            startActor()
            resetElectionTimeout()
            launch {
                transport.incoming.collect {
                    try {
                        cmd.send(EngineCommand.IncomingMessage(it.from, Cbor.decodeFromByteArray(it.bytes)))
                    } catch (_: ClosedSendChannelException) {
                        return@collect // channel closed — node is shutting down
                    }
                }
            }
            launch { snapshots.collect { cmd.trySend(EngineCommand.Compact) } }
        }
    }

    private fun startActor() {
        scope.launch {
            try {
                for (c in cmd) {
                    when (c) {
                        is EngineCommand.IncomingMessage  -> onMessage(c.from, c.message)
                        is EngineCommand.Propose          -> onPropose(c.command, c.response)
                        is EngineCommand.ChangeMembership -> onChangeMembership(c.target, c.response)
                        is EngineCommand.ElectionTimeout  -> onElectionTimeout()
                        is EngineCommand.HeartbeatTick    -> onHeartbeat()
                        is EngineCommand.LeaseExpired     -> { leaderAlive = false }
                        is EngineCommand.Compact          -> onCompact()
                        is EngineCommand.CommitCut        -> onCommitCut(c)
                        is EngineCommand.QuorumCheck      -> onQuorumCheck()
                        is EngineCommand.RequestReadIndex -> onRequestReadIndex(c.deferred)
                        is EngineCommand.TransferLeadership -> onTransferLeadership(c.target, c.response)
                        is EngineCommand.CancelTransfer   -> onCancelTransfer()
                        is EngineCommand.TransferTimeout  -> onTransferTimeout()
                        is EngineCommand.Close            -> { cmd.close(); break }
                    }
                }
            } finally {
                // Complete any in-flight proposals and config changes so their callers don't hang.
                val cause = LeadershipLostException("node scope cancelled")
                failPending(cause)
                failPendingConfigChange(cause)
                failPendingReads(cause)
                failPendingTransfer(LeadershipTransferException("node scope cancelled"))
            }
        }
    }

    // ── Persistence choke-points ──────────────────────────────────────────────

    /** Persist term+vote durably, THEN update in-memory — uniform crash-consistent ordering. */
    private suspend fun persistTermAndVote(term: Long, vote: NodeId?) {
        storage.saveTermAndVotedFor(term, vote)
        currentTerm = term
        votedFor = vote
    }

    /** Persist a vote grant durably, then in-memory (term unchanged). */
    private suspend fun persistVote(vote: NodeId?) {
        storage.saveVotedFor(vote)
        votedFor = vote
    }

    // ── Pending-failure helper ────────────────────────────────────────────────

    /** Complete every in-flight propose() deferred exceptionally and clear the queue. */
    private fun failPending(cause: Throwable) {
        pending.forEach { (_, deferred) -> deferred.completeExceptionally(cause) }
        pending.clear()
    }

    /** Fail the in-flight changeMembership deferred (if any) and clear the change fields. */
    private fun failPendingConfigChange(cause: Throwable) {
        pendingConfigChange?.completeExceptionally(cause)
        pendingConfigChange = null
    }

    /** Fail all in-flight readIndex() deferreds, drain the no-op gate queue, and clear both. */
    private fun failPendingReads(cause: Throwable) {
        pendingReads.forEach { it.deferred.completeExceptionally(cause) }
        pendingReads.clear()
        pendingNoOpGate.clear()
    }

    /**
     * Fail the in-flight leadership transfer (if any) with [cause], cancel its timeout job,
     * and clear all transfer state. Safe to call when no transfer is in flight (no-op then).
     */
    private fun failPendingTransfer(cause: Throwable) {
        transferTimeoutJob?.cancel()
        transferTimeoutJob = null
        transferDeferred?.completeExceptionally(cause)
        transferDeferred = null
        transferTarget = null
    }

    /** Succeed the in-flight leadership transfer — this leader stepped down normally. */
    private fun completePendingTransfer() {
        transferTimeoutJob?.cancel()
        transferTimeoutJob = null
        transferDeferred?.complete(Unit)
        transferDeferred = null
        transferTarget = null
    }

    // ── Role helper ───────────────────────────────────────────────────────────

    /**
     * The non-leader role for this node: [RaftRole.Learner] if the effective membership
     * classifies self as a learner, [RaftRole.Follower] otherwise. Learners replicate the log
     * but never vote, so they must never be promoted to Follower.
     *
     * Consults [membership] (not the static bootstrapConfig) so a node whose role changed
     * via a config log entry correctly reflects its new classification.
     */
    private val followerRole: RaftRole
        get() = if (membership.isLearner(transport.selfId)) RaftRole.Learner else RaftRole.Follower

    // ── Log helpers ───────────────────────────────────────────────────────────

    private fun entryAt(index: Long): LogEntry? = log.firstOrNull { it.index == index }

    private val lastLogIndex: Long get() = log.lastOrNull()?.index ?: snapshotIndex

    private val lastLogTerm: Long get() = log.lastOrNull()?.term ?: snapshotTerm

    /** Term at [index], or `null` if [index] is in the compacted prefix (unknowable from in-memory state). */
    private fun termAt(index: Long): Long? = when {
        index == snapshotIndex -> snapshotTerm
        index < snapshotIndex  -> null
        else                   -> entryAt(index)?.term
    }

    // ── Trace helper ──────────────────────────────────────────────────────────

    private suspend fun emitTrace(event: RaftTraceEvent) = _trace.emit(event)

    private fun nextClock() = ++traceClock

    // ── Metric helper ─────────────────────────────────────────────────────────

    /** Emit [metric] to the [onMetric] hook (no-op when null). */
    private fun emitMetric(metric: RaftMetric) = onMetric?.invoke(metric)

    /**
     * Real-sink replication trace at `debug` level via kotlin-logging. Lazy — the message lambda is
     * only built when debug logging is enabled. Unlike [emitTrace] (the [trace] flow), it does not
     * route through a flow, so it stays visible even when a test's virtual clock stalls — the
     * failure mode that hid the post-install AppendEntries reject loop.
     */
    private inline fun debug(crossinline msg: () -> String) {
        logger.debug { "[raft:${transport.selfId}] ${msg()}" }
    }

    // ── Timers ────────────────────────────────────────────────────────────────

    private fun randomElectionTimeoutMillis(): Long = raftConfig.random.nextLong(
        raftConfig.electionTimeoutMin.inWholeMilliseconds,
        raftConfig.electionTimeoutMax.inWholeMilliseconds,
    )

    private fun resetElectionTimeout() {
        electionJob?.cancel()
        if (_role.value is RaftRole.Learner) return
        electionJob = scope.launch {
            delay(randomElectionTimeoutMillis())
            cmd.trySend(EngineCommand.ElectionTimeout)
        }
    }

    /**
     * Mark the leader as alive and start a lease timer. When the timer fires the lease expires and
     * [leaderAlive] is cleared, allowing pre-votes to be granted again.
     */
    private fun armLeaderLease() {
        leaderAlive = true
        leaderLeaseJob?.cancel()
        leaderLeaseJob = scope.launch {
            delay(raftConfig.electionTimeoutMin.inWholeMilliseconds)
            cmd.trySend(EngineCommand.LeaseExpired)
        }
    }

    // ── Election ──────────────────────────────────────────────────────────────

    private suspend fun onElectionTimeout() {
        if (_role.value is RaftRole.Leader) return
        // A re-timing-out Candidate (probe didn't gather quorum) drops back to follower role
        // for the probe phase so the role accurately reflects "not yet a candidate".
        _role.value = followerRole
        val proposed = currentTerm + 1
        preVoteTerm = proposed
        preVoteRound++
        preVotesGranted.clear()
        preVotesGranted += transport.selfId
        resetElectionTimeout()
        // Single-voter (or all other voters already granted): self pre-vote satisfies quorum — skip probe.
        if (membership.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) { startRealElection(); return }
        emitTrace(RaftTraceEvent.PreVoteStarted(nextClock(), transport.selfId, proposed))
        val pv = RaftMessage.PreVote(proposed, transport.selfId, lastLogIndex, lastLogTerm, preVoteRound)
        otherVoters.forEach { send(it, pv) }
    }

    /** Gate the actual term bump behind a pre-vote quorum. Verbatim body of the old [onElectionTimeout]. */
    private suspend fun startRealElection() {
        preVoteTerm = null
        // If a prior election is still pending, it timed out — emit before overwriting the term.
        if (electionStartTime != null) {
            emitMetric(RaftMetric.ElectionTimedOut(electionStartTerm))
            logger.warn { "[raft:${transport.selfId}] election timed out for term $electionStartTerm" }
        }
        persistTermAndVote(currentTerm + 1, transport.selfId)
        votesGranted.clear()
        votesGranted += transport.selfId
        _role.value = RaftRole.Candidate
        _leader.value = null
        resetElectionTimeout()
        // Record the election start time and emit the metric.
        electionStartTime = TimeSource.Monotonic.markNow()
        electionStartTerm = currentTerm
        emitMetric(RaftMetric.ElectionStarted(currentTerm))
        logger.debug { "[raft:${transport.selfId}] election started for term $currentTerm" }
        // Single-voter cluster: self-vote already satisfies quorum — become leader immediately.
        if (membership.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) { becomeLeader(); return }
        emitTrace(RaftTraceEvent.Timeout(nextClock(), transport.selfId, currentTerm))
        val rv = RaftMessage.RequestVote(currentTerm, transport.selfId, lastLogIndex, lastLogTerm)
        otherVoters.forEach { peer ->
            emitTrace(RaftTraceEvent.RequestVote(nextClock(), transport.selfId, peer, currentTerm, lastLogIndex, lastLogTerm))
            send(peer, rv)
        }
    }

    private suspend fun onRequestVote(from: NodeId, m: RaftMessage.RequestVote) {
        // §4.2.3 leader-stickiness: a node within its leader-lease rejects a higher-term
        // RequestVote without adopting the term, preventing a partitioned voter from deposing
        // a healthy leader the moment it regains connectivity.
        //
        // §3.10 exception: if we are the leader and a transfer to `from` is in flight, we must
        // NOT apply leader-stickiness — the transfer explicitly authorises this candidate to run
        // an election. Step down before normal vote processing so the vote is granted naturally.
        val isTransferCandidate = _role.value is RaftRole.Leader && transferTarget == from
        if (!isTransferCandidate && leaderAlive && m.term > currentTerm) {
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, DenyReason.LeaderAlive))
            send(from, RaftMessage.RequestVoteResponse(currentTerm, false))
            return
        }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        val logOk = isLogUpToDate(log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
        val grant = m.term == currentTerm && logOk && (votedFor == null || votedFor == m.candidateId)
        if (grant) {
            persistVote(m.candidateId)
            resetElectionTimeout()
            emitTrace(RaftTraceEvent.VoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = when {
                m.term < currentTerm -> DenyReason.StaleTerm
                votedFor != null && votedFor != m.candidateId -> DenyReason.AlreadyVoted
                else -> DenyReason.LogNotUpToDate
            }
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.RequestVoteResponse(currentTerm, grant))
    }

    private suspend fun onRequestVoteResponse(from: NodeId, m: RaftMessage.RequestVoteResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Candidate || m.term != currentTerm) return
        if (m.voteGranted) {
            votesGranted += from
            if (membership.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) becomeLeader()
        }
    }

    /**
     * Respond to a pre-vote request: grant iff the proposed term is higher than ours, the
     * candidate's log is at least as up-to-date, and we have not heard from a live leader recently.
     * Does NOT mutate term, votedFor, or timers — pre-vote is hypothesis-only.
     */
    private suspend fun onPreVote(from: NodeId, m: RaftMessage.PreVote) {
        val logOk = isLogUpToDate(log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
        val grant = m.term > currentTerm && logOk && !leaderAlive
        if (grant) {
            emitTrace(RaftTraceEvent.PreVoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = when {
                leaderAlive    -> DenyReason.LeaderAlive
                !logOk         -> DenyReason.LogNotUpToDate
                else           -> DenyReason.StaleTerm
            }
            emitTrace(RaftTraceEvent.PreVoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.PreVoteResponse(currentTerm, grant, m.term, m.round))
    }

    private suspend fun onPreVoteResponse(from: NodeId, m: RaftMessage.PreVoteResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (preVoteTerm == null || m.proposedTerm != preVoteTerm || m.round != preVoteRound) return
        if (m.voteGranted) {
            preVotesGranted += from
            if (membership.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) startRealElection()
        }
    }

    private suspend fun becomeLeader() {
        val elapsed = electionStartTime?.elapsedNow() ?: Duration.ZERO
        electionStartTime = null
        emitMetric(RaftMetric.ElectionWon(currentTerm, elapsed))
        logger.debug { "[raft:${transport.selfId}] won election for term $currentTerm in ${elapsed.inWholeMilliseconds}ms" }

        _role.value = RaftRole.Leader
        _leader.value = transport.selfId
        electionJob?.cancel()
        leaderAlive = true
        leaderLeaseJob?.cancel()
        val nextIdx = lastLogIndex + 1L
        otherMembers.forEach { p ->
            nextIndex[p] = nextIdx
            matchIndex[p] = 0L
        }
        emitTrace(RaftTraceEvent.BecomeLeader(nextClock(), transport.selfId, currentTerm))
        heartbeatJob = scope.launch {
            while (true) {
                cmd.trySend(EngineCommand.HeartbeatTick)
                delay(raftConfig.heartbeatInterval.inWholeMilliseconds)
            }
        }
        recentVoterContacts.clear()
        quorumCheckJob?.cancel()
        quorumCheckJob = scope.launch {
            while (true) {
                delay(randomElectionTimeoutMillis())
                cmd.trySend(EngineCommand.QuorumCheck)
            }
        }
        // ReadIndex state: reset for this leadership term. Any reads queued from a prior term
        // are already failed by relinquishToFollower; start fresh.
        pendingReads.clear()
        pendingNoOpGate.clear()
        heartbeatRound = 0L
        lastAckRound.clear()
        // Transfer state: always clear on becoming leader so a re-elected-after-stepdown node
        // doesn't carry stale transfer state from a previous term.
        transferTarget = null
        transferDeferred = null
        transferTimeoutJob?.cancel()
        transferTimeoutJob = null
        // §5.4.2: append a no-op from the new term so the commit guard (entry.term == currentTerm)
        // can advance commitIndex over any prior-term entries inherited from a previous leader.
        // currentTermNoOpIndex is set inside appendNoOp so readIndex() knows when to gate.
        appendNoOp()
    }

    private suspend fun appendNoOp() {
        val noOpIndex = lastLogIndex + 1L
        currentTermNoOpIndex = noOpIndex   // gate for readIndex(): must not return before this commits
        val noOp = LogEntry(noOpIndex, currentTerm, byteArrayOf(), isNoOp = true)
        log += noOp
        storage.appendEntries(listOf(noOp))
        otherMembers.forEach { sendAppendEntries(it) }
        // Single-voter: no peers will ACK — check for immediate commit.
        tryAdvanceLeaderCommit()
    }

    private suspend fun stepDown(newTerm: Long, reason: StepDownReason) {
        // higher term: adopt it, then relinquish leadership
        persistTermAndVote(newTerm, null)
        relinquishToFollower(reason)
    }

    /**
     * Same-term step-down: relinquish leadership without bumping the term (CheckQuorum path).
     * The term is already current — no persistence required.
     */
    private suspend fun stepDownToFollower(reason: StepDownReason) = relinquishToFollower(reason)

    /**
     * Shared leadership-relinquish body: cancel all leader jobs, fail pending proposals,
     * reset follower state, emit the trace event, and restart the election timer.
     * Called by both [stepDown] (after a term adoption) and [stepDownToFollower] (same-term).
     *
     * If a leadership transfer is in flight and the step-down was triggered by observing a
     * higher term (the transfer target won its election), the transfer is completed successfully.
     * In all other cases the transfer is failed (leader stepped down for an unrelated reason).
     */
    private suspend fun relinquishToFollower(reason: StepDownReason) {
        if (_role.value is RaftRole.Leader) {
            heartbeatJob?.cancel()
            quorumCheckJob?.cancel()
            val cause = LeadershipLostException()
            failPending(cause)
            failPendingConfigChange(cause)
            failPendingReads(LeadershipLostException("lost leadership before read confirmed"))
            debug { "relinquishToFollower($reason): failed in-flight proposals, config change, and pending reads" }
            snapshotXfer.clear()   // leader-only transfer state — abandon any in-flight snapshot sends
            // Leadership transfer: a HigherTermObserved step-down while a transfer is active means the
            // target won its election — complete the transfer deferred successfully. Any other reason
            // (CheckQuorum, RemovedFromConfig) is an unrelated step-down — fail the transfer.
            if (transferTarget != null) {
                if (reason == StepDownReason.HigherTermObserved) {
                    completePendingTransfer()
                } else {
                    failPendingTransfer(LeadershipTransferException("leader stepped down before transfer completed: $reason"))
                }
            }
        }
        leaderAlive = false
        leaderLeaseJob?.cancel()
        preVoteTerm = null          // discard any in-flight pre-vote probe when stepping down
        // If we were a candidate, the election for the prior term implicitly timed out.
        if (_role.value is RaftRole.Candidate && electionStartTime != null) {
            emitMetric(RaftMetric.ElectionTimedOut(electionStartTerm))
            electionStartTime = null
        }
        _role.value = followerRole
        _leader.value = null
        emitTrace(RaftTraceEvent.BecomeFollower(nextClock(), transport.selfId, currentTerm, reason))
        resetElectionTimeout()
    }

    /**
     * CheckQuorum tick: count voters that reached us this window (+1 for self).
     * If fewer than quorumSize, the leader is on the minority side of a partition — step down
     * at the same term (no term bump).
     */
    private suspend fun onQuorumCheck() {
        if (_role.value !is RaftRole.Leader) return
        val contacted = recentVoterContacts.toSet()
        recentVoterContacts.clear()
        // membership.quorumOfContacts credits self per voter set (only when self ∈ that set).
        if (!membership.quorumOfContacts(contacted, transport.selfId)) {
            debug { "onQuorumCheck: lost quorum — contacted=$contacted membership=$membership" }
            stepDownToFollower(StepDownReason.LostQuorum)
        }
    }

    // ── ReadIndex ─────────────────────────────────────────────────────────────

    /**
     * Handle a readIndex() request from the actor channel.
     *
     * Non-leader: complete exceptionally with [NotLeaderException] immediately.
     * Single-voter: self is the quorum — complete immediately with [currentCommitIndex].
     * Multi-voter: queue a [PendingRead] to be resolved after the next heartbeat round
     * that collects a voter-quorum ACK. The next scheduled [onHeartbeat] will bump
     * [heartbeatRound] and broadcast; the ACK via [onAppendEntriesResponse] calls
     * [resolveReadsIfQuorumFresh] which resolves the deferred.
     *
     * Leader-completeness gate (§8): if the current-term no-op has not yet committed,
     * park the request in [pendingNoOpGate] — it will be re-delivered once [advanceCommit]
     * crosses [currentTermNoOpIndex].
     */
    private fun onRequestReadIndex(deferred: CompletableDeferred<Long>) {
        if (_role.value !is RaftRole.Leader) {
            deferred.completeExceptionally(NotLeaderException("readIndex: not the current leader"))
            return
        }
        // §8 leader-completeness gate: block until the current-term no-op commits.
        if (currentCommitIndex < currentTermNoOpIndex) {
            pendingNoOpGate += { onRequestReadIndex(deferred) }
            return
        }
        val ri = currentCommitIndex
        if (membership.quorumOfContacts(emptySet(), transport.selfId)) {
            // Self alone constitutes a quorum of every active voter set (Simple single-voter, or
            // Joint where self is a majority of BOTH old and new). Freshness is trivially satisfied.
            // NOTE: gating on effectiveConfig.quorumSize == 1 is wrong during a shrinking Joint:
            // effectiveConfig = new, so quorumSize = 1 fires even when old still needs a majority.
            deferred.complete(ri)
            return
        }
        // Multi-voter: queue the read to be resolved when a post-queue heartbeat round ACK majority arrives.
        pendingReads += PendingRead(ri, heartbeatRound, deferred)
        debug { "onRequestReadIndex: queued ri=$ri sinceRound=$heartbeatRound pendingReads=${pendingReads.size}" }
    }

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
        heartbeatRound++   // bump the round counter before sending so ACKs that arrive back reference a round > any pre-send sinceRound
        otherMembers.forEach { sendAppendEntries(it) }
    }

    private suspend fun sendAppendEntries(peer: NodeId) {
        val ni = nextIndex[peer] ?: 1L
        // §7: the prefix the follower still needs has been compacted away — divert to InstallSnapshot.
        if (ni <= snapshotIndex) {
            debug { "sendAppendEntries($peer): ni=$ni <= snapshotIndex=$snapshotIndex → divert to InstallSnapshot" }
            sendSnapshotChunk(peer, restart = true); return
        }
        val prevIndex = ni - 1L
        val prevTerm = if (prevIndex == snapshotIndex) snapshotTerm else entryAt(prevIndex)?.term ?: 0L
        val entries = log.filter { it.index >= ni }
        debug { "sendAppendEntries($peer): ni=$ni prevIndex=$prevIndex prevTerm=$prevTerm entries=${entries.size} commit=$currentCommitIndex" }
        emitTrace(
            RaftTraceEvent.AppendEntries(
                clock = nextClock(),
                from = transport.selfId,
                to = peer,
                term = currentTerm,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entryCount = entries.size,
                leaderCommit = currentCommitIndex,
            )
        )
        send(
            peer,
            RaftMessage.AppendEntries(
                term = currentTerm,
                leaderId = transport.selfId,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entries = entries,
                leaderCommit = currentCommitIndex,
                round = heartbeatRound,
            )
        )
    }

    // ── §7 InstallSnapshot ──────────────────────────────────────────────────────

    /**
     * Bytes carried per chunk: the lesser of the transport's payload limit and the configured
     * ceiling, minus a fixed header budget for the CBOR envelope, floored at 1.
     */
    private fun chunkBytes(): Int {
        val cap = transport.maxPayloadBytes?.let { minOf(it, raftConfig.snapshotChunkCeiling) }
            ?: raftConfig.snapshotChunkCeiling
        return maxOf(1, cap - HEADER_BUDGET)
    }

    /**
     * Sends the next snapshot chunk to [peer]. [restart] (or no in-flight transfer) loads the stored
     * snapshot and starts from offset 0; otherwise it resumes from the peer's acked [SnapshotXfer.nextOffset].
     */
    private suspend fun sendSnapshotChunk(peer: NodeId, restart: Boolean) {
        val xfer = if (restart || snapshotXfer[peer] == null) {
            val stored = storage.loadSnapshot() ?: return   // nothing to send yet
            SnapshotXfer(stored.meta, stored.state, 0L).also { snapshotXfer[peer] = it }
        } else {
            snapshotXfer.getValue(peer)
        }
        val start = xfer.nextOffset.toInt()
        val end = minOf(start + chunkBytes(), xfer.state.size)
        val done = end >= xfer.state.size
        debug { "sendSnapshotChunk($peer): through=${xfer.meta.lastIncludedIndex} offset=$start..$end/${xfer.state.size} done=$done restart=$restart" }
        emitTrace(
            RaftTraceEvent.InstallSnapshot(
                nextClock(), transport.selfId, peer, xfer.meta.lastIncludedIndex, xfer.nextOffset, done,
            )
        )
        send(
            peer,
            RaftMessage.InstallSnapshot(
                term = currentTerm,
                leaderId = transport.selfId,
                lastIncludedIndex = xfer.meta.lastIncludedIndex,
                lastIncludedTerm = xfer.meta.lastIncludedTerm,
                offset = xfer.nextOffset,
                data = xfer.state.copyOfRange(start, end),
                done = done,
                config = xfer.meta.config,
                round = heartbeatRound,
            )
        )
    }

    /** Leader: advance or finish a snapshot transfer in response to a follower's ack. */
    private suspend fun onInstallSnapshotResponse(from: NodeId, m: RaftMessage.InstallSnapshotResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        recentVoterContacts += from                // reachability signal for CheckQuorum
        lastAckRound[from] = m.echoedRound         // credit ACK to the round it actually responded to (BLOCKER 1a)
        resolveReadsIfQuorumFresh()                // ReadIndex: snapshot ACKs count as freshness evidence
        val xfer = snapshotXfer[from] ?: return
        xfer.nextOffset = m.nextOffset
        if (xfer.nextOffset >= xfer.state.size) {                 // fully received
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, xfer.meta.lastIncludedIndex)
            nextIndex[from] = xfer.meta.lastIncludedIndex + 1L
            snapshotXfer.remove(from)
            debug { "onInstallSnapshotResponse($from): COMPLETE through=${xfer.meta.lastIncludedIndex} → nextIndex=${nextIndex[from]}, resume AppendEntries" }
            sendAppendEntries(from)                               // resume normal replication
            tryAdvanceLeaderCommit()
        } else {
            debug { "onInstallSnapshotResponse($from): ack offset=${xfer.nextOffset}/${xfer.state.size}, send next chunk" }
            sendSnapshotChunk(from, restart = false)              // next chunk
        }
    }

    /** Follower: reassemble chunks in order, then install the snapshot once the final chunk arrives. */
    private suspend fun onInstallSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot) {
        if (m.term < currentTerm) { send(from, RaftMessage.InstallSnapshotResponse(currentTerm, 0L)); return }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        _role.value = followerRole
        preVoteTerm = null          // a live leader appeared — cancel any in-flight pre-vote probe
        _leader.value = m.leaderId
        resetElectionTimeout()
        armLeaderLease()

        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)
        val r = if (m.offset == 0L) SnapshotReassembly(meta).also { incomingSnapshot = it } else incomingSnapshot
        // Out-of-order or stale chunk: re-advertise the offset we actually hold and wait for a resend.
        if (r == null || r.meta != meta || m.offset != r.buffer.size.toLong()) {
            val have = if (r?.meta == meta) r.buffer.size.toLong() else 0L
            if (have == 0L) incomingSnapshot = null
            debug { "onInstallSnapshot($from): out-of-order offset=${m.offset} (have=$have) → re-advertise, await resend" }
            send(from, RaftMessage.InstallSnapshotResponse(currentTerm, have, echoedRound = m.round))
            return
        }
        r.buffer.addAll(m.data.asList())

        if (!m.done) {
            debug { "onInstallSnapshot($from): chunk offset=${m.offset} accepted (have=${r.buffer.size}), await more" }
            send(from, RaftMessage.InstallSnapshotResponse(currentTerm, r.buffer.size.toLong(), echoedRound = m.round))
            return
        }
        finalizeInstalledSnapshot(from, m, r.buffer.toByteArray())
    }

    /** Persist + apply a fully-reassembled snapshot, reset the log around it, and emit the install. */
    private suspend fun finalizeInstalledSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot, bytes: ByteArray) {
        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)
        storage.saveSnapshot(meta, bytes)
        // Keep the suffix only if our entry at the boundary matches the snapshot's term (Log Matching);
        // otherwise the whole local log is suspect — discard it and rebuild from the snapshot.
        if (entryAt(m.lastIncludedIndex)?.term == m.lastIncludedTerm) {
            storage.discardLogPrefix(m.lastIncludedIndex)
            log.removeAll { it.index <= m.lastIncludedIndex }
        } else {
            storage.truncateFrom(0L)
            log.clear()
        }
        snapshotIndex = m.lastIncludedIndex
        snapshotTerm = m.lastIncludedTerm
        if (currentCommitIndex < m.lastIncludedIndex) {
            currentCommitIndex = m.lastIncludedIndex
            _commitIndex.value = m.lastIncludedIndex
        }
        _compactionFloor.value = snapshotIndex
        // Adopt the snapshot's effective config as the recompute baseline, then recompute membership:
        // the config entries that produced this membership were compacted away on the leader, so the
        // snapshot is the only place the installer can learn them. A non-null joint payload resumes the
        // joint phase. Falls through to log-based or bootstrapConfig when the snapshot carries no config.
        snapshotConfig = m.config
        recomputeMembership()
        _committed.emit(Committed.Install(Snapshot(m.lastIncludedIndex, bytes)))
        incomingSnapshot = null
        debug { "finalizeInstalledSnapshot($from): INSTALLED through=${m.lastIncludedIndex} term=${m.lastIncludedTerm} commit=$currentCommitIndex logTail=${log.firstOrNull()?.index}..${log.lastOrNull()?.index} membership=$membership" }
        emitTrace(RaftTraceEvent.InstallSnapshotAccepted(nextClock(), from, transport.selfId, m.lastIncludedIndex))
        send(from, RaftMessage.InstallSnapshotResponse(currentTerm, bytes.size.toLong(), echoedRound = m.round))
    }

    private suspend fun onAppendEntries(from: NodeId, m: RaftMessage.AppendEntries) {
        if (m.term < currentTerm) {
            send(from, RaftMessage.AppendEntriesResponse(currentTerm, false, echoedRound = m.round))
            return
        }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        // higher term: already adopted it via stepDown above, continue processing in new term
        _role.value = followerRole
        preVoteTerm = null          // a live leader appeared — cancel any in-flight pre-vote probe
        _leader.value = m.leaderId
        resetElectionTimeout()
        armLeaderLease()

        // Log consistency check.
        //
        // The boundary entry at [snapshotIndex] is not in [log] — it was folded into the snapshot —
        // so `entryAt(snapshotIndex)` is null. A normal AppendEntries that resumes right after an
        // install carries `prevLogIndex == snapshotIndex` (the leader supplies `prevLogTerm =
        // snapshotTerm`, see sendAppendEntries). Treating that null as a conflict rejects the resume,
        // the leader backs nextIndex below the floor and re-sends the snapshot, and the
        // install→reject→install loop spins with no delay — freezing virtual time in tests. The
        // snapshot prefix is committed and cluster-agreed, so any `prevLogIndex <= snapshotIndex`
        // already matches; only check entries strictly above the floor.
        if (m.prevLogIndex > snapshotIndex) {
            val prev = entryAt(m.prevLogIndex)
            if (prev == null || prev.term != m.prevLogTerm) {
                // §5.3 fast backup: report conflict info
                val conflictTerm = prev?.term ?: log.lastOrNull { it.index <= m.prevLogIndex }?.term
                val conflictIndex = conflictTerm?.let { t -> log.firstOrNull { it.term == t }?.index }
                val resolvedConflictIndex = conflictIndex ?: m.prevLogIndex
                debug { "onAppendEntries($from): REJECT prevLogIndex=${m.prevLogIndex} prevLogTerm=${m.prevLogTerm} (have=${prev?.term}) snapshotIndex=$snapshotIndex → conflictIndex=$resolvedConflictIndex" }
                emitTrace(
                    RaftTraceEvent.AppendEntriesRejected(
                        clock = nextClock(),
                        from = from,
                        to = transport.selfId,
                        conflictIndex = resolvedConflictIndex,
                        conflictTerm = conflictTerm,
                    )
                )
                send(
                    from,
                    RaftMessage.AppendEntriesResponse(
                        term = currentTerm,
                        success = false,
                        conflictIndex = resolvedConflictIndex,
                        conflictTerm = conflictTerm,
                        echoedRound = m.round,
                    )
                )
                return
            }
        }

        // Truncate conflicting entries and append new ones
        if (m.entries.isNotEmpty()) {
            val first = m.entries.first()
            val conflict = log.firstOrNull { it.index == first.index && it.term != first.term }
            if (conflict != null) {
                storage.truncateFrom(conflict.index)
                log.removeAll { it.index >= conflict.index }
                // Adopt-on-append: recompute membership after rollback so a truncated config entry
                // is immediately uneffected (§6 rollback safety).
                recomputeMembership()
            }
            val toAdd = m.entries.filter { new -> log.none { it.index == new.index } }
            if (toAdd.isNotEmpty()) {
                log.addAll(toAdd)
                storage.appendEntries(toAdd)
                // Adopt-on-append: recompute membership after adding entries — a config entry
                // in toAdd takes effect immediately on the follower.
                recomputeMembership()
            }
        }

        if (m.leaderCommit > currentCommitIndex) {
            advanceCommit(minOf(m.leaderCommit, lastLogIndex))
        }

        val acceptedMatchIndex = lastLogIndex
        debug { "onAppendEntries($from): ACCEPT prevLogIndex=${m.prevLogIndex} +${m.entries.size} entries → matchIndex=$acceptedMatchIndex commit=$currentCommitIndex" }
        emitTrace(
            RaftTraceEvent.AppendEntriesAccepted(
                clock = nextClock(),
                from = from,
                to = transport.selfId,
                matchIndex = acceptedMatchIndex,
            )
        )
        send(from, RaftMessage.AppendEntriesResponse(currentTerm, true, acceptedMatchIndex, echoedRound = m.round))
    }

    private suspend fun onAppendEntriesResponse(from: NodeId, m: RaftMessage.AppendEntriesResponse) {
        // stale-term peer response: step down and discard
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        recentVoterContacts += from                 // reachability signal for CheckQuorum (success or failure)
        lastAckRound[from] = m.echoedRound          // credit ACK to the round it actually responded to (BLOCKER 1a)
        resolveReadsIfQuorumFresh()                 // ReadIndex: check if any pending reads can now be confirmed
        if (m.success) {
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, m.matchIndex)
            nextIndex[from] = matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
            // §3.10: if a transfer is in flight and the target is now caught up, send TimeoutNow.
            sendTimeoutNowIfReady(from)
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            nextIndex[from] = nextIndexAfterFailure(nextIndex[from] ?: 1L, m, log)
            debug { "onAppendEntriesResponse($from): REJECTED → backup nextIndex=${nextIndex[from]} (snapshotIndex=$snapshotIndex), resend" }
            sendAppendEntries(from)
        }
    }

    /**
     * ReadIndex: resolve any pending reads whose sinceRound predates the current heartbeatRound,
     * provided a voter-quorum has ACKed in a round *strictly after* the read was queued.
     *
     * BLOCKER 1 fix (per-voter lastAckRound map, echoed round nonce):
     * A voter is counted as fresh only when [lastAckRound][v] > read.sinceRound. [lastAckRound] is
     * now set to [AppendEntriesResponse.echoedRound] (the round the follower actually responded to),
     * not to the current [heartbeatRound] at receipt. This fixes the round-slip bug: an ACK that
     * was generated in response to a round-H heartbeat but arrived when heartbeatRound=H+1 was
     * previously credited to H+1 (appearing fresh for a read queued at sinceRound=H). With the
     * nonce, it is correctly credited to H = sinceRound and therefore excluded.
     *
     * BLOCKER 2 fix (joint-consensus dual-majority via [MembershipState.quorumOfContacts]):
     * During a Joint config, both the old and new voter sets must independently reach a fresh
     * quorum. Counting only [effectiveConfig] (= new) voters would allow a new-only majority to
     * confirm a read while the old majority is unreachable — violating linearizability for writes
     * committed under the old majority that have not yet been covered by the new one.
     * [quorumOfContacts] already handles Simple vs Joint correctly (same logic used by CheckQuorum).
     * The single-voter fast-path in [onRequestReadIndex] is similarly corrected to use
     * [MembershipState.quorumOfContacts] instead of [effectiveConfig.quorumSize == 1] so a
     * shrinking Joint (old={v1,v2,v3}, new={v1}) does not bypass the old-majority requirement.
     *
     * Design note (Raft §6.4): reads fail only on step-down (no per-read timeout). A partitioned
     * leader that cannot form a quorum will be stepped down by CheckQuorum within one
     * election-timeout window, at which point [failPendingReads] delivers [LeadershipLostException]
     * to all callers. This is intentional: adding per-read timeouts would require a separate timer
     * per read and would not improve safety — only latency in the partition case.
     *
     * Also opportunistically drops reads whose deferred has already been completed (caller cancelled)
     * to avoid an unbounded accumulation of dead waiters.
     */
    private suspend fun resolveReadsIfQuorumFresh() {
        if (pendingReads.isEmpty()) return
        // Drop reads whose caller cancelled before we resolved them.
        pendingReads.removeAll { it.deferred.isCompleted }
        if (pendingReads.isEmpty()) return
        val now = heartbeatRound
        val ready = pendingReads.filter { read ->
            // Only voters whose ACK arrived strictly after the read was queued count as fresh.
            val freshContacts = lastAckRound.filterValues { ackRound -> ackRound > read.sinceRound }.keys
            // BLOCKER 2: require fresh quorum of BOTH old and new voter sets for Joint config.
            membership.quorumOfContacts(freshContacts, transport.selfId) && now > read.sinceRound
        }
        if (ready.isEmpty()) return
        pendingReads.removeAll(ready)
        ready.forEach {
            emitTrace(RaftTraceEvent.ReadIndexConfirmed(nextClock(), it.readIndex, currentTerm))
            it.deferred.complete(it.readIndex)
        }
    }

    private suspend fun tryAdvanceLeaderCommit() {
        // membership.committedIndex accounts for Simple vs Joint quorum, self-credit per voter set,
        // and the §5.4.2 term-guard (only entries from currentTerm can be used to advance commit
        // via replica-count — older entries only commit by implication via Log Matching).
        val majorityIdx = membership.committedIndex(matchIndex, lastLogIndex, transport.selfId) ?: return
        val entry = entryAt(majorityIdx)
        if (entry != null && entry.term == currentTerm && majorityIdx > currentCommitIndex) {
            advanceCommit(majorityIdx)
        }
    }

    private suspend fun advanceCommit(newCommit: Long) {
        val oldCommit = currentCommitIndex
        // Capture only the LAST committed config entry in this advance window; side-effects run AFTER
        // the loop so currentCommitIndex is already bumped and entryAt is not racing a mutation.
        // Keeping only the last is safe even if a Joint and its trailing Simple(C_new) both commit in
        // one window: C_new is then already in the log, so onConfigCommitted's Joint branch would only
        // re-append an entry C_new identical to what already exists — skipping it is harmless, and we
        // run the Simple(C_new) side-effects (complete the deferred, removed-leader step-down) directly.
        var committedConfigEntry: LogEntry? = null
        for (idx in (currentCommitIndex + 1)..newCommit) {
            val entry = entryAt(idx) ?: continue
            when {
                entry.config != null -> {
                    // Config entry: advance commitIndex but withhold from _committed (internal, like no-op).
                    committedConfigEntry = entry
                }
                entry.isNoOp -> {
                    // §5.4.2 no-op: advance commitIndex but withhold from application-facing flow.
                }
                else -> {
                    emitProposeCommittedAndApplied(entry)
                    _committed.emit(Committed.Entry(entry))
                }
            }
            _commitIndex.value = idx
            pending.removeAll { (i, d) -> if (i == idx) { d.complete(entry); true } else false }
        }
        currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
        // ReadIndex leader-completeness gate: if the current-term no-op just committed, re-deliver
        // any readIndex() requests that were parked waiting for it.
        if (currentCommitIndex >= currentTermNoOpIndex && pendingNoOpGate.isNotEmpty()) {
            val gated = pendingNoOpGate.toList()
            pendingNoOpGate.clear()
            gated.forEach { it() }
        }
        // Config-commit side effects AFTER commitIndex is bumped — safe to call appendConfigEntry.
        committedConfigEntry?.let { onConfigCommitted(it) }
    }

    /**
     * Emit [RaftMetric.ProposeCommitted] then [RaftMetric.ProposeApplied] for [entry].
     * Both share the same elapsed time snapshot so the sequence is consistent. Logs at warn
     * when elapsed exceeds [RaftConfig.slowProposeThreshold], debug otherwise.
     * Removes the start-time entry from [proposeStartTimes].
     */
    private fun emitProposeCommittedAndApplied(entry: LogEntry) {
        val startMark = proposeStartTimes.remove(entry.index) ?: return
        val elapsed = startMark.elapsedNow()
        emitMetric(RaftMetric.ProposeCommitted(entry.index, elapsed))
        emitMetric(RaftMetric.ProposeApplied(entry.index, elapsed))
        if (elapsed >= raftConfig.slowProposeThreshold) {
            logger.warn { "[raft:${transport.selfId}] slow propose at index ${entry.index}: ${elapsed.inWholeMilliseconds}ms (threshold ${raftConfig.slowProposeThreshold.inWholeMilliseconds}ms)" }
        } else {
            logger.debug { "[raft:${transport.selfId}] propose at index ${entry.index} applied in ${elapsed.inWholeMilliseconds}ms" }
        }
    }

    /**
     * Snapshot the committed application log for [committedFrom]. Runs in the actor, so
     * [currentCommitIndex] and [log] are read at a single consistent point in the commit
     * stream — the caller has already registered a live subscriber, so entries committed
     * after this cut tail through that subscription without a gap.
     *
     * When [fromIndex] falls at or below [snapshotIndex], loads the stored snapshot and
     * prepends a [Committed.Install] so the subscriber can reset its state machine.
     */
    private suspend fun onCommitCut(c: EngineCommand.CommitCut) {
        val install = if (c.fromIndex <= snapshotIndex && snapshotIndex > 0L)
            storage.loadSnapshot()?.let { Snapshot(it.meta.lastIncludedIndex, it.state) } else null
        val from = maxOf(c.fromIndex, snapshotIndex + 1)
        val replay = log.filter { it.index in from..currentCommitIndex && !it.isNoOp }
        c.response.complete(CommitCutResult(replay, currentCommitIndex, install))
    }

    private suspend fun onCompact() {
        val s = snapshots.value ?: return
        if (s.throughIndex <= snapshotIndex || s.throughIndex > currentCommitIndex) return
        val term = termAt(s.throughIndex) ?: return   // must be a live, committed entry
        // The membership the snapshot must carry is the config as of `throughIndex` — the highest-index
        // config entry at or below the cut, else the config the prior snapshot already recorded. It must
        // NOT be the live `membership`, which may reflect a later config entry between the cut and the
        // log tail (that would stamp the snapshot with a future config and corrupt an installer's view).
        val configAsOfCut = log.lastOrNull { it.config != null && it.index <= s.throughIndex }?.config
            ?: snapshotConfig
        storage.saveSnapshot(SnapshotMeta(s.throughIndex, term, configAsOfCut), s.state)   // durable FIRST
        storage.discardLogPrefix(s.throughIndex)                             // then drop prefix
        log.removeAll { it.index <= s.throughIndex }
        snapshotIndex = s.throughIndex
        snapshotTerm = term
        // Retain the compacted config as the snapshot baseline so a subsequent recompute (or restart)
        // still resolves membership correctly once the config entry is gone from the live log.
        snapshotConfig = configAsOfCut
        _compactionFloor.value = snapshotIndex
        emitTrace(RaftTraceEvent.Compacted(nextClock(), transport.selfId, snapshotIndex, snapshotTerm))
    }

    // ── propose() ─────────────────────────────────────────────────────────────

    private suspend fun onPropose(command: ByteArray, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            response.completeExceptionally(NotLeaderException())
            return
        }
        // §3.10: while a leadership transfer is in flight, reject new proposals so the target can
        // catch up to our log without racing additional appends. The NotLeaderException is the correct
        // signal — the caller should retry on the new leader once transfer completes.
        if (transferTarget != null) {
            response.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferTarget!!.value}"))
            return
        }
        val index = lastLogIndex + 1L
        val entry = LogEntry(index, currentTerm, command)
        log += entry
        storage.appendEntries(listOf(entry))
        emitTrace(RaftTraceEvent.ClientRequest(nextClock(), transport.selfId, index, currentTerm))
        proposeStartTimes[index] = TimeSource.Monotonic.markNow()
        emitMetric(RaftMetric.ProposeAccepted(index, currentTerm))
        logger.debug { "[raft:${transport.selfId}] propose accepted at index $index term $currentTerm" }
        pending += index to response
        otherMembers.forEach { sendAppendEntries(it) }
        // Single-voter: no peers will ACK — check for immediate commit (peerQuorum == 0).
        tryAdvanceLeaderCommit()
    }

    override suspend fun propose(command: ByteArray): LogEntry {
        val d = CompletableDeferred<LogEntry>()
        try {
            cmd.send(EngineCommand.Propose(command, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        return d.await()
    }

    override suspend fun readIndex(): Long {
        val d = CompletableDeferred<Long>()
        try {
            cmd.send(EngineCommand.RequestReadIndex(d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        return d.await()
    }

    // ── Membership ────────────────────────────────────────────────────────────

    /**
     * Recompute [membership] from the current log + [snapshotConfig] + [bootstrapConfig].
     *
     * Resolution order: highest-index config entry in the live log, else snapshot config, else
     * Simple(bootstrapConfig). This is a deterministic function of (log, snapshot, bootstrap),
     * plus self-role re-evaluation and a [RaftTraceEvent.ConfigChange] trace event on genuine
     * transitions. The adopt-on-append and rollback model means there is no separate undo path;
     * truncation simply removes entries and recomputing this function produces the correct
     * rolled-back config automatically.
     *
     * Called after every append, truncate, and snapshot install so the engine always operates under
     * the config justified by its current log state. Emits [RaftTraceEvent.ConfigChange] on both
     * the leader (via [appendConfigEntry]) and followers (via [onAppendEntries]), covering rollback
     * on truncate as well.
     */
    private suspend fun recomputeMembership() {
        val prior = membership
        val configEntry = log.lastOrNull { it.config != null }
        val logConfig = configEntry?.config
        val resolved = logConfig ?: snapshotConfig
        val newMembership = when {
            resolved == null           -> MembershipState.Simple(bootstrapConfig)
            resolved.old != null       -> MembershipState.Joint(resolved.old, resolved.new)
            else                       -> MembershipState.Simple(resolved.new)
        }
        val branch = when {
            configEntry != null    -> "log[${configEntry.index}]"
            snapshotConfig != null -> "snapshot"
            else                   -> "bootstrap"
        }
        val changed = newMembership != prior
        membership = newMembership
        reevaluateSelfRole()
        if (changed) {
            debug { "recomputeMembership: $prior → $newMembership (source=$branch)" }
            // `old` is the prior effective config — on the first ever change that is the
            // bootstrap config, which is more informative than null.
            val configIndex = configEntry?.index ?: if (snapshotConfig != null) snapshotIndex else lastLogIndex
            emitTrace(
                RaftTraceEvent.ConfigChange(
                    nextClock(), transport.selfId, configIndex, prior.effectiveConfig, newMembership.effectiveConfig,
                ),
            )
        }
    }

    /**
     * After recomputing [membership], re-evaluate this node's resting role.
     *
     * A previously-learner node whose new config makes it a voter should leave [RaftRole.Learner]
     * and become election-eligible. A voter node whose new config makes it a learner should enter
     * [RaftRole.Learner]. Only adjusts the follower/learner resting role — does not disturb an
     * active [RaftRole.Candidate] or [RaftRole.Leader].
     */
    private fun reevaluateSelfRole() {
        val current = _role.value
        if (current is RaftRole.Leader || current is RaftRole.Candidate) return
        val desired = followerRole
        if (current != desired) {
            debug { "reevaluateSelfRole: $current → $desired" }
            _role.value = desired
            if (desired is RaftRole.Learner) {
                electionJob?.cancel()   // learners do not participate in elections
                electionJob = null
            } else {
                // Promoted INTO a voting role (Learner → Follower). Arm the election timer now — a
                // freshly-promoted voter that never hears from a leader must be able to start an
                // election, rather than staying passive until the next inbound AppendEntries.
                debug { "reevaluateSelfRole: promoted to voter — arming election timer" }
                resetElectionTimeout()
            }
        }
    }

    /**
     * Append a config log entry to the leader's log (adopt-on-append), replicate it, and
     * try to advance commit. This is `onPropose` specialized for internal config entries:
     * it does NOT touch [pending]/[_committed]/[proposeStartTimes].
     *
     * Called by [onChangeMembership] (learner-set-only Simple entry) and by [onConfigCommitted]
     * (the C_new Simple entry that finalises a Joint after it commits).
     */
    private suspend fun appendConfigEntry(payload: ConfigPayload) {
        val index = lastLogIndex + 1L
        val entry = LogEntry(index, currentTerm, byteArrayOf(), config = payload)
        log += entry
        storage.appendEntries(listOf(entry))
        // recomputeMembership() emits the ConfigChange trace event (unified leader+follower path),
        // so we do not emit it here — doing so would double-emit on the leader.
        recomputeMembership()
        debug { "appendConfigEntry: index=$index payload=$payload membership=$membership" }
        membership.replicationTargets(transport.selfId).forEach { sendAppendEntries(it) }
        tryAdvanceLeaderCommit()
    }

    /**
     * Leader: validate and initiate a membership change request from [changeMembership].
     *
     * A learner-set-only change (voter set unchanged) appends a single `Simple(target)` entry — no
     * quorum shift, so no joint phase. A voter-set change appends a `Joint(old, new)` entry and
     * transitions through §6 joint consensus: dual majorities for commit/election until C_new commits,
     * at which point [onConfigCommitted] appends `Simple(new)` and completes the change. Rejected when
     * not leader, when a change is already in progress, or when the target voter set is empty.
     */
    private suspend fun onChangeMembership(target: ClusterConfig, deferred: CompletableDeferred<ClusterConfig>) {
        if (_role.value !is RaftRole.Leader) {
            debug { "onChangeMembership: rejected — not leader (role=${_role.value})" }
            deferred.completeExceptionally(NotLeaderException())
            return
        }
        if (pendingConfigChange != null) {
            debug { "onChangeMembership: rejected — change already in progress" }
            deferred.completeExceptionally(MembershipChangeInProgressException())
            return
        }
        if (target.voters.isEmpty()) {
            debug { "onChangeMembership: rejected — target voters is empty" }
            deferred.completeExceptionally(IllegalArgumentException("target voter set must not be empty"))
            return
        }
        // A change may only start from a settled Simple config. An in-flight — or orphaned, after a
        // leader crash mid-transition — Joint config means a §6 transition is still converging; reject
        // until C_new commits. This also makes the `current.config` read below total.
        val current = membership
        if (current !is MembershipState.Simple) {
            debug { "onChangeMembership: rejected — joint transition in progress ($current)" }
            deferred.completeExceptionally(MembershipChangeInProgressException())
            return
        }
        pendingConfigChange = deferred
        if (target.voters != current.config.voters) {
            // Voter-set change → §6 joint consensus. Append Joint(old=current, new=target); on its
            // commit, onConfigCommitted appends Simple(C_new) and the transition completes when that
            // entry commits. Commit and election require dual majorities throughout the joint phase.
            debug { "onChangeMembership: accepted — voter-set change to $target via joint consensus (old=${current.config})" }
            appendConfigEntry(ConfigPayload(old = current.config, new = target))
        } else {
            // Learner-set-only change: append a Simple(target) entry, no joint phase needed.
            debug { "onChangeMembership: accepted — learner-set-only change to $target" }
            appendConfigEntry(ConfigPayload(old = null, new = target))
        }
    }

    /**
     * Called by [advanceCommit] after a config entry commits.
     *
     * Joint committed (`payload.old != null`): the C_{old,new} transition is now durable under both
     * majorities — append `Simple(new)` to drive the cluster onto C_new alone. The deferred is NOT
     * completed yet; it completes when that `Simple(new)` entry commits (the branch below).
     *
     * Simple committed: the transition is complete — wake the [changeMembership] caller. §6.4.1: if
     * this leader is not a voter in C_new (removed-leader / leader-replace), step down once C_new is
     * durable, so the new cluster elects a leader from its own membership.
     */
    private suspend fun onConfigCommitted(entry: LogEntry) {
        val payload = entry.config ?: return
        debug { "onConfigCommitted: entry.index=${entry.index} payload=$payload" }
        if (payload.old != null) {
            // Joint committed → append C_new (Simple) to complete the transition — but ONLY if no
            // later config entry has already superseded the Joint. `membership` always reflects the
            // last config entry in the log, so `membership is Joint` is exactly the condition "no
            // Simple(C_new) follows the Joint yet." A new leader that inherits a Joint whose trailing
            // Simple(C_new) is already in its log (the original leader appended it before crashing /
            // stepping down) must NOT append a second C_new: that duplicate carries the new leader's
            // term, diverges from the existing C_new, and wedges replication in an infinite
            // AppendEntries backup loop. Skipping is safe — C_new already exists; the Simple branch
            // will complete the deferred and run the step-down when that existing C_new commits.
            if (membership is MembershipState.Joint) {
                debug { "onConfigCommitted: Joint committed — appending Simple(C_new=${payload.new})" }
                appendConfigEntry(ConfigPayload(old = null, new = payload.new))
            } else {
                debug { "onConfigCommitted: Joint committed but C_new already in log (membership=$membership) — skip duplicate append" }
            }
        } else {
            // Simple committed → transition complete; wake the changeMembership caller.
            val result = ClusterConfig(payload.new.voters, payload.new.learners)
            debug { "onConfigCommitted: Simple committed — completing pendingConfigChange with $result" }
            pendingConfigChange?.complete(result)
            pendingConfigChange = null
            // §6.4.1: if self is not in the new voter set, step down (removed-leader case).
            if (_role.value is RaftRole.Leader && transport.selfId !in payload.new.voters) {
                debug { "onConfigCommitted: self not in new voters — stepping down (RemovedFromConfig)" }
                stepDownToFollower(StepDownReason.RemovedFromConfig)
            }
        }
    }

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        val d = CompletableDeferred<ClusterConfig>()
        try {
            cmd.send(EngineCommand.ChangeMembership(target, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        return d.await()
    }

    // ── §3.10 Leadership transfer ─────────────────────────────────────────────

    /**
     * Validate and initiate a leadership transfer to [target].
     *
     * Rejects immediately when: not leader, target is self, target is not a voter.
     * Once accepted: blocks new proposals, sends AppendEntries to sync target, sends [TimeoutNow],
     * and arms a one-election-timeout timer after which the transfer is auto-abandoned.
     */
    private suspend fun onTransferLeadership(target: NodeId, response: CompletableDeferred<Unit>) {
        if (_role.value !is RaftRole.Leader) {
            response.completeExceptionally(NotLeaderException("transferLeadership: not the current leader"))
            return
        }
        if (target == transport.selfId) {
            response.completeExceptionally(IllegalArgumentException("transferLeadership: target must not be this node (${transport.selfId.value})"))
            return
        }
        val currentVoters = membership.effectiveConfig.voters
        if (target !in currentVoters) {
            response.completeExceptionally(IllegalArgumentException("transferLeadership: target ${target.value} is not a voter in the current config ($currentVoters)"))
            return
        }
        // A second concurrent call while one is already in flight: reject the second.
        if (transferTarget != null) {
            response.completeExceptionally(IllegalStateException("transferLeadership: a transfer to ${transferTarget!!.value} is already in flight"))
            return
        }
        transferTarget = target
        transferDeferred = response
        emitTrace(RaftTraceEvent.LeadershipTransferStarted(nextClock(), transport.selfId, target))
        debug { "onTransferLeadership: transfer started to ${target.value}" }

        // Arm auto-timeout: one election-timeout window.
        transferTimeoutJob = scope.launch {
            delay(raftConfig.electionTimeoutMax.inWholeMilliseconds)
            cmd.trySend(EngineCommand.TransferTimeout)
        }

        // Sync target's log and send TimeoutNow.
        sendTransferSync(target)
    }

    /**
     * Send AppendEntries to bring [target] up to [currentCommitIndex], then send [TimeoutNow].
     *
     * If the target is already up to date (matchIndex == lastLogIndex), skip straight to TimeoutNow.
     * AppendEntries delivery is best-effort — the normal heartbeat loop will keep retrying;
     * we send here to minimise the sync delay before TimeoutNow.
     */
    private suspend fun sendTransferSync(target: NodeId) {
        sendAppendEntries(target)
        // Only send TimeoutNow once the target is caught up (matchIndex >= commitIndex).
        // When the AppendEntries ACK arrives (onAppendEntriesResponse) and the target is caught up,
        // sendTimeoutNowIfReady is called there too. Here we handle the already-caught-up case.
        if ((matchIndex[target] ?: 0L) >= currentCommitIndex) {
            sendTimeoutNow(target)
        }
    }

    /** Send a [RaftMessage.TimeoutNow] to [target]. */
    private suspend fun sendTimeoutNow(target: NodeId) {
        debug { "sendTimeoutNow: sending TimeoutNow to ${target.value} term=$currentTerm" }
        send(target, RaftMessage.TimeoutNow(currentTerm, transport.selfId))
    }

    /**
     * Called from [onAppendEntriesResponse] when a successful ACK arrives during an active transfer.
     * If the target is now caught up to [currentCommitIndex], send [TimeoutNow] to trigger the election.
     */
    private suspend fun sendTimeoutNowIfReady(from: NodeId) {
        val target = transferTarget ?: return
        if (from != target) return
        if ((matchIndex[target] ?: 0L) >= currentCommitIndex) {
            sendTimeoutNow(target)
        }
    }

    /**
     * Auto-timeout fired: the target did not win an election within one election-timeout window.
     * Resume normal operation (re-enable proposals) and fail the transfer deferred.
     */
    private suspend fun onTransferTimeout() {
        val target = transferTarget ?: return   // already resolved — ignore stale timer
        debug { "onTransferTimeout: transfer to ${target.value} timed out — resuming normal operation" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Timeout,
        ))
        failPendingTransfer(LeadershipTransferException("leadership transfer to ${target.value} timed out"))
    }

    /**
     * Explicit cancel from the application: abort the in-flight transfer and resume proposals.
     */
    private suspend fun onCancelTransfer() {
        val target = transferTarget ?: return   // nothing in flight — no-op
        debug { "onCancelTransfer: transfer to ${target.value} cancelled" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Cancelled,
        ))
        failPendingTransfer(LeadershipTransferException("leadership transfer to ${target.value} was cancelled"))
    }

    /**
     * §3.10 TimeoutNow received: immediately start a real election without waiting for election timeout.
     *
     * Only valid when this node is a voting follower (not a leader, candidate, or learner) and only
     * when [from] is the leader we currently recognise. The message must carry the sender's current
     * term — if it's stale, or if a same-term TimeoutNow arrives from a non-leader peer, ignore it.
     *
     * The pre-vote phase is intentionally skipped: the leader already validated this node's log is
     * up-to-date (it just sent AppendEntries to sync us), so the pre-vote safety check is redundant
     * and would only delay the election. We jump straight to a real RequestVote.
     */
    private suspend fun onTimeoutNow(from: NodeId, m: RaftMessage.TimeoutNow) {
        debug { "onTimeoutNow: from=${from.value} term=${m.term} currentTerm=$currentTerm role=${_role.value}" }
        // Ignore if from a stale leader or if we are already leader/candidate.
        if (m.term < currentTerm) {
            debug { "onTimeoutNow: stale term ${m.term} < currentTerm=$currentTerm — ignoring" }
            return
        }
        if (_role.value is RaftRole.Leader || _role.value is RaftRole.Candidate) {
            debug { "onTimeoutNow: already ${_role.value} — ignoring" }
            return
        }
        // Only the current leader may issue TimeoutNow. A stale or spoofed same-term TimeoutNow from
        // a peer that is not the leader we know about must not trigger a spurious election. _leader is
        // only meaningful at the current term: for a strictly-higher term the sender has legitimately
        // advanced past us (we step down below), and _leader is stale, so the check applies only when
        // m.term == currentTerm. _leader may be null before we have heard from any leader this term;
        // in that case accept (the sender asserts current-term leadership, validated by the term guards).
        if (m.term == currentTerm && _leader.value != null && from != _leader.value) {
            debug { "onTimeoutNow: sender ${from.value} is not the current leader (${_leader.value?.value}) — ignoring" }
            return
        }
        // A learner never votes and must never start an election.
        if (_role.value is RaftRole.Learner) {
            debug { "onTimeoutNow: self is a learner — ignoring" }
            return
        }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        // Start a real election immediately (skip pre-vote — we are already up-to-date per the leader's sync).
        debug { "onTimeoutNow: starting immediate election (skipping pre-vote)" }
        startRealElection()
    }

    // ── Message dispatcher ────────────────────────────────────────────────────

    private suspend fun onMessage(from: NodeId, m: RaftMessage) = when (m) {
        is RaftMessage.RequestVote             -> onRequestVote(from, m)
        is RaftMessage.RequestVoteResponse     -> onRequestVoteResponse(from, m)
        is RaftMessage.AppendEntries           -> onAppendEntries(from, m)
        is RaftMessage.AppendEntriesResponse   -> onAppendEntriesResponse(from, m)
        is RaftMessage.InstallSnapshot         -> onInstallSnapshot(from, m)
        is RaftMessage.InstallSnapshotResponse -> onInstallSnapshotResponse(from, m)
        is RaftMessage.PreVote                 -> onPreVote(from, m)
        is RaftMessage.PreVoteResponse         -> onPreVoteResponse(from, m)
        is RaftMessage.TimeoutNow              -> onTimeoutNow(from, m)
    }

    private suspend fun send(peer: NodeId, m: RaftMessage) =
        transport.sendTo(peer, Cbor.encodeToByteArray(m))

    override suspend fun transferLeadership(target: NodeId) {
        val d = CompletableDeferred<Unit>()
        try {
            cmd.send(EngineCommand.TransferLeadership(target, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        d.await()
    }

    override fun cancelTransfer() {
        cmd.trySend(EngineCommand.CancelTransfer)
    }

    override suspend fun close() {
        try { cmd.send(EngineCommand.Close) } catch (_: ClosedSendChannelException) { /* already closed */ }
    }

    private companion object {
        /** Reserve for the CBOR envelope around a chunk's [RaftMessage.InstallSnapshot.data] payload. */
        const val HEADER_BUDGET = 256
    }
}
