@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a multi-node real-[RaftNode] cluster for testing.
 *
 * Requires `UnconfinedTestDispatcher` — see the banner in [RaftTestFixtures] for the
 * full TestDispatcher contract. Pass the test's [kotlinx.coroutines.test.TestScope] as
 * [scope] and [kotlinx.coroutines.test.TestScope.backgroundScope] as [nodeScope] so the
 * infinite election/heartbeat loops are cancelled when the test body completes.
 */
class RaftSimulation(
    val nodeIds: List<NodeId>,
    private val scope: CoroutineScope,
    private val raftConfig: RaftConfig = RaftConfig(),
    /**
     * Scope used to create per-node child scopes. In tests, pass [TestScope.backgroundScope]
     * so that the node coroutines (infinite heartbeat/election loops) are cancelled when the
     * test body finishes without causing [UncompletedCoroutinesError].
     */
    private val nodeScope: CoroutineScope = scope,
    /**
     * Per-message payload limit reported to the engine via [RaftTransport.maxPayloadBytes].
     * A tiny value forces InstallSnapshot to span many chunks (see [InMemoryRaftNetwork]).
     */
    maxPayloadBytes: Int? = null,
    private val nodeFactory: (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode,
) {
    val network = InMemoryRaftNetwork(maxPayloadBytes)
    val storages: Map<NodeId, InMemoryRaftStorage> = nodeIds.associateWith { InMemoryRaftStorage() }
    private val scopes: MutableMap<NodeId, CoroutineScope> = mutableMapOf()
    val nodes: MutableMap<NodeId, RaftNode> = mutableMapOf()

    /** Bounded per-node ring buffer of [RaftTraceEvent]s, for [dumpState]. */
    private val traces: MutableMap<NodeId, ArrayDeque<RaftTraceEvent>> = mutableMapOf()

    /** One [AppliedStateMachine] per node, re-created on [restart] so a crashed-then-restarted node stays tracked. */
    private val stateMachines: MutableMap<NodeId, AppliedStateMachine> = mutableMapOf()

    private fun start(id: NodeId) {
        val child = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
        scopes[id] = child
        val node = nodeFactory(id, network.transport(id), storages.getValue(id), child)
        nodes[id] = node
        collectTrace(id, node)
        collectStateMachine(id, node)
    }

    /** (Re)bind a fresh [AppliedStateMachine] for [node] on [nodeScope] so a restarted node is retracked from its first commit. */
    private fun collectStateMachine(id: NodeId, node: RaftNode) {
        val sm = AppliedStateMachine()
        stateMachines[id] = sm
        nodeScope.launch { node.committed.collect(sm::apply) }
    }

    /**
     * Collect [node]'s trace into a bounded ring buffer on [nodeScope] (NOT the test
     * [scope]). A hot `trace.collect` on the test scope never completes and would trip
     * [kotlinx.coroutines.test.UncompletedCoroutinesError] at teardown.
     */
    private fun collectTrace(id: NodeId, node: RaftNode) {
        val ring = ArrayDeque<RaftTraceEvent>(TRACE_RING_CAPACITY)
        traces[id] = ring
        nodeScope.launch {
            node.trace.collect { event ->
                if (ring.size >= TRACE_RING_CAPACITY) ring.removeFirst()
                ring.addLast(event)
            }
        }
    }

    fun crash(id: NodeId) {
        scopes[id]?.cancel()
        scopes.remove(id)
        nodes.remove(id)
        // Keep the trace ring so a post-crash dump still shows what this node did.
    }
    fun restart(id: NodeId) { start(id) }
    fun partition(a: Set<NodeId>, b: Set<NodeId>) = network.partition(a, b)
    fun heal() = network.heal()
    fun dropLink(from: NodeId, to: NodeId) = network.dropLink(from, to)

    /**
     * Encode and inject a [RaftMessage.PreVote] directly into [to]'s incoming channel,
     * bypassing the normal partition/drop rules.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun deliverPreVote(to: NodeId, from: NodeId, term: Long, lastLogIndex: Long, lastLogTerm: Long) {
        val bytes = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.PreVote(term, from, lastLogIndex, lastLogTerm)
        )
        network.deliver(from = from, to = to, bytes = bytes)
    }

    /**
     * Yield multiple times to let actor queues drain — deliberately yield-only (no `delay`),
     * because `advanceUntilIdle()` would fire the leader-lease timer and invalidate leader-alive tests.
     */
    suspend fun settle() = repeat(10) { yield() }

    // ── Bounded, dump-on-timeout await helpers ──────────────────────────────
    // Every cluster-state await in a raft test must go through one of these so a
    // non-converging cluster FAILS FAST with a full state dump rather than hanging
    // for the whole runTest timeout. Each polls the target condition under a
    // withTimeout(within) bound and, on expiry, throws AssertionError(dumpState(...)).

    /** Suspend until [index] is committed on every node in [on]; fail fast with a dump otherwise. */
    suspend fun awaitCommit(
        index: Long,
        on: Collection<NodeId> = nodeIds,
        within: Duration = DEFAULT_AWAIT,
    ) {
        awaitOrDump("awaitCommit($index) on $on", within) {
            pollUntil { true.takeIf { on.all { id -> committedTo(id) >= index } } }
        }
    }

    private fun committedTo(id: NodeId): Long = nodes[id]?.commitIndex?.value ?: -1L

    /** Suspend until some node is [RaftRole.Leader]; return it, or fail fast with a dump. */
    suspend fun awaitLeader(within: Duration = DEFAULT_AWAIT): RaftNode =
        awaitOrDump("awaitLeader", within) {
            pollUntil { leader() }
        }

    /** Suspend until node [id] holds [role]; fail fast with a dump otherwise. */
    suspend fun awaitRole(id: NodeId, role: RaftRole, within: Duration = DEFAULT_AWAIT) {
        awaitOrDump("awaitRole($id, $role)", within) {
            pollUntil { nodes[id]?.takeIf { it.role.value == role } }
        }
    }

    /**
     * Poll [probe] every virtual millisecond until it returns non-null, then return that value.
     *
     * The `delay`-based poll matters under [UnconfinedTestDispatcher]: each `delay(1)` advances
     * virtual time and lets the engine actors (election, heartbeat, propose) run to a quiescent
     * point, so a caller that subscribes to a hot flow immediately after the await sees the
     * subsequent emissions rather than racing queued engine work. The enclosing [withTimeout]
     * in [awaitOrDump] bounds the loop and turns non-convergence into a fast dump.
     */
    private suspend fun <T : Any> pollUntil(probe: () -> T?): T {
        while (true) {
            probe()?.let { return it }
            delay(1)
        }
    }

    private suspend fun <T> awaitOrDump(
        what: String,
        within: Duration = DEFAULT_AWAIT,
        block: suspend () -> T,
    ): T = try {
        withTimeout(within) { block() }
    } catch (_: TimeoutCancellationException) {
        // withTimeout cancelled us; suspend again on a fresh job to render the dump.
        throw AssertionError(withContext(NonCancellable) { dumpState("$what timed out after $within") })
    }

    /** Isolates a single node from every other node — the offline-follower scenario. */
    fun partitionOff(id: NodeId) = partition(setOf(id), nodeIds.filter { it != id }.toSet())

    /**
     * A committed index on [id] safely past the start of the log yet below its current commit, so a
     * snapshot through it both discards the prefix an offline follower still needs *and* leaves a
     * suffix that must replicate via AppendEntries after the install — exercising the full rejoin.
     */
    fun compactionFloorCandidate(id: NodeId): Long {
        val commit = nodes.getValue(id).commitIndex.value
        return maxOf(2L, commit - 5L)
    }

    /**
     * A live, in-order capture of every [Committed.Install] delivered to [id]'s state machine.
     * Start it before healing a partition so the install raised by the rejoin is recorded.
     */
    fun collectInstalls(id: NodeId): List<Committed.Install> {
        val installs = mutableListOf<Committed.Install>()
        nodeScope.launch {
            nodes.getValue(id).committed.collect { if (it is Committed.Install) installs += it }
        }
        return installs
    }

    /**
     * The applied state of [id]'s state machine, modelled as the ordered concatenation of every
     * committed command — reset whenever a [Committed.Install] arrives, then extended by the
     * snapshot's opaque state and any subsequent entries. Two nodes agree iff this matches.
     */
    fun appliedState(id: NodeId): ByteArray = stateMachines.getValue(id).bytes()

    /**
     * The opaque state bytes a consumer would snapshot for [id] covering exactly entries up to and
     * including [throughIndex] — the concatenation of those entries' commands. Equals what a peer
     * reaches by applying the same prefix, so a node that installs this snapshot and then replays the
     * suffix lands in the identical applied state as a node that applied the whole log linearly.
     */
    suspend fun stateBytes(id: NodeId, throughIndex: Long): ByteArray {
        val acc = ArrayList<Byte>()
        storages.getValue(id).entries().filter { it.index <= throughIndex && !it.isNoOp }
            .forEach { acc.addAll(it.command.asList()) }
        return acc.toByteArray()
    }

    /** Construct + start every node. Declared last so all properties above are initialized before [start] runs. */
    init { nodeIds.forEach { start(it) } }

    /** Folds a node's [Committed] stream into opaque bytes — entries append, installs reset to the snapshot state. */
    private class AppliedStateMachine {
        private val acc = ArrayList<Byte>()
        fun apply(c: Committed) = when (c) {
            is Committed.Entry -> { acc.addAll(c.entry.command.asList()); Unit }
            is Committed.Install -> { acc.clear(); acc.addAll(c.snapshot.state.asList()); Unit }
        }
        fun bytes(): ByteArray = acc.toByteArray()
    }

    suspend fun checkInvariants() {
        // 1. Election Safety: at most one leader at a time
        val leaders = nodes.values.filter { it.role.value is RaftRole.Leader }
        assertTrue(leaders.size <= 1,
            "Election Safety violated: ${leaders.size} simultaneous leaders (${leaders.map { it.role.value }})")

        // 2. State Machine Safety: no two nodes committed different commands at same index
        val minCommit = nodes.values.minOfOrNull { it.commitIndex.value } ?: 0L
        if (minCommit > 0L) {
            val snapshots: Map<NodeId, List<LogEntry>> = nodes.keys.associateWith { id ->
                storages.getValue(id).entries(1L)
            }
            val reference = snapshots.values.firstOrNull() ?: return
            snapshots.entries.drop(1).forEach { (nodeId, entries) ->
                (1..minCommit).forEach { idx ->
                    val ref = reference.firstOrNull { it.index == idx }
                    val oth = entries.firstOrNull { it.index == idx }
                    if (ref != null && oth != null) {
                        assertTrue(ref.command.contentEquals(oth.command),
                            "State Machine Safety violated at index $idx between reference and $nodeId")
                    }
                }
            }
        }
    }

    fun leader(): RaftNode? = nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
    fun followers(): List<RaftNode> = nodes.values.filter { it.role.value is RaftRole.Follower }

    /**
     * Render a per-node diagnostic: role, term, commitIndex, log range, and the trace-event
     * histogram (Timeout / BecomeLeader / BecomeFollower) that makes leadership thrash and
     * term inflation obvious at a glance. Used as the message of the [AssertionError] thrown
     * by the await helpers on timeout, and callable directly from a failing assertion.
     */
    suspend fun dumpState(reason: String): String = buildString {
        appendLine("RaftSimulation state dump — $reason")
        nodeIds.forEach { id ->
            appendLine(nodeLine(id))
        }
        worstOffNode()?.let { id ->
            appendLine("Last $RECENT_EVENTS events for worst-off node $id:")
            recentEvents(id).forEach { appendLine("    $it") }
        }
    }

    private suspend fun nodeLine(id: NodeId): String {
        val node = nodes[id]
        val ring = traces[id].orEmpty()
        val state = if (node == null) "CRASHED" else {
            val role = node.role.value::class.simpleName
            "role=$role leader=${node.leader.value} commitIndex=${node.commitIndex.value}"
        }
        return "  $id: $state log=${logRange(id)} ${eventCounts(ring)}"
    }

    private suspend fun logRange(id: NodeId): String {
        val entries = storages.getValue(id).entries(1L)
        return if (entries.isEmpty()) "[]" else "[${entries.first().index}..${entries.last().index}]"
    }

    private fun eventCounts(ring: Collection<RaftTraceEvent>): String {
        val timeouts = ring.count { it is RaftTraceEvent.Timeout }
        val becameLeader = ring.count { it is RaftTraceEvent.BecomeLeader }
        val becameFollower = ring.count { it is RaftTraceEvent.BecomeFollower }
        return "Timeout=$timeouts BecomeLeader=$becameLeader BecomeFollower=$becameFollower"
    }

    /** The node with the most leadership churn (Timeout + BecomeLeader + BecomeFollower). */
    private fun worstOffNode(): NodeId? = traces.keys.maxByOrNull { id ->
        traces[id].orEmpty().count {
            it is RaftTraceEvent.Timeout ||
                it is RaftTraceEvent.BecomeLeader ||
                it is RaftTraceEvent.BecomeFollower
        }
    }

    private fun recentEvents(id: NodeId): List<RaftTraceEvent> =
        traces[id].orEmpty().toList().takeLast(RECENT_EVENTS)

    private companion object {
        const val TRACE_RING_CAPACITY = 256
        const val RECENT_EVENTS = 12
        val DEFAULT_AWAIT = 2.seconds
    }
}
