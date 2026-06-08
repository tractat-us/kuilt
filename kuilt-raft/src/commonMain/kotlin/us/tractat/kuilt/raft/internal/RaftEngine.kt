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
import us.tractat.kuilt.raft.DenyReason
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("us.tractat.kuilt.raft.RaftEngine")

internal class RaftEngine(
    private val clusterConfig: ClusterConfig,
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

    // ── Peer-set helpers ─────────────────────────────────────────────────────

    /** Voters other than self — the RequestVote recipients. */
    private val otherVoters: List<NodeId>
        get() = clusterConfig.voters.filter { it != transport.selfId }

    /** All members (voters + learners) other than self — the AppendEntries recipients. */
    private val otherMembers: List<NodeId>
        get() = clusterConfig.allMembers.filter { it != transport.selfId }

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

    // Leader-lease state: true while this node has heard from a live leader recently enough
    // that triggering an election would be disruptive. Cleared on stepDown and after electionTimeoutMin.
    private var leaderAlive = false
    private var leaderLeaseJob: Job? = null

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
                _compactionFloor.value = snapshotIndex
                if (currentCommitIndex < snapshotIndex) {
                    currentCommitIndex = snapshotIndex
                    _commitIndex.value = snapshotIndex
                }
            }
            // Set initial role
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
                        is EngineCommand.IncomingMessage -> onMessage(c.from, c.message)
                        is EngineCommand.Propose         -> onPropose(c.command, c.response)
                        is EngineCommand.ElectionTimeout -> onElectionTimeout()
                        is EngineCommand.HeartbeatTick   -> onHeartbeat()
                        is EngineCommand.LeaseExpired    -> { leaderAlive = false }
                        is EngineCommand.Compact         -> onCompact()
                        is EngineCommand.CommitCut       -> onCommitCut(c)
                        is EngineCommand.QuorumCheck     -> onQuorumCheck()
                        is EngineCommand.Close           -> { cmd.close(); break }
                    }
                }
            } finally {
                // Complete any in-flight proposals so their callers don't hang.
                failPending(LeadershipLostException("node scope cancelled"))
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

    // ── Role helper ───────────────────────────────────────────────────────────

    /**
     * The non-leader role for this node: [RaftRole.Learner] if the node is configured as a
     * learner, [RaftRole.Follower] otherwise. Learners replicate the log but never vote, so
     * they must never be promoted to Follower.
     */
    private val followerRole: RaftRole
        get() = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower

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

    private fun randomElectionTimeoutMillis(): Long = Random.nextLong(
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
        // Single-voter: self pre-vote already satisfies quorum — skip the probe round.
        if (preVotesGranted.size >= clusterConfig.quorumSize) { startRealElection(); return }
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
        if (votesGranted.size >= clusterConfig.quorumSize) { becomeLeader(); return }
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
        if (leaderAlive && m.term > currentTerm) {
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
            if (votesGranted.size >= clusterConfig.quorumSize) becomeLeader()
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
            if (preVotesGranted.size >= clusterConfig.quorumSize) startRealElection()
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
        // §5.4.2: append a no-op from the new term so the commit guard (entry.term == currentTerm)
        // can advance commitIndex over any prior-term entries inherited from a previous leader.
        appendNoOp()
    }

    private suspend fun appendNoOp() {
        val noOpIndex = lastLogIndex + 1L
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
     */
    private suspend fun relinquishToFollower(reason: StepDownReason) {
        if (_role.value is RaftRole.Leader) {
            heartbeatJob?.cancel()
            quorumCheckJob?.cancel()
            failPending(LeadershipLostException())
            snapshotXfer.clear()   // leader-only transfer state — abandon any in-flight snapshot sends
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
        val voterContacts = recentVoterContacts.count { it in clusterConfig.voters }
        recentVoterContacts.clear()
        val reachable = voterContacts + 1   // +1 for self: a leader always counts itself
        if (reachable < clusterConfig.quorumSize) stepDownToFollower(StepDownReason.LostQuorum)
    }

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
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
            )
        )
    }

    /** Leader: advance or finish a snapshot transfer in response to a follower's ack. */
    private suspend fun onInstallSnapshotResponse(from: NodeId, m: RaftMessage.InstallSnapshotResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        recentVoterContacts += from   // reachability signal for CheckQuorum
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

        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm)
        val r = if (m.offset == 0L) SnapshotReassembly(meta).also { incomingSnapshot = it } else incomingSnapshot
        // Out-of-order or stale chunk: re-advertise the offset we actually hold and wait for a resend.
        if (r == null || r.meta != meta || m.offset != r.buffer.size.toLong()) {
            val have = if (r?.meta == meta) r.buffer.size.toLong() else 0L
            if (have == 0L) incomingSnapshot = null
            debug { "onInstallSnapshot($from): out-of-order offset=${m.offset} (have=$have) → re-advertise, await resend" }
            send(from, RaftMessage.InstallSnapshotResponse(currentTerm, have))
            return
        }
        r.buffer.addAll(m.data.asList())

        if (!m.done) {
            debug { "onInstallSnapshot($from): chunk offset=${m.offset} accepted (have=${r.buffer.size}), await more" }
            send(from, RaftMessage.InstallSnapshotResponse(currentTerm, r.buffer.size.toLong()))
            return
        }
        finalizeInstalledSnapshot(from, m, r.buffer.toByteArray())
    }

    /** Persist + apply a fully-reassembled snapshot, reset the log around it, and emit the install. */
    private suspend fun finalizeInstalledSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot, bytes: ByteArray) {
        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm)
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
        _committed.emit(Committed.Install(Snapshot(m.lastIncludedIndex, bytes)))
        incomingSnapshot = null
        debug { "finalizeInstalledSnapshot($from): INSTALLED through=${m.lastIncludedIndex} term=${m.lastIncludedTerm} commit=$currentCommitIndex logTail=${log.firstOrNull()?.index}..${log.lastOrNull()?.index}" }
        emitTrace(RaftTraceEvent.InstallSnapshotAccepted(nextClock(), from, transport.selfId, m.lastIncludedIndex))
        send(from, RaftMessage.InstallSnapshotResponse(currentTerm, bytes.size.toLong()))
    }

    private suspend fun onAppendEntries(from: NodeId, m: RaftMessage.AppendEntries) {
        if (m.term < currentTerm) {
            send(from, RaftMessage.AppendEntriesResponse(currentTerm, false))
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
            }
            val toAdd = m.entries.filter { new -> log.none { it.index == new.index } }
            log.addAll(toAdd)
            storage.appendEntries(toAdd)
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
        send(from, RaftMessage.AppendEntriesResponse(currentTerm, true, acceptedMatchIndex))
    }

    private suspend fun onAppendEntriesResponse(from: NodeId, m: RaftMessage.AppendEntriesResponse) {
        // stale-term peer response: step down and discard
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        recentVoterContacts += from   // reachability signal for CheckQuorum (success or failure)
        if (m.success) {
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, m.matchIndex)
            nextIndex[from] = matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            nextIndex[from] = nextIndexAfterFailure(nextIndex[from] ?: 1L, m, log)
            debug { "onAppendEntriesResponse($from): REJECTED → backup nextIndex=${nextIndex[from]} (snapshotIndex=$snapshotIndex), resend" }
            sendAppendEntries(from)
        }
    }

    private suspend fun tryAdvanceLeaderCommit() {
        // Find highest N > currentCommitIndex where a majority have matchIndex >= N and log[N].term == currentTerm.
        // quorum - 1 is the number of *peer* votes needed (leader always counts itself).
        val peerQuorum = clusterConfig.quorumSize - 1
        // voters only — learners replicate but never count toward commit
        val voterMatchIndices = otherVoters.mapNotNull { matchIndex[it] }
        val majorityIdx = majorityCommitIndex(voterMatchIndices, peerQuorum, lastLogIndex) ?: return
        val entry = entryAt(majorityIdx)
        if (entry != null && entry.term == currentTerm && majorityIdx > currentCommitIndex) {
            advanceCommit(majorityIdx)
        }
    }

    private suspend fun advanceCommit(newCommit: Long) {
        val oldCommit = currentCommitIndex
        for (idx in (currentCommitIndex + 1)..newCommit) {
            val entry = entryAt(idx) ?: continue
            // §5.4.2 no-ops are internal: they advance commitIndex but are withheld from the
            // application-facing committed flow (#136). emit before bumping commitIndex
            // StateFlow — deliberate; do not reorder.
            if (!entry.isNoOp) {
                emitProposeCommittedAndApplied(entry)
                _committed.emit(Committed.Entry(entry))
            }
            _commitIndex.value = idx
            pending.removeAll { (i, d) -> if (i == idx) { d.complete(entry); true } else false }
        }
        currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
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
        storage.saveSnapshot(SnapshotMeta(s.throughIndex, term), s.state)   // durable FIRST
        storage.discardLogPrefix(s.throughIndex)                             // then drop prefix
        log.removeAll { it.index <= s.throughIndex }
        snapshotIndex = s.throughIndex
        snapshotTerm = term
        _compactionFloor.value = snapshotIndex
        emitTrace(RaftTraceEvent.Compacted(nextClock(), transport.selfId, snapshotIndex, snapshotTerm))
    }

    // ── propose() ─────────────────────────────────────────────────────────────

    private suspend fun onPropose(command: ByteArray, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            response.completeExceptionally(NotLeaderException())
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
    }

    private suspend fun send(peer: NodeId, m: RaftMessage) =
        transport.sendTo(peer, Cbor.encodeToByteArray(m))

    override suspend fun close() {
        try { cmd.send(EngineCommand.Close) } catch (_: ClosedSendChannelException) { /* already closed */ }
    }

    private companion object {
        /** Reserve for the CBOR envelope around a chunk's [RaftMessage.InstallSnapshot.data] payload. */
        const val HEADER_BUDGET = 256
    }
}
