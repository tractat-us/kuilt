/**
 * # MultiNodeRaftSim — published multi-node Raft simulation harness
 *
 * This is the harness to reach for when writing a test that runs a real
 * [us.tractat.kuilt.raft.RaftNode] cluster. It handles the ceremony that every consensus test
 * needs but should not reinvent:
 *
 * - An in-process [MultiNodeRaftNetwork] with link drop / partition / heal controls.
 * - Per-node child scopes on [TestScope.backgroundScope] so the infinite election/heartbeat
 *   loops cancel cleanly when the test body finishes without an
 *   [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * - **Per-node distinct seeded [Random]** so nodes draw different election timeouts and
 *   symmetry-break into a leader. Without per-node seeds all nodes draw the same timeout,
 *   triggering split-vote storms that never converge.
 * - Bounded `await*` helpers that advance virtual time in 1 ms steps and **fail fast with a
 *   state dump on non-convergence**. Never call `advanceUntilIdle()` — the engine never quiesces.
 * - Partition/kill helpers for testing leader re-election and failure recovery.
 *
 * ## Setup ceremony
 *
 * ```kotlin
 * @OptIn(ExperimentalCoroutinesApi::class)
 * class MyClusterTest {
 *     @Test
 *     fun leaderElected() = raftSimTest(n = 3) { sim ->
 *         val leader = sim.awaitLeader()
 *         assertNotNull(leader)
 *     }
 * }
 * ```
 *
 * Or wire manually for full control:
 *
 * ```kotlin
 * @Test
 * fun leaderElectedManual() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
 *     val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
 *     val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
 *     val leader = sim.awaitLeader()
 *     assertNotNull(leader)
 * }
 * ```
 *
 * ## Determinism contract
 *
 * Run under [StandardTestDispatcher] (FIFO at each virtual instant) — **not**
 * `UnconfinedTestDispatcher`. `UnconfinedTestDispatcher` runs continuations *eagerly inline*, making
 * the ordering of timer fires vs message round-trips load-dependent even though every `delay()` is
 * already virtual. `StandardTestDispatcher` fixes that ordering. See `:kuilt-raft` issue #383.
 *
 * Use [raftSimTest] as the standard entry point: it wires `StandardTestDispatcher`, the 5 s timeout,
 * and hands a ready [MultiNodeRaftSim] into the test body.
 *
 * ## Non-converging clusters
 *
 * A cluster that never elects a leader (e.g. all nodes share one [Random] seed and draw identical
 * election timeouts) causes election/heartbeat timers to fire without bound. Under
 * `StandardTestDispatcher` this inflates virtual time while CPU stays pegged on scheduler
 * bookkeeping — the [runTest] timeout eventually fires, but only after a full 5 s wall-clock wait.
 * The `await*` helpers detect churn early via an election-cycle counter and fail fast with a
 * [dumpState] before the outer timeout fires. Symptom: `election thrash exceeded N cycles`.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-node Raft simulation harness for use in tests. See the file-level KDoc for the full
 * determinism contract and setup ceremony.
 *
 * @param nodeIds The ordered list of voter [NodeId]s forming the initial cluster config.
 * @param scope The test's [TestScope] — used for [withTimeout] bounds in the await helpers.
 * @param nodeScope Scope for node coroutines — pass [TestScope.backgroundScope] so the infinite
 *   heartbeat/election loops are cancelled at test teardown without
 *   [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * @param baseConfig Timing parameters for every node. [RaftConfig.random] is **overridden per node**
 *   (`Random(baseSeed + nodeIndex)`) to ensure distinct election timeouts and guarantee convergence.
 *   Must have [RaftConfig.expectVirtualTime] `= true` for tests running under a
 *   [kotlinx.coroutines.test.TestDispatcher]. Defaults to [MULTI_NODE_SIM_BASE_CONFIG].
 * @param baseSeed Seed from which per-node [Random]s are derived. Change to explore different
 *   election orderings; the default [MULTI_NODE_SIM_SEED] is stable across runs.
 * @param maxPayloadBytes Optional payload cap forwarded to [MultiNodeRaftNetwork] — forces
 *   InstallSnapshot chunking in tests that exercise the snapshot-transfer path.
 * @param nodeFactory Override to wire a custom [RaftNode] implementation. The default wires
 *   [CoroutineScope.raftNode] with a per-node seeded config. Parameters: `(id, transport,
 *   storage, childScope)`.
 */
