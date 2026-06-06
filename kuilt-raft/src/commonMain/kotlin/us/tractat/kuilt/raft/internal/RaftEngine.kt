@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.StepDownReason
import kotlin.random.Random

internal class RaftEngine(
    private val clusterConfig: ClusterConfig,
    private val transport: RaftTransport,
    private val storage: RaftStorage,
    private val raftConfig: RaftConfig,
    private val scope: CoroutineScope,
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

    private var traceClock = 0L
    private val _trace = MutableSharedFlow<RaftTraceEvent>(extraBufferCapacity = 512)
    override val trace: Flow<RaftTraceEvent> = _trace

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
    private var electionJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        scope.launch {
            // Restore persisted state
            currentTerm = storage.term()
            votedFor = storage.votedFor()
            log.addAll(storage.entries())
            // Set initial role
            _role.value = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower
            // Start actor and message subscription
            startActor()
            resetElectionTimeout()
            launch { transport.incoming.collect { cmd.send(EngineCommand.IncomingMessage(it.from, Cbor.decodeFromByteArray(it.bytes))) } }
        }
    }

    private fun startActor() {
        scope.launch {
            for (c in cmd) {
                when (c) {
                    is EngineCommand.IncomingMessage -> onMessage(c.from, c.message)
                    is EngineCommand.Propose         -> onPropose(c.command, c.response)
                    is EngineCommand.ElectionTimeout -> onElectionTimeout()
                    is EngineCommand.HeartbeatTick   -> onHeartbeat()
                    is EngineCommand.Close           -> { cmd.close(); break }
                }
            }
        }
    }

    // ── Trace helper ──────────────────────────────────────────────────────────

    private suspend fun emitTrace(event: RaftTraceEvent) = _trace.emit(event)

    private fun nextClock() = ++traceClock

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
        currentTerm++
        storage.saveTermAndVotedFor(currentTerm, transport.selfId)
        votedFor = transport.selfId
        votesGranted.clear()
        votesGranted += transport.selfId
        _role.value = RaftRole.Candidate
        _leader.value = null
        resetElectionTimeout()
        val last = log.lastOrNull()
        emitTrace(RaftTraceEvent.Timeout(nextClock(), transport.selfId, currentTerm))
        val rv = RaftMessage.RequestVote(currentTerm, transport.selfId, last?.index ?: 0L, last?.term ?: 0L)
        clusterConfig.voters.filter { it != transport.selfId }.forEach { peer ->
            emitTrace(RaftTraceEvent.RequestVote(nextClock(), transport.selfId, peer, currentTerm, last?.index ?: 0L, last?.term ?: 0L))
            send(peer, rv)
        }
    }

    private suspend fun onRequestVote(from: NodeId, m: RaftMessage.RequestVote) {
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        val last = log.lastOrNull()
        val logOk = m.lastLogTerm > (last?.term ?: 0L) ||
            (m.lastLogTerm == (last?.term ?: 0L) && m.lastLogIndex >= (last?.index ?: 0L))
        val grant = m.term == currentTerm && logOk && (votedFor == null || votedFor == m.candidateId)
        if (grant) {
            storage.saveVotedFor(m.candidateId)
            votedFor = m.candidateId
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
        _role.value = RaftRole.Leader
        _leader.value = transport.selfId
        electionJob?.cancel()
        val nextIdx = (log.lastOrNull()?.index ?: 0L) + 1L
        clusterConfig.allMembers.filter { it != transport.selfId }.forEach { p ->
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
        val noOpIndex = (log.lastOrNull()?.index ?: 0L) + 1L
        val noOp = LogEntry(noOpIndex, currentTerm, byteArrayOf())
        log += noOp
        storage.appendEntries(listOf(noOp))
        clusterConfig.allMembers.filter { it != transport.selfId }.forEach { sendAppendEntries(it) }
    }

    private suspend fun stepDown(newTerm: Long, reason: StepDownReason) {
        currentTerm = newTerm
        storage.saveTermAndVotedFor(newTerm, null)
        votedFor = null
        if (_role.value is RaftRole.Leader) {
            heartbeatJob?.cancel()
            pending.forEach { (_, d) -> d.completeExceptionally(LeadershipLostException()) }
            pending.clear()
        }
        _role.value = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower
        _leader.value = null
        emitTrace(RaftTraceEvent.BecomeFollower(nextClock(), transport.selfId, currentTerm, reason))
        resetElectionTimeout()
    }

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
        clusterConfig.allMembers.filter { it != transport.selfId }.forEach { sendAppendEntries(it) }
    }

    private suspend fun sendAppendEntries(peer: NodeId) {
        val ni = nextIndex[peer] ?: 1L
        val prev = log.firstOrNull { it.index == ni - 1L }
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
        _role.value = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower
        _leader.value = m.leaderId
        resetElectionTimeout()

        // Log consistency check
        if (m.prevLogIndex > 0L) {
            val prev = log.firstOrNull { it.index == m.prevLogIndex }
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
            advanceCommit(minOf(m.leaderCommit, log.lastOrNull()?.index ?: 0L))
        }

        val acceptedMatchIndex = log.lastOrNull()?.index ?: 0L
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
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        if (m.success) {
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, m.matchIndex)
            nextIndex[from] = matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            nextIndex[from] = computeNextIndexAfterFailure(from, m)
            sendAppendEntries(from)
        }
    }

    private suspend fun tryAdvanceLeaderCommit() {
        // Find highest N > currentCommitIndex where a majority have matchIndex >= N and log[N].term == currentTerm
        val voterMatches = clusterConfig.voters
            .filter { it != transport.selfId }
            .mapNotNull { matchIndex[it] }
            .sortedDescending()
        val quorum = clusterConfig.quorumSize - 1 // leader counts itself
        if (voterMatches.size >= quorum) {
            val majorityIdx = voterMatches[quorum - 1]
            val entry = log.firstOrNull { it.index == majorityIdx }
            if (entry != null && entry.term == currentTerm && majorityIdx > currentCommitIndex) {
                advanceCommit(majorityIdx)
            }
        }
    }

    private fun computeNextIndexAfterFailure(peer: NodeId, m: RaftMessage.AppendEntriesResponse): Long {
        val current = nextIndex[peer] ?: 1L
        if (m.conflictTerm != null) {
            val lastOfTerm = log.lastOrNull { it.term == m.conflictTerm }
            return if (lastOfTerm != null) lastOfTerm.index + 1L
                   else m.conflictIndex ?: maxOf(1L, current - 1L)
        }
        return m.conflictIndex ?: maxOf(1L, current - 1L)
    }

    private suspend fun advanceCommit(newCommit: Long) {
        val oldCommit = currentCommitIndex
        for (idx in (currentCommitIndex + 1)..newCommit) {
            val entry = log.firstOrNull { it.index == idx } ?: continue
            _committed.emit(entry)
            _commitIndex.value = idx
            pending.removeAll { (i, d) -> if (i == idx) { d.complete(entry); true } else false }
        }
        currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
    }

    // ── propose() ─────────────────────────────────────────────────────────────

    private suspend fun onPropose(command: ByteArray, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            response.completeExceptionally(NotLeaderException())
            return
        }
        val index = (log.lastOrNull()?.index ?: 0L) + 1L
        val entry = LogEntry(index, currentTerm, command)
        log += entry
        storage.appendEntries(listOf(entry))
        emitTrace(RaftTraceEvent.ClientRequest(nextClock(), transport.selfId, index, currentTerm))
        pending += index to response
        clusterConfig.allMembers.filter { it != transport.selfId }.forEach { sendAppendEntries(it) }
    }

    override suspend fun propose(command: ByteArray): LogEntry {
        val d = CompletableDeferred<LogEntry>()
        cmd.send(EngineCommand.Propose(command, d))
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

    override suspend fun close() { cmd.send(EngineCommand.Close) }
}
