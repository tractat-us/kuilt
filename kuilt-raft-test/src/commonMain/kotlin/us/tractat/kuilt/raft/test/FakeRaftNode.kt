package us.tractat.kuilt.raft.test

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftNode
import kotlin.random.Random
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.Snapshot

/**
 * A test double for [RaftNode] with driver helpers for driving role transitions,
 * injecting committed entries, emitting trace events, and inspecting outgoing proposals.
 *
 * Defaults make `FakeRaftNode()` ready to use in one line:
 *
 * ```kotlin
 * val node = FakeRaftNode()
 * node.setRole(RaftRole.Leader)
 * val entry = node.propose("set x=1".encodeToByteArray())
 * val committed = node.committed.first() as Committed.Entry
 * // committed.entry.command == "set x=1".encodeToByteArray()
 * ```
 *
 * **Stream semantics — a deliberate divergence from the real [RaftNode].** The real
 * [RaftNode] documents [committed] and [trace] as *hot, no-replay* flows (late
 * collectors miss history). For test ergonomics this double backs them with
 * unbounded-buffering channels instead, so `pushCommitted(...)` followed by
 * `committed.first()` works without racing a collector. Two consequences a consumer
 * should not encode as [RaftNode] guarantees:
 * - entries/events emitted before collection are **buffered and replayed** here,
 *   whereas the real [RaftNode] would drop them;
 * - [close] **completes** [committed]/[trace] (channel close), whereas the real
 *   engine cancels its backing scope without completing the flows.
 */