public class MultiNodeRaftSim(
    public val nodeIds: List<NodeId>,
    private val scope: CoroutineScope,
    private val nodeScope: CoroutineScope,
    private val baseConfig: RaftConfig = MULTI_NODE_SIM_BASE_CONFIG,
    private val baseSeed: Long = MULTI_NODE_SIM_SEED,
    maxPayloadBytes: Int? = null,
    private val nodeFactory: (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode =
        defaultNodeFactory(nodeIds, baseConfig, baseSeed),
) {
    /** The in-process channel network shared by all nodes. Use to inject partitions or raw messages. */
    public val network: MultiNodeRaftNetwork = MultiNodeRaftNetwork(maxPayloadBytes)

    /** Per-node durable storage — each node's log, term, and vote. */
    public val storages: Map<NodeId, InMemoryRaftStorage> = nodeIds.associateWith { InMemoryRaftStorage() }

    private val scopes: MutableMap<NodeId, CoroutineScope> = mutableMapOf()

    /** Live node map — entries are removed on [crash] and re-added on [restart]. */
    public val nodes: MutableMap<NodeId, RaftNode> = mutableMapOf()

    private val traces: MutableMap<NodeId, ArrayDeque<RaftTraceEvent>> = mutableMapOf()
    private val stateMachines: MutableMap<NodeId, AppliedStateMachine> = mutableMapOf()

    /**
     * Monotonic count of election-cycle markers ([RaftTraceEvent.Timeout] + [RaftTraceEvent.BecomeLeader])
     * observed across every node. A converging scenario fires only a handful; runaway leadership thrash
     * inflates it without bound. See [tick] and [MAX_ELECTION_THRASH].
     */
    private var churnEvents: Long = 0L

    /**
     * Upper bound on [churnEvents] for the in-flight await, set by [awaitOrDump] to
     * `churnEvents + MAX_ELECTION_THRASH`. [tick] fails fast when it is exceeded.
     */
    private var churnDeadline: Long = Long.MAX_VALUE

    private fun startNode(id: NodeId) {
        val child = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
        scopes[id] = child
        val node = nodeFactory(id, network.transport(id), storages.getValue(id), child)
        nodes[id] = node
        collectTrace(id, node)
        collectStateMachine(id, node)
    }

    private fun collectStateMachine(id: NodeId, node: RaftNode) {
        val sm = AppliedStateMachine()
        stateMachines[id] = sm
        nodeScope.launch { node.committed.collect(sm::apply) }
    }

    /**
     * Collect [node]'s trace into a bounded ring buffer on [nodeScope] — NOT the test [scope].
     * A hot `trace.collect` on the test scope would never complete and trip
     * [kotlinx.coroutines.test.UncompletedCoroutinesError] at teardown.
     */
    private fun collectTrace(id: NodeId, node: RaftNode) {
        val ring = ArrayDeque<RaftTraceEvent>(TRACE_RING_CAPACITY)
        traces[id] = ring
        nodeScope.launch {
            node.trace.collect { event ->
                if (event is RaftTraceEvent.Timeout || event is RaftTraceEvent.BecomeLeader) churnEvents++
                if (ring.size >= TRACE_RING_CAPACITY) ring.removeFirst()
                ring.addLast(event)
            }
        }
    }

    /**
     * One virtual-time poll step: advance virtual time by 1 ms (driving engine actors one step),
     * then trip the election-churn bound if exceeded. A non-converging cluster fires this many
     * times per await but the bound detects it quickly rather than running out the full timeout.
     */
    private suspend fun tick() {
        if (churnEvents > churnDeadline) {
            throw AssertionError(withContext(NonCancellable) {
                dumpState("election thrash exceeded $MAX_ELECTION_THRASH cycles — cluster is not converging")
            })
        }
        delay(1)
    }

    // ── Cluster mutation ─────────────────────────────────────────────────────

    /** Cancel node [id]'s coroutine scope, simulating a crash. Use [restart] to bring it back up. */
    public fun crash(id: NodeId) {
        scopes[id]?.cancel()
        scopes.remove(id)
        nodes.remove(id)
    }

    /** Restart a previously [crash]ed node using its durable [storages] entry. */
    public fun restart(id: NodeId) { startNode(id) }

    /**
     * Partition nodes into two groups — messages in both directions between any node in [a] and any
     * node in [b] are silently dropped. Restore with [heal].
     */
    public fun partition(a: Set<NodeId>, b: Set<NodeId>): Unit = network.partition(a, b)

    /** Isolate [id] from every other node — the offline-follower scenario. Restore with [heal]. */
    public fun partitionOff(id: NodeId): Unit = partition(setOf(id), nodeIds.filter { it != id }.toSet())

    /** Restore all links cleared by [partition] or [dropLink]. */
    public fun heal(): Unit = network.heal()

    /** Drop messages from [from] to [to] (unidirectional). Restore with [heal]. */
    public fun dropLink(from: NodeId, to: NodeId): Unit = network.dropLink(from, to)

    // ── Bounded await helpers ────────────────────────────────────────────────
    // Build cluster-state assertions on these — never on raw flows or advanceUntilIdle().
    // Each helper advances virtual time in bounded 1 ms steps and fails fast with dumpState()
    // on non-convergence.

    /** Suspend until some node is [RaftRole.Leader]; return it, or fail fast with a state dump. */
    public suspend fun awaitLeader(within: Duration = DEFAULT_AWAIT): RaftNode =
        awaitOrDump("awaitLeader", within) { pollUntil { leader() } }

    /**
     * Suspend until a node whose id is in [among] is [RaftRole.Leader]. Use after a partition when
     * a stale leader from the minority partition may transiently report leader status — scope the
     * await to the surviving voters.
     */
    public suspend fun awaitLeader(among: Set<NodeId>, within: Duration = DEFAULT_AWAIT): RaftNode =
        awaitOrDump("awaitLeader(among=$among)", within) {
            pollUntil {
                nodes.entries
                    .firstOrNull { it.key in among && it.value.role.value is RaftRole.Leader }
                    ?.value
            }
        }

    /** Suspend until [index] is committed on every node in [on]; fail fast with a state dump otherwise. */
    public suspend fun awaitCommit(
        index: Long,
        on: Collection<NodeId> = nodeIds,
        within: Duration = DEFAULT_AWAIT,
    ) {
        awaitOrDump("awaitCommit($index) on $on", within) {
            pollUntil { true.takeIf { on.all { id -> committedTo(id) >= index } } }
        }
    }

    /** Suspend until node [id] holds [role]; fail fast with a state dump otherwise. */
    public suspend fun awaitRole(id: NodeId, role: RaftRole, within: Duration = DEFAULT_AWAIT) {
        awaitOrDump("awaitRole($id, $role)", within) {
            pollUntil { nodes[id]?.takeIf { it.role.value == role } }
        }
    }

    /** Suspend until [cond] holds (polled each virtual ms); fail fast with a state dump on timeout. */
    public suspend fun awaitTrue(what: String, within: Duration = DEFAULT_AWAIT, cond: () -> Boolean) {
        awaitOrDump(what, within) { pollUntil { true.takeIf { cond() } } }
    }

    /**
     * Propose against the current leader, re-acquiring and retrying on [NotLeaderException].
     * Returns the committed [us.tractat.kuilt.raft.LogEntry]. Restricts to [among] if provided.
     */
    public suspend fun proposeOnLeader(
        command: ByteArray,
        among: Set<NodeId>? = null,
        within: Duration = DEFAULT_AWAIT,
    ): LogEntry = awaitOrDump("proposeOnLeader", within) {
        while (true) {
            val l = leader()?.takeIf { among == null || nodeIdOf(it) in among }
            if (l == null) { tick(); continue }
            try {
                return@awaitOrDump l.propose(command)
            } catch (_: NotLeaderException) {
                tick()
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /**
     * Let pending work at the current virtual instant drain without advancing the clock. Under
     * [StandardTestDispatcher]'s FIFO scheduling, yielding hands the single test thread back so
     * already-scheduled coroutines (e.g. a freshly launched collector) run at *this* instant. Call
     * after [launch]ing a collector you need to observe the next emission.
     */
    public suspend fun settle(): Unit = repeat(10) { yield() }

    // ── Inspection helpers ───────────────────────────────────────────────────

    /** The current leader, or `null` if none is known. */
    public fun leader(): RaftNode? = nodes.values.firstOrNull { it.role.value is RaftRole.Leader }

    /** All nodes currently reporting [RaftRole.Follower]. */
    public fun followers(): List<RaftNode> = nodes.values.filter { it.role.value is RaftRole.Follower }

    /**
     * The applied state for [id] — ordered concatenation of committed commands, reset on a
     * snapshot install. Two nodes with equal applied state have applied the same sequence of commands.
     */
    public fun appliedState(id: NodeId): ByteArray = stateMachines.getValue(id).bytes()

    /**
     * Render a per-node diagnostic snapshot — roles, terms, commit indices, log ranges, and an
     * election-event histogram (Timeout / BecomeLeader / BecomeFollower) that makes leadership
     * thrash and term inflation visible at a glance. Used as the body of the [AssertionError]
     * thrown by the bounded await helpers on non-convergence, and callable directly from a
     * failing test assertion.
     */
    public suspend fun dumpState(reason: String): String = buildString {
        appendLine("MultiNodeRaftSim state dump — $reason")
        nodeIds.forEach { id -> appendLine(nodeLine(id)) }
        worstOffNode()?.let { id ->
            appendLine("Last $RECENT_EVENTS events for worst-off node $id:")
            recentEvents(id).forEach { appendLine("    $it") }
        }
    }

    /**
     * Assert election safety (at most one leader at a time) and state-machine safety (no two nodes
     * committed different commands at the same log index up to the minimum known commit).
     */
    public suspend fun checkInvariants() {
        val leaders = nodes.values.filter { it.role.value is RaftRole.Leader }
        assertTrue(leaders.size <= 1, "Election Safety violated: ${leaders.size} simultaneous leaders")
        val minCommit = nodes.values.minOfOrNull { it.commitIndex.value } ?: 0L
        if (minCommit <= 0L) return
        val snapshots = nodes.keys.associateWith { id -> storages.getValue(id).entries(1L) }
        val reference = snapshots.values.firstOrNull() ?: return
        snapshots.entries.drop(1).forEach { (nodeId, entries) ->
            (1..minCommit).forEach { idx ->
                val ref = reference.firstOrNull { it.index == idx }
                val oth = entries.firstOrNull { it.index == idx }
                if (ref != null && oth != null) {
                    assertTrue(
                        ref.command.contentEquals(oth.command),
                        "State Machine Safety violated at index $idx between reference and $nodeId",
                    )
                }
            }
        }
    }

    // ── Private internals ────────────────────────────────────────────────────

    private fun committedTo(id: NodeId): Long = nodes[id]?.commitIndex?.value ?: -1L

    private fun nodeIdOf(node: RaftNode): NodeId = nodes.entries.first { it.value === node }.key

    private suspend fun <T : Any> pollUntil(probe: () -> T?): T {
        while (true) {
            probe()?.let { return it }
            tick()
        }
    }

    private suspend fun <T> awaitOrDump(what: String, within: Duration, block: suspend () -> T): T {
        val priorDeadline = churnDeadline
        churnDeadline = churnEvents + MAX_ELECTION_THRASH
        return try {
            withTimeout(within) { block() }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(withContext(NonCancellable) { dumpState("$what timed out after $within") })
        } finally {
            churnDeadline = priorDeadline
        }
    }

    private suspend fun nodeLine(id: NodeId): String {
        val node = nodes[id]
        val ring = traces[id].orEmpty()
        val state = if (node == null) "CRASHED" else {
            "role=${node.role.value::class.simpleName} leader=${node.leader.value} commitIndex=${node.commitIndex.value}"
        }
        return "  $id: $state log=${logRange(id)} ${eventCounts(ring)}"
    }

    private suspend fun logRange(id: NodeId): String {
        val entries = storages.getValue(id).entries(1L)
        return if (entries.isEmpty()) "[]" else "[${entries.first().index}..${entries.last().index}]"
    }

    private fun eventCounts(ring: Collection<RaftTraceEvent>): String {
        val timeouts = ring.count { it is RaftTraceEvent.Timeout }
        val became = ring.count { it is RaftTraceEvent.BecomeLeader }
        val followed = ring.count { it is RaftTraceEvent.BecomeFollower }
        return "Timeout=$timeouts BecomeLeader=$became BecomeFollower=$followed"
    }

    private fun worstOffNode(): NodeId? = traces.keys.maxByOrNull { id ->
        traces[id].orEmpty().count {
            it is RaftTraceEvent.Timeout ||
                it is RaftTraceEvent.BecomeLeader ||
                it is RaftTraceEvent.BecomeFollower
        }
    }

    private fun recentEvents(id: NodeId): List<RaftTraceEvent> =
        traces[id].orEmpty().toList().takeLast(RECENT_EVENTS)

    /** Folds a node's [Committed] stream into opaque bytes for state-machine equality checks. */
    private class AppliedStateMachine {
        private val acc = ArrayList<Byte>()
        fun apply(c: Committed) = when (c) {
            is Committed.Entry -> acc.addAll(c.entry.command.asList())
            is Committed.Install -> { acc.clear(); acc.addAll(c.snapshot.state.asList()) }
        }
        fun bytes(): ByteArray = acc.toByteArray()
    }

    /** Declared last so all properties above are initialized before [startNode] runs. */
    init { nodeIds.forEach { startNode(it) } }

    private companion object {
        const val TRACE_RING_CAPACITY = 256
        const val RECENT_EVENTS = 12

        /**
         * Election-cycle markers a single await tolerates before [tick] fails fast with a dump.
         * Generous enough to absorb a handful of elections from legitimate crash/partition scenarios;
         * tight enough to trip on a cluster that never converges. Tune up only if a genuinely
         * converging scenario exceeds it.
         */
        const val MAX_ELECTION_THRASH = 60L

        val DEFAULT_AWAIT = 2.seconds
    }
}

// ── Module-level helpers ─────────────────────────────────────────────────────

/**
 * Stable seed for per-node [Random] derivation in [MultiNodeRaftSim]. Different from
 * `:kuilt-raft`'s internal test seed (383) so harness tests explore different election orderings
 * independently.
 */
public const val MULTI_NODE_SIM_SEED: Long = 485L

/**
 * Fast timing config for [MultiNodeRaftSim] tests — elections fire in single-digit virtual ms,
 * so a 3-node leader election completes in ≤ 20 ms of virtual time.
 *
 * [RaftConfig.random] is left as [Random.Default] here; [MultiNodeRaftSim] overrides it per-node
 * with `Random(baseSeed + nodeIndex)` so each node draws a distinct election timeout and
 * symmetry-breaking works correctly. The [RaftConfig.expectVirtualTime] flag suppresses the
 * real-dispatcher guard for tests running under a [kotlinx.coroutines.test.TestDispatcher].
 *
 * **Never use this config in production** — fast timings are meaningless on real networks and
 * [RaftConfig.expectVirtualTime] suppresses a safety guard.
 */
public val MULTI_NODE_SIM_BASE_CONFIG: RaftConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
)

/**
 * Build a [MultiNodeRaftSim] of [n] voters and run [body] under
 * `runTest(StandardTestDispatcher(), timeout = 5.seconds)` — the canonical harness for multi-node
 * Raft tests. See [MultiNodeRaftSim] for the full determinism contract.
 *
 * ```kotlin
 * @Test
 * fun leaderElected() = raftSimTest(n = 3) { sim ->
 *     val leader = sim.awaitLeader()
 *     assertNotNull(leader)
 * }
 *
 * @Test
 * fun partitionAndRecover() = raftSimTest(n = 3) { sim ->
 *     sim.awaitLeader()
 *     val leaderId = sim.leader()!!.let { l ->
 *         sim.nodes.entries.first { it.value === l }.key
 *     }
 *     sim.partitionOff(leaderId)
 *     val survivors = sim.nodeIds.filter { it != leaderId }.toSet()
 *     sim.awaitLeader(among = survivors)
 *     sim.heal()
 *     // The old leader rejoins as a follower
 *     sim.awaitRole(leaderId, RaftRole.Follower)
 * }
 * ```
 *
 * @param n Number of voters (default 3 — minimum for fault tolerance with one crash).
 * @param baseConfig Timing config forwarded to [MultiNodeRaftSim]. Defaults to [MULTI_NODE_SIM_BASE_CONFIG].
 * @param baseSeed Seed for per-node [Random] derivation. Defaults to [MULTI_NODE_SIM_SEED].
 * @param timeout Test timeout. Default 5 s — tight enough to surface hangs quickly. Widen only for
 *   tests that intentionally wait on multi-round operations (e.g. large-log compaction).
 */
public fun raftSimTest(
    n: Int = 3,
    baseConfig: RaftConfig = MULTI_NODE_SIM_BASE_CONFIG,
    baseSeed: Long = MULTI_NODE_SIM_SEED,
    timeout: Duration = 5.seconds,
    body: suspend TestScope.(MultiNodeRaftSim) -> Unit,
): TestResult = runTest(
    context = StandardTestDispatcher(),
    timeout = timeout,
) {
    val ids = (1..n).map { NodeId("v$it") }
    val sim = MultiNodeRaftSim(
        nodeIds = ids,
        scope = this,
        nodeScope = backgroundScope,
        baseConfig = baseConfig,
        baseSeed = baseSeed,
    )
    body(sim)
}

// ── Private factory ──────────────────────────────────────────────────────────

private fun defaultNodeFactory(
    nodeIds: List<NodeId>,
    baseConfig: RaftConfig,
    baseSeed: Long,
): (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode {
    val clusterConfig = ClusterConfig(voters = nodeIds.toSet())
    return { id, transport, storage, childScope ->
        val nodeIndex = nodeIds.indexOf(id).toLong()
        val nodeConfig = baseConfig.copy(random = Random(baseSeed + nodeIndex))
        childScope.raftNode(clusterConfig, transport, storage, nodeConfig)
    }
}
