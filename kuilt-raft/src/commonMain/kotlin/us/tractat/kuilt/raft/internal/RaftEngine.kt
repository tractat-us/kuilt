@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
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
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientIdCollisionException
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.DedupKey
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
    identity: ClientIdentity = ClientIdentity.Auto,
) : RaftNode {

    // ── Raft §8 client-serial dedup ───────────────────────────────────────────
    /** Whether the caller supplied a stable durable id (collision ⇒ fail loud) vs an auto id (re-mint). */
    private val isDurableId: Boolean = identity is ClientIdentity.Durable

    /** This node's dedup identity. `var` because an auto id re-mints on a detected collision. */
    private var myClientId: ClientId = when (identity) {
        is ClientIdentity.Durable -> identity.clientId
        ClientIdentity.Auto -> ClientId.auto(transport.selfId, raftConfig.random)
    }

    /** Monotonic per-client serial for the auto [propose] form. Confined to the actor loop. */
    private var serial: Long = 0L

    /** Best-effort, non-durable leader-side dedup of recently-committed proposals. */
    private val dedupCache = LeaderDedupCache()

    /** Detects another live writer committing under [myClientId]. Replaced on auto-id re-mint. */
    private var collisions = CollisionDetector(myClientId)

    private val cmd = Channel<EngineCommand>(Channel.UNLIMITED)

    private val _role = MutableStateFlow<RaftRole>(RaftRole.Follower)
    override val role: StateFlow<RaftRole> = _role.asStateFlow()

    private val _leader = MutableStateFlow<NodeId?>(null)
    override val leader: StateFlow<NodeId?> = _leader.asStateFlow()

    private val _commitIndex = MutableStateFlow(0L)
    override val commitIndex: StateFlow<Long> = _commitIndex.asStateFlow()

    private val _membership = MutableStateFlow(bootstrapConfig)
    override val membership: StateFlow<ClusterConfig> = _membership.asStateFlow()

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

    // ── Shared consensus core (see [RaftState]) ──────────────────────────────
    // `currentTerm`/`votedFor`/`log`/`currentCommitIndex`/`snapshotIndex`/`snapshotTerm`/
    // `snapshotConfig`/`membershipState`/`nextIndex`/`matchIndex` and the derived log helpers
    // now live on `state`, declared just before the `init` block (below).
    // `membershipState` is named to avoid shadowing the `membership: StateFlow<ClusterConfig>` override.

    // ── Peer-set helpers ─────────────────────────────────────────────────────

    /**
     * Voters other than self — the RequestVote recipients.
     * Joint: union of both voter sets minus self.
     */
    private val otherVoters: Set<NodeId>
        get() = state.membershipState.electionTargets(transport.selfId)

    /**
     * All members (voters + learners) other than self — the AppendEntries recipients.
     * Joint: union of all members from both configs minus self.
     */
    private val otherMembers: Set<NodeId>
        get() = state.membershipState.replicationTargets(transport.selfId)

    // ── Actor-only mutable state ──────────────────────────────────────────────

    // Candidate state
    private val votesGranted = mutableSetOf<NodeId>()

    // Pre-vote probe state — set while a pre-vote round is in flight, null otherwise
    private var preVoteTerm: Long? = null
    private var preVoteRound: Long = 0L
    private val preVotesGranted = mutableSetOf<NodeId>()

    // Leader state
    private val pending = mutableListOf<Pair<Long, CompletableDeferred<LogEntry>>>()

    /**
     * At most one membershipState change in flight at a time (one-change-at-a-time rule).
     * Completed when C_new commits; failed on any leadership relinquish or close.
     * Not on [pending] — config entries are internal, withheld from [RaftNode.committed].
     */
    private var pendingConfigChange: CompletableDeferred<ClusterConfig>? = null

    // §7 InstallSnapshot transfer — leader-only chunked send, extracted to SnapshotSender (one chunk in
    // flight per peer; await-ack-then-next). Declared before `init` per the machine-field ordering rule
    // (#1077). chunkBytes stays in the engine (it reads transport/raftConfig); the machine's only
    // side-effect is loading the stored snapshot bytes.
    private val snapshotSender = SnapshotSender(storage, ::chunkBytes)

    // §7 InstallSnapshot reassembly state — follower-only (in-order chunk accumulation).
    private val snapshotReceiver = SnapshotReceiver()

    // Timer jobs (cancelled/restarted by actor)
    // timer jobs are children of scope and die with it; Close only stops the actor loop
    private var electionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var quorumCheckJob: Job? = null

    // CheckQuorum: voters (other than self) from whom any response arrived in the current window.
    // Reset each tick. Leader-only.
    private val recentVoterContacts = mutableSetOf<NodeId>()

    // §6.4 ReadIndex state — leader-only, extracted to ReadIndexTracker. It owns the pending-read queue,
    // the per-voter last-ACK round, the heartbeat round nonce (the engine stamps sends via
    // readIndexTracker.round and bumps it in onHeartbeat), the current-term no-op gate, and the
    // freshness/quorum arithmetic; the engine keeps all send/trace/deferred.complete side-effects at the
    // call site. Reset on becomeLeader; failed on relinquishToFollower. Declared before `init` per the
    // machine-field ordering rule (#1077) — the actor's teardown calls readIndexTracker.failAll(...).
    private val readIndexTracker = ReadIndexTracker()

    // Leader-lease state: true while this node has heard from a live leader recently enough
    // that triggering an election would be disruptive. Cleared on stepDown and after electionTimeoutMin.
    private var leaderAlive = false
    private var leaderLeaseJob: Job? = null

    // §3.10 Leadership transfer state — leader-only, extracted to LeadershipTransferMachine. It owns the
    // tri-state (target/deferred/auto-timeout-job) as one all-or-none InFlight record. Cleared on
    // becomeLeader (reset) and relinquishToFollower (onLeadershipRelinquished). Declared before `init` per
    // the machine-field ordering rule (#1077) — the actor's teardown calls transfer.fail(...). The machine
    // launches only the auto-timeout timer, which re-enters the actor via cmd.trySend(TransferTimeout).
    private val transfer = LeadershipTransferMachine(
        scope = scope,
        raftConfig = raftConfig,
        signalTimeout = { cmd.trySend(EngineCommand.TransferTimeout) },
    )

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

    // ── Client-proposal forwarding state (§8) — actor-teardown-touched ─────────
    // MUST stay declared BEFORE the `init` block below (which launches the actor).
    // The actor's `finally` teardown calls forwarder.failAll(...), which dereferences
    // the machine's maps; when the scope is cancelled during construction that teardown
    // can run before the constructor reaches a declaration placed after `init` → NPE.
    // Keep alongside the other pending-state fields the teardown touches
    // (pending/pendingConfigChange/readIndexTracker/transfer*). See #1077.
    //
    // Extracted to ProposalForwarder (§8): it owns the outstanding forwards awaiting a
    // ForwardResponse, the ones parked while no leader is known, and the monotonic
    // correlation nonce; the engine keeps all send/deferred.complete side-effects at the
    // call site. Drained by flushWaitingForLeader after every non-Close command; failed on
    // actor teardown.
    private val forwarder = ProposalForwarder()

    /**
     * The shared consensus core — term/vote, log, commit/snapshot boundary, membership, and the
     * leader-side replication indices. Declared BEFORE the `init` block (#1077): the actor's
     * teardown and the init-restore coroutine both dereference these fields, so `state` must be
     * initialized before the launch below.
     */
    private val state = RaftState(bootstrapConfig)

    init {
        scope.launch {
            // Restore persisted state
            state.currentTerm = storage.term()
            state.votedFor = storage.votedFor()
            // Recover the snapshot baseline FIRST: a persisted snapshot is by definition committed, so
            // seed snapshotIndex/Term, the compaction floor, and commitIndex from it. This must happen
            // BEFORE the log load so `snapshotIndex` is known when we filter the persisted entries.
            storage.loadSnapshot()?.let { stored ->
                state.snapshotIndex = stored.meta.lastIncludedIndex
                state.snapshotTerm = stored.meta.lastIncludedTerm
                // Seed the membershipState baseline from the snapshot so a node that crashed after compacting
                // past a config change recovers under that change (the config entry is gone from the log).
                state.snapshotConfig = stored.meta.config
                _compactionFloor.value = state.snapshotIndex
                if (state.currentCommitIndex < state.snapshotIndex) {
                    state.currentCommitIndex = state.snapshotIndex
                    _commitIndex.value = state.snapshotIndex
                }
            }
            // Load only entries ABOVE the snapshot floor. `saveSnapshot` is durable-before-discardLogPrefix
            // (#1221): a crash in that window leaves storage holding the snapshot AND the un-discarded prefix
            // (entries with index <= snapshotIndex). Loading `entries()` unfiltered would put the compacted
            // prefix into the in-memory log, and the positional log math (RaftLogMath: log[index - snapshotIndex
            // - 1], which assumes the list begins at snapshotIndex + 1) would then silently return the wrong
            // entry for every lookup. Filtering here restores the invariant regardless of the crash window.
            state.log.addAll(storage.entries(state.snapshotIndex + 1))
            // Recompute effective membershipState from the recovered log + snapshot (restart recovery).
            // This is load-bearing: a node that crashed mid-transition comes back under exactly
            // the config its durable log justifies — no special restart path needed.
            recomputeMembership()
            // Set initial role (consults membershipState.isLearner, so must run after recomputeMembership)
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
                    val closing = c is EngineCommand.Close
                    when (c) {
                        is EngineCommand.IncomingMessage  -> onMessage(c.from, c.message)
                        is EngineCommand.Propose          -> onLocalPropose(c.command, c.requestId, c.response)
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
                    if (!closing) flushWaitingForLeader()
                }
            } finally {
                // Complete any in-flight proposals and config changes so their callers don't hang.
                val cause = LeadershipLostException("node scope cancelled")
                failPending(cause)
                failPendingConfigChange(cause)
                readIndexTracker.failAll(cause)
                transfer.fail(LeadershipTransferException("node scope cancelled"))
                forwarder.failAll(cause)
            }
        }
    }

    // ── Persistence choke-points ──────────────────────────────────────────────

    /** Persist term+vote durably, THEN update in-memory — uniform crash-consistent ordering. */
    private suspend fun persistTermAndVote(term: Long, vote: NodeId?) {
        storage.saveTermAndVotedFor(term, vote)
        state.currentTerm = term
        state.votedFor = vote
    }

    /** Persist a vote grant durably, then in-memory (term unchanged). */
    private suspend fun persistVote(vote: NodeId?) {
        storage.saveVotedFor(vote)
        state.votedFor = vote
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

    // ── Role helper ───────────────────────────────────────────────────────────

    /**
     * The non-leader role for this node: [RaftRole.Learner] if the effective membershipState
     * classifies self as a learner, [RaftRole.Follower] otherwise. Learners replicate the log
     * but never vote, so they must never be promoted to Follower.
     *
     * Consults [membershipState] (not the static bootstrapConfig) so a node whose role changed
     * via a config log entry correctly reflects its new classification.
     */
    private val followerRole: RaftRole
        get() = if (state.membershipState.isLearner(transport.selfId)) RaftRole.Learner else RaftRole.Follower

    // ── Log helpers ───────────────────────────────────────────────────────────
    // `entryAt`/`lastLogIndex`/`lastLogTerm`/`termAt` now live on [RaftState] (`state.*`) —
    // pure functions of the moved log/snapshot fields.

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
        val proposed = state.currentTerm + 1
        preVoteTerm = proposed
        preVoteRound++
        preVotesGranted.clear()
        preVotesGranted += transport.selfId
        resetElectionTimeout()
        // Single-voter (or all other voters already granted): self pre-vote satisfies quorum — skip probe.
        if (state.membershipState.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) { startRealElection(); return }
        emitTrace(RaftTraceEvent.PreVoteStarted(nextClock(), transport.selfId, proposed))
        val pv = RaftMessage.PreVote(proposed, transport.selfId, state.lastLogIndex, state.lastLogTerm, preVoteRound)
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
        persistTermAndVote(state.currentTerm + 1, transport.selfId)
        votesGranted.clear()
        votesGranted += transport.selfId
        _role.value = RaftRole.Candidate
        _leader.value = null
        resetElectionTimeout()
        // Record the election start time and emit the metric.
        electionStartTime = TimeSource.Monotonic.markNow()
        electionStartTerm = state.currentTerm
        emitMetric(RaftMetric.ElectionStarted(state.currentTerm))
        logger.debug { "[raft:${transport.selfId}] election started for term ${state.currentTerm}" }
        // Single-voter cluster: self-vote already satisfies quorum — become leader immediately.
        if (state.membershipState.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) { becomeLeader(); return }
        emitTrace(RaftTraceEvent.Timeout(nextClock(), transport.selfId, state.currentTerm))
        val rv = RaftMessage.RequestVote(state.currentTerm, transport.selfId, state.lastLogIndex, state.lastLogTerm)
        otherVoters.forEach { peer ->
            emitTrace(RaftTraceEvent.RequestVote(nextClock(), transport.selfId, peer, state.currentTerm, state.lastLogIndex, state.lastLogTerm))
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
        val isTransferCandidate = _role.value is RaftRole.Leader && transfer.inFlightTarget == from
        if (!isTransferCandidate && leaderAlive && m.term > state.currentTerm) {
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, DenyReason.LeaderAlive))
            send(from, RaftMessage.RequestVoteResponse(state.currentTerm, false))
            return
        }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        val logOk = isLogUpToDate(state.log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
        val grant = m.term == state.currentTerm && logOk && (state.votedFor == null || state.votedFor == m.candidateId)
        if (grant) {
            persistVote(m.candidateId)
            resetElectionTimeout()
            emitTrace(RaftTraceEvent.VoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = when {
                m.term < state.currentTerm -> DenyReason.StaleTerm
                state.votedFor != null && state.votedFor != m.candidateId -> DenyReason.AlreadyVoted
                else -> DenyReason.LogNotUpToDate
            }
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.RequestVoteResponse(state.currentTerm, grant))
    }

    private suspend fun onRequestVoteResponse(from: NodeId, m: RaftMessage.RequestVoteResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Candidate || m.term != state.currentTerm) return
        if (m.voteGranted) {
            votesGranted += from
            if (state.membershipState.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) becomeLeader()
        }
    }

    /**
     * Respond to a pre-vote request: grant iff the proposed term is higher than ours, the
     * candidate's log is at least as up-to-date, and we have not heard from a live leader recently.
     * Does NOT mutate term, votedFor, or timers — pre-vote is hypothesis-only.
     */
    private suspend fun onPreVote(from: NodeId, m: RaftMessage.PreVote) {
        val logOk = isLogUpToDate(state.log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
        val grant = m.term > state.currentTerm && logOk && !leaderAlive
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
        send(from, RaftMessage.PreVoteResponse(state.currentTerm, grant, m.term, m.round))
    }

    private suspend fun onPreVoteResponse(from: NodeId, m: RaftMessage.PreVoteResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (preVoteTerm == null || m.proposedTerm != preVoteTerm || m.round != preVoteRound) return
        if (m.voteGranted) {
            preVotesGranted += from
            if (state.membershipState.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) startRealElection()
        }
    }

    private suspend fun becomeLeader() {
        val elapsed = electionStartTime?.elapsedNow() ?: Duration.ZERO
        electionStartTime = null
        emitMetric(RaftMetric.ElectionWon(state.currentTerm, elapsed))
        logger.debug { "[raft:${transport.selfId}] won election for term ${state.currentTerm} in ${elapsed.inWholeMilliseconds}ms" }

        _role.value = RaftRole.Leader
        _leader.value = transport.selfId
        electionJob?.cancel()
        leaderAlive = true
        leaderLeaseJob?.cancel()
        val nextIdx = state.lastLogIndex + 1L
        otherMembers.forEach { p ->
            state.nextIndex[p] = nextIdx
            state.matchIndex[p] = 0L
        }
        emitTrace(RaftTraceEvent.BecomeLeader(nextClock(), transport.selfId, state.currentTerm))
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
        readIndexTracker.reset()
        // Transfer state: always clear on becoming leader so a re-elected-after-stepdown node
        // doesn't carry stale transfer state from a previous term.
        transfer.reset()
        // §5.4.2: append a no-op from the new term so the commit guard (entry.term == currentTerm)
        // can advance commitIndex over any prior-term entries inherited from a previous leader.
        // appendNoOp arms readIndexTracker's no-op gate (onNoOpAppended) so readIndex() knows when to gate.
        appendNoOp()
    }

    private suspend fun appendNoOp() {
        val noOpIndex = state.lastLogIndex + 1L
        readIndexTracker.onNoOpAppended(noOpIndex)   // gate for readIndex(): must not return before this commits
        val noOp = LogEntry(noOpIndex, state.currentTerm, byteArrayOf(), isNoOp = true)
        state.log += noOp
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
            readIndexTracker.failAll(LeadershipLostException("lost leadership before read confirmed"))
            debug { "relinquishToFollower($reason): failed in-flight proposals, config change, and pending reads" }
            snapshotSender.abandonAll()   // leader-only transfer state — abandon any in-flight snapshot sends
            dedupCache.clear()     // leader-only best-effort dedup cache — a new leader starts cold
            // Leadership transfer: a HigherTermObserved step-down while a transfer is active means the
            // target won its election — complete the transfer deferred successfully. Any other reason
            // (CheckQuorum, RemovedFromConfig) is an unrelated step-down — fail the transfer.
            transfer.onLeadershipRelinquished(reason)
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
        emitTrace(RaftTraceEvent.BecomeFollower(nextClock(), transport.selfId, state.currentTerm, reason))
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
        // membershipState.quorumOfContacts credits self per voter set (only when self ∈ that set).
        if (!state.membershipState.quorumOfContacts(contacted, transport.selfId)) {
            debug { "onQuorumCheck: lost quorum — contacted=$contacted membershipState=${state.membershipState}" }
            stepDownToFollower(StepDownReason.LostQuorum)
        }
    }

    // ── ReadIndex ─────────────────────────────────────────────────────────────

    /**
     * Handle a readIndex() request from the actor channel.
     *
     * Non-leader: complete exceptionally with [NotLeaderException] immediately. The leadership check is
     * engine state; the freshness/gate arithmetic is delegated to [readIndexTracker]. On a
     * [ReadIndexTracker.ReadDecision.ResolveNow] (self alone is a fresh quorum) the engine completes the
     * deferred here; on [ReadIndexTracker.ReadDecision.Queued] it logs and awaits a later quorum ACK via
     * [onAppendEntriesResponse] → [ReadIndexTracker.resolve]; on [ReadIndexTracker.ReadDecision.Gated]
     * (the §8 current-term-no-op gate is not yet crossed) the tracker parks the re-invocation below —
     * redelivered from [advanceCommit] via [ReadIndexTracker.onNoOpCommitted] once the no-op commits.
     */
    private fun onRequestReadIndex(deferred: CompletableDeferred<Long>) {
        if (_role.value !is RaftRole.Leader) {
            deferred.completeExceptionally(NotLeaderException("readIndex: not the current leader"))
            return
        }
        val decision = readIndexTracker.request(
            deferred = deferred,
            commitIndex = state.currentCommitIndex,
            membership = state.membershipState,
            selfId = transport.selfId,
            reinvoke = { onRequestReadIndex(deferred) },
        )
        when (decision) {
            ReadIndexTracker.ReadDecision.Gated -> Unit
            is ReadIndexTracker.ReadDecision.ResolveNow -> deferred.complete(decision.readIndex)
            is ReadIndexTracker.ReadDecision.Queued ->
                debug { "onRequestReadIndex: queued ri=${decision.readIndex} sinceRound=${decision.sinceRound} pendingReads=${decision.pendingCount}" }
        }
    }

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
        readIndexTracker.bumpRound()   // bump the round counter before sending so ACKs that arrive back reference a round > any pre-send sinceRound
        otherMembers.forEach { sendAppendEntries(it) }
    }

    private suspend fun sendAppendEntries(peer: NodeId) {
        val ni = state.nextIndex[peer] ?: 1L
        // §7: the prefix the follower still needs has been compacted away — divert to InstallSnapshot.
        if (ni <= state.snapshotIndex) {
            debug { "sendAppendEntries($peer): ni=$ni <= snapshotIndex=${state.snapshotIndex} → divert to InstallSnapshot" }
            // #1222: onHeartbeat diverts here every tick for the whole transfer (nextIndex stays
            // ≤ snapshotIndex until it completes), so [sendSnapshotChunk] must RESUME an in-flight
            // transfer from the follower's acked offset — never restart it from offset 0. It loads a
            // fresh snapshot only when there is no transfer in flight; any rewind is follower-driven
            // via ReAdvertise(0). (A `restart = true` affordance once lived here and caused the
            // livelock — it is deliberately gone so it cannot be reintroduced.)
            sendSnapshotChunk(peer); return
        }
        val prevIndex = ni - 1L
        val prevTerm = if (prevIndex == state.snapshotIndex) {
            state.snapshotTerm
        } else {
            state.entryAt(prevIndex)?.term
                ?: error("prevTerm for in-window index $prevIndex missing (snapshotIndex=${state.snapshotIndex}, lastLogIndex=${state.lastLogIndex})")
        }
        val entries = logSliceFrom(state.log, state.snapshotIndex, ni)
        debug { "sendAppendEntries($peer): ni=$ni prevIndex=$prevIndex prevTerm=$prevTerm entries=${entries.size} commit=${state.currentCommitIndex}" }
        emitTrace(
            RaftTraceEvent.AppendEntries(
                clock = nextClock(),
                from = transport.selfId,
                to = peer,
                term = state.currentTerm,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entryCount = entries.size,
                leaderCommit = state.currentCommitIndex,
            )
        )
        send(
            peer,
            RaftMessage.AppendEntries(
                term = state.currentTerm,
                leaderId = transport.selfId,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entries = entries,
                leaderCommit = state.currentCommitIndex,
                round = readIndexTracker.round,
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
     * Sends the next snapshot chunk to [peer], resuming its in-flight transfer from the peer's acked
     * offset (loading the stored snapshot fresh from offset 0 only when no transfer is in flight). A
     * restart is never initiated here — the follower drives any rewind via its `ReAdvertise(0)` ack.
     * The load/slice/advance arithmetic lives in [snapshotSender]; the engine keeps the trace/send
     * side-effects.
     */
    private suspend fun sendSnapshotChunk(peer: NodeId) {
        val chunk = snapshotSender.nextChunk(peer) ?: return   // nothing to send yet
        val start = chunk.offset.toInt()
        val end = start + chunk.data.size
        debug { "sendSnapshotChunk($peer): through=${chunk.meta.lastIncludedIndex} offset=$start..$end/${chunk.totalBytes} done=${chunk.done}" }
        emitTrace(
            RaftTraceEvent.InstallSnapshot(
                nextClock(), transport.selfId, peer, chunk.meta.lastIncludedIndex, chunk.offset, chunk.done,
            )
        )
        send(
            peer,
            RaftMessage.InstallSnapshot(
                term = state.currentTerm,
                leaderId = transport.selfId,
                lastIncludedIndex = chunk.meta.lastIncludedIndex,
                lastIncludedTerm = chunk.meta.lastIncludedTerm,
                offset = chunk.offset,
                data = chunk.data,
                done = chunk.done,
                config = chunk.meta.config,
                round = readIndexTracker.round,
            )
        )
    }

    /** Leader: advance or finish a snapshot transfer in response to a follower's ack. */
    private suspend fun onInstallSnapshotResponse(from: NodeId, m: RaftMessage.InstallSnapshotResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != state.currentTerm) return
        recentVoterContacts += from                // reachability signal for CheckQuorum
        readIndexTracker.recordAck(from, m.echoedRound)   // credit ACK to the round it actually responded to (BLOCKER 1a)
        confirmFreshReads()                        // ReadIndex: snapshot ACKs count as freshness evidence
        when (val outcome = snapshotSender.onAck(from, m.nextOffset)) {
            SnapshotSender.AckOutcome.NoTransfer -> return
            is SnapshotSender.AckOutcome.Complete -> {            // fully received
                state.matchIndex[from] = maxOf(state.matchIndex[from] ?: 0L, outcome.lastIncludedIndex)
                state.nextIndex[from] = outcome.lastIncludedIndex + 1L
                debug { "onInstallSnapshotResponse($from): COMPLETE through=${outcome.lastIncludedIndex} → nextIndex=${state.nextIndex[from]}, resume AppendEntries" }
                sendAppendEntries(from)                           // resume normal replication
                tryAdvanceLeaderCommit()
            }
            SnapshotSender.AckOutcome.SendNext -> {
                debug { "onInstallSnapshotResponse($from): ack offset=${m.nextOffset}, send next chunk" }
                sendSnapshotChunk(from)                            // next chunk
            }
        }
    }

    /** Follower: reassemble chunks in order, then install the snapshot once the final chunk arrives. */
    private suspend fun onInstallSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot) {
        if (m.term < state.currentTerm) { send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, 0L)); return }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        _role.value = followerRole
        preVoteTerm = null          // a live leader appeared — cancel any in-flight pre-vote probe
        _leader.value = m.leaderId
        resetElectionTimeout()
        armLeaderLease()

        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)
        when (val outcome = snapshotReceiver.onChunk(meta, m.offset, m.data, m.done)) {
            is SnapshotReceiver.ChunkOutcome.ReAdvertise -> {
                debug { "onInstallSnapshot($from): out-of-order offset=${m.offset} (have=${outcome.haveOffset}) → re-advertise, await resend" }
                send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, outcome.haveOffset, echoedRound = m.round))
            }
            is SnapshotReceiver.ChunkOutcome.AwaitMore -> {
                debug { "onInstallSnapshot($from): chunk offset=${m.offset} accepted (have=${outcome.haveOffset}), await more" }
                send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, outcome.haveOffset, echoedRound = m.round))
            }
            is SnapshotReceiver.ChunkOutcome.Complete -> finalizeInstalledSnapshot(from, m, outcome.bytes)
        }
    }

    /** Persist + apply a fully-reassembled snapshot, reset the log around it, and emit the install. */
    private suspend fun finalizeInstalledSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot, bytes: ByteArray) {
        // A snapshot at or below our applied frontier can only regress state — ack and ignore it.
        // This is the etcd-style `<= committed` restore guard: Raft Fig 13 rule 6 (when our existing
        // entry already covers the snapshot's last index/term, retain the suffix and *reply* — do NOT
        // fall through to the rule-8 state-machine reset), plus §7's tolerance of retransmitted /
        // out-of-order InstallSnapshot chunks (the paper's figure doesn't spell the `<= committed` case
        // out explicitly). Without it a stale/duplicate snapshot below `snapshotIndex` hits the
        // discard-whole branch (entryAt is null) and wipes the committed suffix (#1219); this also keeps
        // the `Committed.Install` emit below reachable only when the snapshot genuinely advances the
        // frontier, so a behind-commit retain-suffix snapshot never resets the state machine backward
        // (#1220). `currentCommitIndex` is the strictly stronger bound — it covers both `<= snapshotIndex`
        // and `<= commitIndex`, since `snapshotIndex <= currentCommitIndex` always holds. The ack is a
        // safe no-op at the leader: `SnapshotSender.onAck` returns `NoTransfer` when there is no live
        // transfer for this peer (the common case for a delayed duplicate), and it is byte-identical to
        // the ack the normal finalize path sends below. `reset()` releases the fully-reassembled buffer
        // (this path is reached only on `ChunkOutcome.Complete`, so `SnapshotReceiver` is holding the
        // entire snapshot) — honoring the reset-on-every-Complete contract and avoiding a retained-bytes leak.
        if (m.lastIncludedIndex <= state.currentCommitIndex) {
            snapshotReceiver.reset()
            send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, bytes.size.toLong(), echoedRound = m.round))
            return
        }
        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)
        storage.saveSnapshot(meta, bytes)
        // Keep the suffix only if our entry at the boundary matches the snapshot's term (Log Matching);
        // otherwise the whole local log is suspect — discard it and rebuild from the snapshot.
        if (state.entryAt(m.lastIncludedIndex)?.term == m.lastIncludedTerm) {
            storage.discardLogPrefix(m.lastIncludedIndex)
            state.log.removeAll { it.index <= m.lastIncludedIndex }
        } else {
            storage.truncateFrom(0L)
            state.log.clear()
        }
        state.snapshotIndex = m.lastIncludedIndex
        state.snapshotTerm = m.lastIncludedTerm
        if (state.currentCommitIndex < m.lastIncludedIndex) {
            state.currentCommitIndex = m.lastIncludedIndex
            _commitIndex.value = m.lastIncludedIndex
        }
        _compactionFloor.value = state.snapshotIndex
        // Adopt the snapshot's effective config as the recompute baseline, then recompute membershipState:
        // the config entries that produced this membershipState were compacted away on the leader, so the
        // snapshot is the only place the installer can learn them. A non-null joint payload resumes the
        // joint phase. Falls through to log-based or bootstrapConfig when the snapshot carries no config.
        state.snapshotConfig = m.config
        recomputeMembership()
        _committed.emit(Committed.Install(Snapshot(m.lastIncludedIndex, bytes)))
        snapshotReceiver.reset()
        debug { "finalizeInstalledSnapshot($from): INSTALLED through=${m.lastIncludedIndex} term=${m.lastIncludedTerm} commit=${state.currentCommitIndex} logTail=${state.log.firstOrNull()?.index}..${state.log.lastOrNull()?.index} membershipState=${state.membershipState}" }
        emitTrace(RaftTraceEvent.InstallSnapshotAccepted(nextClock(), from, transport.selfId, m.lastIncludedIndex))
        send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, bytes.size.toLong(), echoedRound = m.round))
    }

    private suspend fun onAppendEntries(from: NodeId, m: RaftMessage.AppendEntries) {
        if (m.term < state.currentTerm) {
            send(from, RaftMessage.AppendEntriesResponse(state.currentTerm, false, echoedRound = m.round))
            return
        }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
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
        if (m.prevLogIndex > state.snapshotIndex) {
            val prev = state.entryAt(m.prevLogIndex)
            if (prev == null || prev.term != m.prevLogTerm) {
                // §5.3 fast backup: report conflict info
                val conflictTerm = prev?.term ?: state.log.lastOrNull { it.index <= m.prevLogIndex }?.term
                val conflictIndex = conflictTerm?.let { t -> state.log.firstOrNull { it.term == t }?.index }
                val resolvedConflictIndex = conflictIndex ?: m.prevLogIndex
                debug { "onAppendEntries($from): REJECT prevLogIndex=${m.prevLogIndex} prevLogTerm=${m.prevLogTerm} (have=${prev?.term}) snapshotIndex=${state.snapshotIndex} → conflictIndex=$resolvedConflictIndex" }
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
                        term = state.currentTerm,
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
            val conflict = state.log.firstOrNull { it.index == first.index && it.term != first.term }
            if (conflict != null) {
                storage.truncateFrom(conflict.index)
                state.log.removeAll { it.index >= conflict.index }
                // Adopt-on-append: recompute membershipState after rollback so a truncated config entry
                // is immediately uneffected (§6 rollback safety).
                recomputeMembership()
            }
            val have = state.log.mapTo(HashSet()) { it.index }
            val toAdd = m.entries.filter { it.index !in have }
            if (toAdd.isNotEmpty()) {
                state.log.addAll(toAdd)
                storage.appendEntries(toAdd)
                // Adopt-on-append: recompute membershipState after adding entries — a config entry
                // in toAdd takes effect immediately on the follower.
                recomputeMembership()
            }
        }

        if (m.leaderCommit > state.currentCommitIndex) {
            advanceCommit(minOf(m.leaderCommit, state.lastLogIndex))
        }

        val acceptedMatchIndex = state.lastLogIndex
        debug { "onAppendEntries($from): ACCEPT prevLogIndex=${m.prevLogIndex} +${m.entries.size} entries → matchIndex=$acceptedMatchIndex commit=${state.currentCommitIndex}" }
        emitTrace(
            RaftTraceEvent.AppendEntriesAccepted(
                clock = nextClock(),
                from = from,
                to = transport.selfId,
                matchIndex = acceptedMatchIndex,
            )
        )
        send(from, RaftMessage.AppendEntriesResponse(state.currentTerm, true, acceptedMatchIndex, echoedRound = m.round))
    }

    private suspend fun onAppendEntriesResponse(from: NodeId, m: RaftMessage.AppendEntriesResponse) {
        // stale-term peer response: step down and discard
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != state.currentTerm) return
        recentVoterContacts += from                 // reachability signal for CheckQuorum (success or failure)
        readIndexTracker.recordAck(from, m.echoedRound)   // credit ACK to the round it actually responded to (BLOCKER 1a)
        confirmFreshReads()                         // ReadIndex: check if any pending reads can now be confirmed
        if (m.success) {
            // Clamp to lastLogIndex: a leader can never have replicated entries it doesn't hold, so a
            // real follower's match never exceeds lastLogIndex; the clamp only bites on a malformed/
            // foreign response, turning a downstream prevTerm-missing crash (#1175) into a benign no-op.
            state.matchIndex[from] = maxOf(state.matchIndex[from] ?: 0L, minOf(m.matchIndex, state.lastLogIndex))
            state.nextIndex[from] = state.matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
            // §3.10 step 2: if a transfer is in flight and the target's log now fully matches ours
            // (matchIndex == lastLogIndex, not merely commitIndex), send TimeoutNow.
            if (transfer.onPeerAck(from, state.matchIndex.getValue(from), state.lastLogIndex)) sendTimeoutNow(from)
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            state.nextIndex[from] = nextIndexAfterFailure(state.nextIndex[from] ?: 1L, m, state.log)
            debug { "onAppendEntriesResponse($from): REJECTED → backup nextIndex=${state.nextIndex[from]} (snapshotIndex=${state.snapshotIndex}), resend" }
            sendAppendEntries(from)
        }
    }

    /**
     * ReadIndex: ask [readIndexTracker] which pending reads a fresh voter-quorum now confirms (the
     * round-slip and joint dual-majority freshness arithmetic — BLOCKER 1 and 2 — live in the tracker,
     * documented on [ReadIndexTracker]), then emit the trace and complete each read's deferred here.
     * Called after every AppendEntries/InstallSnapshot ACK is credited via [ReadIndexTracker.recordAck].
     */
    private suspend fun confirmFreshReads() {
        readIndexTracker.resolve(state.membershipState, transport.selfId).forEach {
            emitTrace(RaftTraceEvent.ReadIndexConfirmed(nextClock(), it.readIndex, state.currentTerm))
            it.deferred.complete(it.readIndex)
        }
    }

    private suspend fun tryAdvanceLeaderCommit() {
        // membershipState.committedIndex accounts for Simple vs Joint quorum, self-credit per voter set,
        // and the §5.4.2 term-guard (only entries from currentTerm can be used to advance commit
        // via replica-count — older entries only commit by implication via Log Matching).
        val majorityIdx = state.membershipState.committedIndex(state.matchIndex, state.lastLogIndex, transport.selfId) ?: return
        val entry = state.entryAt(majorityIdx)
        if (entry != null && entry.term == state.currentTerm && majorityIdx > state.currentCommitIndex) {
            advanceCommit(majorityIdx)
        }
    }

    private suspend fun advanceCommit(newCommit: Long) {
        val oldCommit = state.currentCommitIndex
        // Capture only the LAST committed config entry in this advance window; side-effects run AFTER
        // the loop so currentCommitIndex is already bumped and entryAt is not racing a mutation.
        // Keeping only the last is safe even if a Joint and its trailing Simple(C_new) both commit in
        // one window: C_new is then already in the log, so onConfigCommitted's Joint branch would only
        // re-append an entry C_new identical to what already exists — skipping it is harmless, and we
        // run the Simple(C_new) side-effects (complete the deferred, removed-leader step-down) directly.
        var committedConfigEntry: LogEntry? = null
        for (idx in (state.currentCommitIndex + 1)..newCommit) {
            val entry = state.entryAt(idx) ?: continue
            when {
                entry.config != null -> {
                    // Config entry: advance commitIndex but withhold from _committed (internal, like no-op).
                    committedConfigEntry = entry
                }
                entry.isNoOp -> {
                    // §5.4.2 no-op: advance commitIndex but withhold from application-facing flow.
                }
                else -> {
                    detectCollision(entry)
                    emitProposeCommittedAndApplied(entry)
                    _committed.emit(Committed.Entry(entry))
                    // Best-effort leader cache: record on the leader only (a follower would accumulate
                    // entries it never serves; the cache is leader-scoped and cleared on step-down).
                    if (_role.value is RaftRole.Leader) dedupCache.record(entry.dedupKey, entry)
                }
            }
            _commitIndex.value = idx
            val matches = pending.filter { (i, _) -> i == idx }
            pending.removeAll(matches)
            matches.forEach { (_, d) -> d.complete(entry) }
        }
        state.currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
        // ReadIndex leader-completeness gate: if the current-term no-op just committed, re-deliver
        // any readIndex() requests that were parked waiting for it.
        readIndexTracker.onNoOpCommitted(state.currentCommitIndex).forEach { it() }
        // Config-commit side effects AFTER commitIndex is bumped — safe to call appendConfigEntry.
        committedConfigEntry?.let { onConfigCommitted(it) }
    }

    /**
     * §8 collision check on every committed application entry: a committed entry under [myClientId]
     * bearing a serial this node never issued proves another live writer shares the identity. A
     * **durable** id (caller-supplied) is an operational error — throw [ClientIdCollisionException]
     * loud from the actor loop. An **auto** id silently re-mints (fresh suffix, reset serial +
     * detector) and logs a warning; the in-flight writes already committed are unaffected.
     */
    private fun detectCollision(entry: LogEntry) {
        if (!collisions.isForeign(entry.dedupKey)) return
        if (isDurableId) {
            throw ClientIdCollisionException(
                "another writer committed under durable clientId '${myClientId.value}' " +
                    "(foreign serial ${entry.dedupKey?.requestId} at index ${entry.index}); two processes share one id",
            )
        }
        val old = myClientId
        myClientId = ClientId.auto(transport.selfId, raftConfig.random)
        serial = 0L
        collisions = CollisionDetector(myClientId)
        logger.warn {
            "[raft:${transport.selfId}] clientId collision: auto id '${old.value}' seen with a foreign " +
                "serial ${entry.dedupKey?.requestId} — re-minted as '${myClientId.value}'"
        }
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
        val install = if (c.fromIndex <= state.snapshotIndex && state.snapshotIndex > 0L)
            storage.loadSnapshot()?.let { Snapshot(it.meta.lastIncludedIndex, it.state) } else null
        val from = maxOf(c.fromIndex, state.snapshotIndex + 1)
        val replay = logSliceFrom(state.log, state.snapshotIndex, from)
            .filter { it.index <= state.currentCommitIndex && !it.isNoOp }
        c.response.complete(CommitCutResult(replay, state.currentCommitIndex, install))
    }

    private suspend fun onCompact() {
        val s = snapshots.value ?: return
        if (s.throughIndex <= state.snapshotIndex || s.throughIndex > state.currentCommitIndex) return
        val term = state.termAt(s.throughIndex) ?: return   // must be a live, committed entry
        // The membershipState the snapshot must carry is the config as of `throughIndex` — the highest-index
        // config entry at or below the cut, else the config the prior snapshot already recorded. It must
        // NOT be the live `membershipState`, which may reflect a later config entry between the cut and the
        // log tail (that would stamp the snapshot with a future config and corrupt an installer's view).
        val configAsOfCut = state.log.lastOrNull { it.config != null && it.index <= s.throughIndex }?.config
            ?: state.snapshotConfig
        storage.saveSnapshot(SnapshotMeta(s.throughIndex, term, configAsOfCut), s.state)   // durable FIRST
        storage.discardLogPrefix(s.throughIndex)                             // then drop prefix
        state.log.removeAll { it.index <= s.throughIndex }
        state.snapshotIndex = s.throughIndex
        state.snapshotTerm = term
        // Retain the compacted config as the snapshot baseline so a subsequent recompute (or restart)
        // still resolves membershipState correctly once the config entry is gone from the live log.
        state.snapshotConfig = configAsOfCut
        _compactionFloor.value = state.snapshotIndex
        emitTrace(RaftTraceEvent.Compacted(nextClock(), transport.selfId, state.snapshotIndex, state.snapshotTerm))
    }

    // ── propose() ─────────────────────────────────────────────────────────────

    /**
     * Actor-loop entry for a **local** proposal: stamp this node's own [DedupKey] (auto-serial when
     * [requestId] is null, else the caller-pinned serial), record it as issued for collision detection,
     * then hand off to [onPropose] which appends-or-forwards with the stamped key. Stamping here (not in
     * [onPropose]) is deliberate: forwarded proposals reach [onPropose] via [onForward] carrying the
     * *originator's* key, which must NOT be re-stamped and must NOT count as a serial this node issued.
     */
    private suspend fun onLocalPropose(command: ByteArray, requestId: Long?, response: CompletableDeferred<LogEntry>) {
        val reqId = requestId ?: ++serial
        collisions.issued(reqId)
        onPropose(command, DedupKey(myClientId, reqId), response)
    }

    private suspend fun onPropose(command: ByteArray, dedupKey: DedupKey?, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            // Follower/Candidate/Learner: forward to the leader (Raft §8). Wait, cancellably,
            // if none is known yet. Cleanup of cancelled entries is handled on the actor loop
            // in flushWaitingForLeader (isCompleted check) and forwarder.failAll (finally).
            // Do NOT use invokeOnCompletion to mutate the forwarder maps — that runs on the
            // caller's thread and races the actor loop, which is the sole owner of the maps.
            when (val d = forwarder.forward(response, command, dedupKey, _leader.value, transport.selfId)) {
                is ProposalForwarder.ForwardDecision.SendToLeader ->
                    send(d.leaderId, RaftMessage.Forward(d.id, d.command, d.dedupKey))
                ProposalForwarder.ForwardDecision.Queued -> Unit
            }
            return
        }
        // §3.10: while a leadership transfer is in flight, reject new proposals so the target can
        // catch up to our log without racing additional appends. The NotLeaderException is the correct
        // signal — the caller should retry on the new leader once transfer completes.
        val transferInFlight = transfer.inFlightTarget
        if (transferInFlight != null) {
            response.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferInFlight.value}"))
            return
        }
        // §8 best-effort leader dedup: a retry of an already-committed key coalesces onto the recorded
        // result instead of appending a second entry. The consumer's ClientSessionTable is the durable
        // backstop; this only catches the common lost-ack retry on a still-leading node.
        dedupCache.lookup(dedupKey)?.let { response.complete(it); return }
        val index = state.lastLogIndex + 1L
        val entry = LogEntry(index, state.currentTerm, command, dedupKey = dedupKey)
        state.log += entry
        storage.appendEntries(listOf(entry))
        emitTrace(RaftTraceEvent.ClientRequest(nextClock(), transport.selfId, index, state.currentTerm))
        proposeStartTimes[index] = TimeSource.Monotonic.markNow()
        emitMetric(RaftMetric.ProposeAccepted(index, state.currentTerm))
        logger.debug { "[raft:${transport.selfId}] propose accepted at index $index term ${state.currentTerm}" }
        pending += index to response
        otherMembers.forEach { sendAppendEntries(it) }
        // Single-voter: no peers will ACK — check for immediate commit (peerQuorum == 0).
        tryAdvanceLeaderCommit()
    }

    override suspend fun propose(command: ByteArray): LogEntry = proposeWithRequestId(command, null)

    override suspend fun propose(command: ByteArray, requestId: Long): LogEntry =
        proposeWithRequestId(command, requestId)

    private suspend fun proposeWithRequestId(command: ByteArray, requestId: Long?): LogEntry {
        val d = CompletableDeferred<LogEntry>()
        try {
            cmd.send(EngineCommand.Propose(command, requestId, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        try {
            return d.await()
        } finally {
            // If this coroutine was cancelled before the deferred completed, mark the deferred
            // cancelled so the actor loop's flushWaitingForLeader sees isCompleted==true and
            // drops it rather than forwarding a cancelled request. This is the only mutation of
            // d that touches the caller's thread; all other map/state mutations stay on the actor.
            // cancel() on an already-completed deferred is a harmless no-op.
            d.cancel()
        }
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
     * Recompute [membershipState] from the current log + [snapshotConfig] + [bootstrapConfig].
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
        val prior = state.membershipState
        val configEntry = state.log.lastOrNull { it.config != null }
        val logConfig = configEntry?.config
        val resolved = logConfig ?: state.snapshotConfig
        val newMembership = when {
            resolved == null           -> MembershipState.Simple(bootstrapConfig)
            resolved.old != null       -> MembershipState.Joint(resolved.old, resolved.new)
            else                       -> MembershipState.Simple(resolved.new)
        }
        val branch = when {
            configEntry != null    -> "log[${configEntry.index}]"
            state.snapshotConfig != null -> "snapshot"
            else                   -> "bootstrap"
        }
        val changed = newMembership != prior
        state.membershipState = newMembership
        reevaluateSelfRole()
        if (changed) {
            _membership.value = newMembership.effectiveConfig
            debug { "recomputeMembership: $prior → $newMembership (source=$branch)" }
            // `old` is the prior effective config — on the first ever change that is the
            // bootstrap config, which is more informative than null.
            val configIndex = configEntry?.index ?: if (state.snapshotConfig != null) state.snapshotIndex else state.lastLogIndex
            emitTrace(
                RaftTraceEvent.ConfigChange(
                    nextClock(), transport.selfId, configIndex, prior.effectiveConfig, newMembership.effectiveConfig,
                ),
            )
        }
    }

    /**
     * After recomputing [membershipState], re-evaluate this node's resting role.
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
        val index = state.lastLogIndex + 1L
        val entry = LogEntry(index, state.currentTerm, byteArrayOf(), config = payload)
        state.log += entry
        storage.appendEntries(listOf(entry))
        // recomputeMembership() emits the ConfigChange trace event (unified leader+follower path),
        // so we do not emit it here — doing so would double-emit on the leader.
        recomputeMembership()
        debug { "appendConfigEntry: index=$index payload=$payload membershipState=${state.membershipState}" }
        state.membershipState.replicationTargets(transport.selfId).forEach { sendAppendEntries(it) }
        tryAdvanceLeaderCommit()
    }

    /**
     * Leader: validate and initiate a membershipState change request from [changeMembership].
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
        // §3.10 step 1: while a leadership transfer is in flight, reject new requests — a membership
        // change is a new request, exactly like a proposal (mirrors the onPropose gate). Appending a
        // config entry mid-transfer would move the lastLogIndex goalpost the target is chasing.
        val transferInFlight = transfer.inFlightTarget
        if (transferInFlight != null) {
            debug { "onChangeMembership: rejected — leadership transfer in flight to ${transferInFlight.value}" }
            deferred.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferInFlight.value}"))
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
        val current = state.membershipState
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
     * durable, so the new cluster elects a leader from its own membershipState.
     */
    private suspend fun onConfigCommitted(entry: LogEntry) {
        val payload = entry.config ?: return
        debug { "onConfigCommitted: entry.index=${entry.index} payload=$payload" }
        if (payload.old != null) {
            // Joint committed → append C_new (Simple) to complete the transition — but ONLY if no
            // later config entry has already superseded the Joint. `membershipState` always reflects the
            // last config entry in the log, so `membershipState is Joint` is exactly the condition "no
            // Simple(C_new) follows the Joint yet." A new leader that inherits a Joint whose trailing
            // Simple(C_new) is already in its log (the original leader appended it before crashing /
            // stepping down) must NOT append a second C_new: that duplicate carries the new leader's
            // term, diverges from the existing C_new, and wedges replication in an infinite
            // AppendEntries backup loop. Skipping is safe — C_new already exists; the Simple branch
            // will complete the deferred and run the step-down when that existing C_new commits.
            if (state.membershipState is MembershipState.Joint) {
                debug { "onConfigCommitted: Joint committed — appending Simple(C_new=${payload.new})" }
                appendConfigEntry(ConfigPayload(old = null, new = payload.new))
            } else {
                debug { "onConfigCommitted: Joint committed but C_new already in log (membershipState=${state.membershipState}) — skip duplicate append" }
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
        val currentVoters = state.membershipState.effectiveConfig.voters
        if (target !in currentVoters) {
            response.completeExceptionally(IllegalArgumentException("transferLeadership: target ${target.value} is not a voter in the current config ($currentVoters)"))
            return
        }
        // §3.10 step 1, the reverse direction of the onChangeMembership gate: refuse to start a transfer
        // while a membership change is still converging. pendingConfigChange stays non-null from
        // changeMembership until the resulting Simple entry commits (onConfigCommitted), and the
        // Joint→Simple auto-append fires inside that window — appending an entry that would grow
        // lastLogIndex mid-transfer, moving the goalpost the target is chasing. Transfer and membership
        // change are thus mutually exclusive in both directions, which is what keeps lastLogIndex stable
        // for the duration of the transfer (the onPeerAck predicate relies on this).
        if (pendingConfigChange != null) {
            response.completeExceptionally(MembershipChangeInProgressException("transferLeadership: a membership change is in progress"))
            return
        }
        // A second concurrent call while one is already in flight: reject the second. `start` arms the
        // auto-timeout timer (one election-timeout window) and parks `response`, returning false iff a
        // transfer is already in flight — in which case `inFlightTarget` is the existing target.
        if (!transfer.start(target, response)) {
            response.completeExceptionally(IllegalStateException("transferLeadership: a transfer to ${transfer.inFlightTarget?.value} is already in flight"))
            return
        }
        emitTrace(RaftTraceEvent.LeadershipTransferStarted(nextClock(), transport.selfId, target))
        debug { "onTransferLeadership: transfer started to ${target.value}" }

        // Sync target's log; send TimeoutNow now iff it is already fully caught up (matchIndex >= lastLogIndex,
        // §3.10 step 2). Otherwise the AppendEntries ACK path (onAppendEntriesResponse → transfer.onPeerAck)
        // sends it once the target catches up. AppendEntries delivery is best-effort — the heartbeat loop retries.
        sendAppendEntries(target)
        if (transfer.onPeerAck(target, state.matchIndex[target] ?: 0L, state.lastLogIndex)) sendTimeoutNow(target)
    }

    /** Send a [RaftMessage.TimeoutNow] to [target]. */
    private suspend fun sendTimeoutNow(target: NodeId) {
        debug { "sendTimeoutNow: sending TimeoutNow to ${target.value} term=${state.currentTerm}" }
        send(target, RaftMessage.TimeoutNow(state.currentTerm, transport.selfId))
    }

    /**
     * Auto-timeout fired: the target did not win an election within one election-timeout window.
     * Resume normal operation (re-enable proposals) and fail the transfer deferred.
     */
    private suspend fun onTransferTimeout() {
        val target = transfer.onTimeout() ?: return   // already resolved — ignore stale timer
        debug { "onTransferTimeout: transfer to ${target.value} timed out — resuming normal operation" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Timeout,
        ))
    }

    /**
     * Explicit cancel from the application: abort the in-flight transfer and resume proposals.
     */
    private suspend fun onCancelTransfer() {
        val target = transfer.onCancel() ?: return   // nothing in flight — no-op
        debug { "onCancelTransfer: transfer to ${target.value} cancelled" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Cancelled,
        ))
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
        debug { "onTimeoutNow: from=${from.value} term=${m.term} currentTerm=${state.currentTerm} role=${_role.value}" }
        // Ignore if from a stale leader or if we are already leader/candidate.
        if (m.term < state.currentTerm) {
            debug { "onTimeoutNow: stale term ${m.term} < currentTerm=${state.currentTerm} — ignoring" }
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
        if (m.term == state.currentTerm && _leader.value != null && from != _leader.value) {
            debug { "onTimeoutNow: sender ${from.value} is not the current leader (${_leader.value?.value}) — ignoring" }
            return
        }
        // A learner never votes and must never start an election.
        if (_role.value is RaftRole.Learner) {
            debug { "onTimeoutNow: self is a learner — ignoring" }
            return
        }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        // Start a real election immediately (skip pre-vote — we are already up-to-date per the leader's sync).
        debug { "onTimeoutNow: starting immediate election (skipping pre-vote)" }
        startRealElection()
    }

    // ── Client-proposal forwarding (§8) ──────────────────────────────────────
    // Forwarding state (the outstanding forwards, the no-leader queue, and the correlation
    // nonce) lives in `forwarder` (ProposalForwarder), declared before `init` above (#1077).
    // onForward (the LEADER side) stays here — it is a propose-path entry point, not
    // forwarder state.

    /** Leader handles a forwarded proposal: run the normal propose path, reply with its fate. */
    private suspend fun onForward(from: NodeId, m: RaftMessage.Forward) {
        if (_role.value !is RaftRole.Leader) {
            // We are not the leader (stepped down, or stale Forward arrived). Reply NotLeader so
            // the originator's onForwardResponse fails with LeadershipLostException and the caller
            // can retry. Do NOT re-forward or queue — that would create a silent second hop and
            // leave the originator's deferred owned by two mechanisms.
            send(from, RaftMessage.ForwardResponse(m.clientRequestId, ForwardOutcome.NotLeader))
            return
        }
        val d = CompletableDeferred<LogEntry>()
        // Append under the proposer's own dedupKey UNCHANGED — the leader must not re-stamp it (that
        // would defeat exactly-once) and does not count it among the serials it issued itself.
        onPropose(m.command, m.dedupKey, d)
        scope.launch {
            val outcome = try {
                val e = d.await()
                ForwardOutcome.Committed(e.index, e.term)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is NotLeaderException || e is LeadershipLostException) ForwardOutcome.NotLeader
                else ForwardOutcome.Failed
            }
            // Best-effort reply; transport may be tearing down on close.
            runCatchingCancellable { send(from, RaftMessage.ForwardResponse(m.clientRequestId, outcome)) }
        }
    }

    /** Follower handles the leader's reply to a forward it sent. */
    private fun onForwardResponse(from: NodeId, m: RaftMessage.ForwardResponse) {
        val pf = forwarder.onResponse(m.clientRequestId) ?: return
        when (val o = m.outcome) {
            // Re-wrap with the proposer's own dedupKey so the returned entry matches what the leader appended.
            is ForwardOutcome.Committed -> pf.deferred.complete(LogEntry(o.index, o.term, pf.command, dedupKey = pf.dedupKey))
            ForwardOutcome.NotLeader, ForwardOutcome.Failed ->
                pf.deferred.completeExceptionally(LeadershipLostException("forwarded proposal was not committed; retry"))
        }
    }

    /**
     * Drain forwards queued while no leader was known. If we are now the leader, propose them
     * locally; otherwise send them to the current leader. No-op when nothing is queued or no
     * leader is known yet. The forwarder decides *which* action per parked entry; the engine
     * keeps the propose/send side-effects here.
     */
    private suspend fun flushWaitingForLeader() {
        val actions = forwarder.flush(_leader.value, transport.selfId, _role.value is RaftRole.Leader)
        for (action in actions) {
            when (action) {
                is ProposalForwarder.FlushAction.ReProposeLocally -> {
                    // The forward stays in the forwarder's map across this suspendable onPropose so teardown
                    // (forwarder.failAll) still owns the deferred until it lands in `pending`; evict it only
                    // AFTER onPropose returns (deferred now in `pending`).
                    onPropose(action.pf.command, action.pf.dedupKey, action.pf.deferred)
                    forwarder.reProposed(action.id)
                }
                is ProposalForwarder.FlushAction.SendToLeader ->
                    send(action.leaderId, RaftMessage.Forward(action.id, action.command, action.dedupKey))
            }
        }
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
        is RaftMessage.Forward                 -> onForward(from, m)
        is RaftMessage.ForwardResponse         -> onForwardResponse(from, m)
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