public class FakeRaftNode(
    /** Stable identifier for this node — exposed as a convenience field, not on [RaftNode]. */
    public val selfId: NodeId = NodeId("self"),
    initialRole: RaftRole = RaftRole.Follower,
    initialLeader: NodeId? = null,
    initialCommitIndex: Long = 0L,
    /**
     * This node's Raft §8 dedup identity, stamped onto the [LogEntry] returned by [propose]. Defaults
     * to a deterministic auto id derived from [selfId] (distinct per node id, stable per instance);
     * pass a fixed [ClientId] to assert exact dedup keys.
     */
    public val clientId: ClientId = ClientId.auto(selfId, Random(selfId.value.hashCode())),
) : RaftNode {

    /** Monotonic auto-serial for the no-requestId [propose] form. */
    private var serial: Long = 0L

    private val _role = MutableStateFlow(initialRole)
    override val role: StateFlow<RaftRole> = _role.asStateFlow()

    private val _leader = MutableStateFlow(initialLeader)
    override val leader: StateFlow<NodeId?> = _leader.asStateFlow()

    private val _commitIndex = MutableStateFlow(initialCommitIndex)
    override val commitIndex: StateFlow<Long> = _commitIndex.asStateFlow()

    private val committedChannel = Channel<Committed>(capacity = Channel.UNLIMITED)
    override val committed: Flow<Committed> = committedChannel.receiveAsFlow()

    // Backs committedFrom: an ordered history of committed entries for replay, plus a
    // live tail so late subscribers catch up then follow along (see committedFrom KDoc).
    private val committedHistory = mutableListOf<LogEntry>()
    private val committedTail = MutableSharedFlow<LogEntry>(extraBufferCapacity = Int.MAX_VALUE)

    override fun committedFrom(fromIndex: Long): Flow<Committed> = flow {
        coroutineScope {
            val buffer = Channel<LogEntry>(Channel.UNLIMITED)
            val tail = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    committedTail.collect { buffer.send(it) }
                } finally {
                    buffer.close()
                }
            }
            val cutIndex = _commitIndex.value
            committedHistory
                .filter { it.index in fromIndex..cutIndex && !it.isNoOp }
                .forEach { emit(Committed.Entry(it)) }
            for (entry in buffer) {
                if (entry.index > cutIndex && !entry.isNoOp) emit(Committed.Entry(entry))
            }
            tail.cancel()
        }
    }

    /**
     * Snapshot publish channel — present to satisfy [RaftNode]. This double runs no real compaction
     * loop, so setting it has no functional effect; drive [setCompactionFloor] to simulate a floor and
     * [pushInstall] to inject a [Committed.Install] on the [committed] stream.
     */
    override val snapshots: MutableStateFlow<Snapshot?> = MutableStateFlow(null)

    private val _compactionFloor = MutableStateFlow(0L)
    override val compactionFloor: StateFlow<Long> = _compactionFloor.asStateFlow()

    private val traceChannel = Channel<RaftTraceEvent>(capacity = Channel.UNLIMITED)
    override val trace: Flow<RaftTraceEvent> = traceChannel.receiveAsFlow()

    private val _proposals = mutableListOf<ByteArray>()
    private var _closed = false
    private var _nextIndex = initialCommitIndex + 1L

    /** Dedup key the in-flight [propose] wants stamped onto its committed entry; null outside a propose. */
    private var stampForNextCommit: DedupKey? = null

    /** All commands passed to [propose], in call order. */
    public val proposals: List<ByteArray> get() = _proposals.toList()

    /** Whether [close] has been called. */
    public val closed: Boolean get() = _closed

    /**
     * Behavior of [propose]. Defaults to contract-faithful: throws [NotLeaderException]
     * unless [role] is [RaftRole.Leader], otherwise appends the command to [committed]
     * and returns the resulting [LogEntry].
     *
     * Override to inject specific outcomes:
     * ```kotlin
     * node.proposeBehavior = { _ -> throw LeadershipLostException() }
     * ```
     */
    public var proposeBehavior: suspend (ByteArray) -> LogEntry = { command ->
        if (_role.value !is RaftRole.Leader) throw NotLeaderException()
        pushCommitted(command)
    }

    // ── RaftNode interface ────────────────────────────────────────────────────

    override suspend fun propose(command: ByteArray): LogEntry = proposeStamped(command, ++serial)

    override suspend fun propose(command: ByteArray, requestId: Long): LogEntry =
        proposeStamped(command, requestId)

    /**
     * Run [proposeBehavior] for [command] with `DedupKey(clientId, requestId)` stamped onto both the
     * committed-stream entry (via [pushCommitted]'s [stampForNextCommit] hook) and the returned entry,
     * so they stay equal. A custom [proposeBehavior] that bypasses [pushCommitted] still gets the key
     * copied onto whatever entry it returns.
     */
    private suspend fun proposeStamped(command: ByteArray, requestId: Long): LogEntry {
        _proposals.add(command)
        val key = DedupKey(clientId, requestId)
        stampForNextCommit = key
        return try {
            proposeBehavior(command).copy(dedupKey = key)
        } finally {
            stampForNextCommit = null
        }
    }

    override suspend fun close() {
        if (_closed) return
        _closed = true
        committedChannel.close()
        traceChannel.close()
    }

    // ── Test-driver helpers ───────────────────────────────────────────────────

    /** Transition [role] to [newRole]. */
    public fun setRole(newRole: RaftRole) {
        _role.value = newRole
    }

    /** Update [leader] to [newLeader]. */
    public fun setLeader(newLeader: NodeId?) {
        _leader.value = newLeader
    }

    /** Set [commitIndex] to [index]. Does not push any entry onto [committed]. */
    public fun setCommitIndex(index: Long) {
        _commitIndex.value = index
    }

    /**
     * Push [entry] onto [committed] and advance [commitIndex] to [entry]'s index.
     *
     * ```kotlin
     * node.pushCommitted(LogEntry(index = 1, term = 1, command = byteArrayOf(42)))
     * val entry = node.committed.first()
     * ```
     */
    public suspend fun pushCommitted(entry: LogEntry): LogEntry {
        committedChannel.send(Committed.Entry(entry))
        committedHistory.add(entry)
        _commitIndex.value = entry.index
        committedTail.emit(entry)
        return entry
    }

    /**
     * Push a [Committed.Install] onto [committed], advancing [compactionFloor] and [commitIndex] to
     * [snapshot]'s `throughIndex` — the test-double analogue of the engine accepting an InstallSnapshot.
     */
    public suspend fun pushInstall(snapshot: Snapshot) {
        committedChannel.send(Committed.Install(snapshot))
        _compactionFloor.value = snapshot.throughIndex
        if (_commitIndex.value < snapshot.throughIndex) _commitIndex.value = snapshot.throughIndex
    }

    /** Set [compactionFloor] to [index] without emitting a [Committed.Install]. */
    public fun setCompactionFloor(index: Long) {
        _compactionFloor.value = index
    }

    /**
     * Convenience: create a [LogEntry] at the next auto-incremented index (term=1)
     * with [command], push it onto [committed], and return it.
     *
     * ```kotlin
     * node.pushCommitted("set x=1".encodeToByteArray())
     * val entry = node.committed.first()
     * ```
     */
    public suspend fun pushCommitted(command: ByteArray): LogEntry =
        pushCommitted(LogEntry(index = _nextIndex++, term = 1L, command = command, dedupKey = stampForNextCommit))

    /**
     * Push [event] onto [trace].
     *
     * ```kotlin
     * node.emitTrace(RaftTraceEvent.BecomeLeader(clock = 1, node = NodeId("self"), term = 2))
     * val event = node.trace.first()
     * ```
     */
    public suspend fun emitTrace(event: RaftTraceEvent) {
        traceChannel.send(event)
    }
}
