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
     * Emits every committed [LogEntry] in index order. The overflow policy is [BufferOverflow.SUSPEND]
     * so the actor backpressures rather than silently dropping entries. Callers that fall behind will
     * slow the cluster — this is the correct trade-off for a consensus log where every entry must be
     * delivered.
     */
    private val _committed = MutableSharedFlow<LogEntry>(
        extraBufferCapacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val committed: Flow<LogEntry> = _committed

    override fun committedFrom(fromIndex: Long): Flow<LogEntry> = flow {
        coroutineScope {
            // Subscribe to the live tail BEFORE the actor captures the cut. UNDISPATCHED
            // runs the collector synchronously up to its first suspension (subscriber
            // registration), so by the time we send CommitCut we're guaranteed registered
            // — no entry committed after the cut can slip through the gap.
            val buffer = Channel<LogEntry>(Channel.UNLIMITED)
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
            cut.replay.forEach { emit(it) }
            // Tail live, deduped against the replayed prefix. Entries with index <= cutIndex
            // were already replayed from the snapshot; no-ops never surface.
            for (entry in buffer) {
                if (entry.index > cut.cutIndex && !entry.isNoOp) emit(entry)
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

    // Candidate state
    private val votesGranted = mutableSetOf<NodeId>()

    // Leader state
    private val nextIndex = mutableMapOf<NodeId, Long>()
    private val matchIndex = mutableMapOf<NodeId, Long>()
    private val pending = mutableListOf<Pair<Long, CompletableDeferred<LogEntry>>>()

    // Timer jobs (cancelled/restarted by actor)
    // timer jobs are children of scope and die with it; Close only stops the actor loop
    private var electionJob: Job? = null
    private var heartbeatJob: Job? = null

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
                        is EngineCommand.CommitCut       -> onCommitCut(c)
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

    private val lastLogIndex: Long get() = log.lastOrNull()?.index ?: 0L

    private val lastLogTerm: Long get() = log.lastOrNull()?.term ?: 0L

    // ── Trace helper ──────────────────────────────────────────────────────────

    private suspend fun emitTrace(event: RaftTraceEvent) = _trace.emit(event)

    private fun nextClock() = ++traceClock

    // ── Metric helper ─────────────────────────────────────────────────────────

    /** Emit [metric] to the [onMetric] hook (no-op when null). */
    private fun emitMetric(metric: RaftMetric) = onMetric?.invoke(metric)

    // ── Timers ────────────────────────────────────────────────────────────────

    private fun resetElectionTimeout() {
        electionJob?.cancel()
        if (_role.value is RaftRole.Learner) return
        electionJob = scope.launch {
            delay(
                Random.nextLong(
                    raftConfig.electionTimeoutMin.inWholeMilliseconds,
                    raftConfig.electionTimeoutMax.inWholeMilliseconds,
                )
            )
            cmd.trySend(EngineCommand.ElectionTimeout)
        }
    }

    // ── Election ──────────────────────────────────────────────────────────────

    private suspend fun onElectionTimeout() {
        if (_role.value is RaftRole.Leader) return
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

    private suspend fun becomeLeader() {
        val elapsed = electionStartTime?.elapsedNow() ?: Duration.ZERO
        electionStartTime = null
        emitMetric(RaftMetric.ElectionWon(currentTerm, elapsed))
        logger.debug { "[raft:${transport.selfId}] won election for term $currentTerm in ${elapsed.inWholeMilliseconds}ms" }

        _role.value = RaftRole.Leader
        _leader.value = transport.selfId
        electionJob?.cancel()
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
        // higher term: adopt it, then continue processing this message in the new term
        persistTermAndVote(newTerm, null)
        if (_role.value is RaftRole.Leader) {
            heartbeatJob?.cancel()
            failPending(LeadershipLostException())
        }
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

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
        otherMembers.forEach { sendAppendEntries(it) }
    }

    private suspend fun sendAppendEntries(peer: NodeId) {
        val ni = nextIndex[peer] ?: 1L
        val prev = entryAt(ni - 1L)
        val entries = log.filter { it.index >= ni }
        emitTrace(
            RaftTraceEvent.AppendEntries(
                clock = nextClock(),
                from = transport.selfId,
                to = peer,
                term = currentTerm,
                prevLogIndex = prev?.index ?: 0L,
                prevLogTerm = prev?.term ?: 0L,
                entryCount = entries.size,
                leaderCommit = currentCommitIndex,
            )
        )
        send(
            peer,
            RaftMessage.AppendEntries(
                term = currentTerm,
                leaderId = transport.selfId,
                prevLogIndex = prev?.index ?: 0L,
                prevLogTerm = prev?.term ?: 0L,
                entries = entries,
                leaderCommit = currentCommitIndex,
            )
        )
    }

    private suspend fun onAppendEntries(from: NodeId, m: RaftMessage.AppendEntries) {
        if (m.term < currentTerm) {
            send(from, RaftMessage.AppendEntriesResponse(currentTerm, false))
            return
        }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        // higher term: already adopted it via stepDown above, continue processing in new term
        _role.value = followerRole
        _leader.value = m.leaderId
        resetElectionTimeout()

        // Log consistency check
        if (m.prevLogIndex > 0L) {
            val prev = entryAt(m.prevLogIndex)
            if (prev == null || prev.term != m.prevLogTerm) {
                // §5.3 fast backup: report conflict info
                val conflictTerm = prev?.term ?: log.lastOrNull { it.index <= m.prevLogIndex }?.term
                val conflictIndex = conflictTerm?.let { t -> log.firstOrNull { it.term == t }?.index }
                val resolvedConflictIndex = conflictIndex ?: m.prevLogIndex
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
        if (m.success) {
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, m.matchIndex)
            nextIndex[from] = matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            nextIndex[from] = nextIndexAfterFailure(nextIndex[from] ?: 1L, m, log)
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
                _committed.emit(entry)
                emitProposeApplied(entry)
            }
            emitProposeCommitted(idx)
            _commitIndex.value = idx
            pending.removeAll { (i, d) -> if (i == idx) { d.complete(entry); true } else false }
        }
        currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
    }

    /** Emit [RaftMetric.ProposeCommitted] for [logIndex] if we have a start time recorded. */
    private fun emitProposeCommitted(logIndex: Long) {
        val startMark = proposeStartTimes[logIndex] ?: return
        val elapsed = startMark.elapsedNow()
        emitMetric(RaftMetric.ProposeCommitted(logIndex, elapsed))
    }

    /**
     * Emit [RaftMetric.ProposeApplied] for [entry]. Logs at warn when elapsed exceeds
     * [RaftConfig.slowProposeThreshold]; debug otherwise. Cleans up the start-time map entry.
     */
    private fun emitProposeApplied(entry: LogEntry) {
        val startMark = proposeStartTimes.remove(entry.index) ?: return
        val elapsed = startMark.elapsedNow()
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
     */
    private fun onCommitCut(c: EngineCommand.CommitCut) {
        val replay = log.filter { it.index in c.fromIndex..currentCommitIndex && !it.isNoOp }
        c.response.complete(CommitCutResult(replay, currentCommitIndex))
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
        is RaftMessage.RequestVote           -> onRequestVote(from, m)
        is RaftMessage.RequestVoteResponse   -> onRequestVoteResponse(from, m)
        is RaftMessage.AppendEntries         -> onAppendEntries(from, m)
        is RaftMessage.AppendEntriesResponse -> onAppendEntriesResponse(from, m)
    }

    private suspend fun send(peer: NodeId, m: RaftMessage) =
        transport.sendTo(peer, Cbor.encodeToByteArray(m))

    override suspend fun close() {
        try { cmd.send(EngineCommand.Close) } catch (_: ClosedSendChannelException) { /* already closed */ }
    }
}
