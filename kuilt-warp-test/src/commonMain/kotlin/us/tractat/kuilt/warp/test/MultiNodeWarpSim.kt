/**
 * # MultiNodeWarpSim — published multi-node warp simulation harness
 *
 * This is the harness to reach for when writing a test that runs several coordination-free
 * [us.tractat.kuilt.warp.WarpNode]s against each other. It handles the ceremony every
 * multi-node warp test needs but should not reinvent (and, before this harness existed,
 * kept re-making with a hand-picked dispatcher — the #966 flake class):
 *
 * - An [InMemoryLoom] mesh with one [Seam] per node, woven before any node starts.
 * - Per-node child scopes on [kotlinx.coroutines.test.TestScope.backgroundScope] so the
 *   Quilter anti-entropy loops and heartbeat detectors cancel cleanly at teardown without
 *   an [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * - A **virtual clock** derived from the test scheduler, so ring-change stamps, claim
 *   settle windows, and heartbeat liveness all share one timeline with `delay(...)`.
 * - **Deterministic per-node seeding**: [InMemoryLoom] assigns stable `peer-1..peer-n`
 *   ids in join order, and [WarpNode] derives each internal Quilter's jitter RNG from its
 *   own [PeerId] — so every node's randomness is distinct and reproducible across runs.
 * - Bounded [settle] / `await*` helpers that advance virtual time in explicit steps and
 *   **fail fast with a state dump on non-convergence**. Never call `advanceUntilIdle()` —
 *   the anti-entropy timers re-arm forever, so the scheduler never quiesces.
 *
 * ## Setup ceremony
 *
 * ```kotlin
 * @OptIn(ExperimentalCoroutinesApi::class)
 * class MyMeshTest {
 *     @Test
 *     fun tasksConverge() = warpSimTest(n = 3) { sim ->
 *         val task = TaskId("t1")
 *         sim.enqueueEcho(task)
 *         sim.awaitResults(listOf(task))
 *     }
 * }
 * ```
 *
 * Or wire manually for full control:
 *
 * ```kotlin
 * @Test
 * fun tasksConvergeManual() = runTest(StandardTestDispatcher(), timeout = WARP_SIM_WEDGE_BACKSTOP) {
 *     val sim = multiNodeWarpSim(n = 3, nodeScope = backgroundScope, scheduler = testScheduler)
 *     try {
 *         sim.enqueueEcho(TaskId("t1"))
 *         sim.awaitResults(listOf(TaskId("t1")))
 *     } finally {
 *         sim.closeAll()
 *     }
 * }
 * ```
 *
 * ## Determinism contract
 *
 * Run under [StandardTestDispatcher] (FIFO at each virtual instant) — **not**
 * `UnconfinedTestDispatcher`. `UnconfinedTestDispatcher` runs continuations *eagerly inline*,
 * which makes the interleaving of anti-entropy timer fires vs Quilter delta round-trips
 * load-dependent even though every `delay()` is already virtual — the exact mechanism behind
 * the #966 flake class. `StandardTestDispatcher` fixes that ordering. Use [warpSimTest] as the
 * standard entry point: it wires `StandardTestDispatcher`, the [WARP_SIM_WEDGE_BACKSTOP]
 * wall-clock backstop, and hands a ready [MultiNodeWarpSim] into the test body.
 *
 * ## Non-convergence
 *
 * A mesh that never converges (a broken op, a partitioned owner nobody re-homes from, a
 * mis-wired roster) would otherwise run out the full `runTest` timeout and die with an opaque
 * `UncompletedCoroutinesError`. The `await*` helpers bound every wait in **virtual** time
 * (default 2 s) and throw an [AssertionError] carrying [dumpState] — per-node results-board
 * sizes, execution/failover/duplicate counters, and the execution log — so a hang becomes a
 * fast, legible failure.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp.test

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.TaskRing
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.warp.rosterSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Short-cadence [QuilterConfig] for [MultiNodeWarpSim] tests — anti-entropy converges in a few
 * hundred virtual ms rather than the multi-second production cadence.
 *
 * [QuilterConfig.expectVirtualTime] suppresses the real-dispatcher guard for tests running
 * under a [kotlinx.coroutines.test.TestDispatcher]. **Never use this config in production.**
 */
public val MULTI_NODE_WARP_SIM_QUILTER_CONFIG: QuilterConfig = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/** The pass-through op every [MultiNodeWarpSim.trackedEchoRegistry] registers. */
public val WARP_SIM_ECHO_OP: OpId = OpId("warp-sim:echo")

/**
 * Multi-node warp simulation harness for use in tests. See the file-level KDoc for the full
 * determinism contract and setup ceremony; construct via [multiNodeWarpSim] or — preferably —
 * run the whole test through [warpSimTest].
 *
 * The harness owns the mesh ([loom]), one [Seam] and one [WarpNode] per peer, and the shared
 * execution log the default tracked-echo registries record into.
 */
public class MultiNodeWarpSim internal constructor(
    /** The in-process mesh all seams are woven on. */
    public val loom: InMemoryLoom,
    seamList: List<Seam>,
    private val nodeScope: CoroutineScope,
    private val scheduler: TestCoroutineScheduler,
    /** The Quilter cadence every node runs on — [settle] derives its pump interval from it. */
    public val quilterConfig: QuilterConfig,
    private val heartbeatConfig: HeartbeatConfig,
    private val strategy: ClaimStrategy,
    registryFactory: (MultiNodeWarpSim, PeerId) -> OpRegistry,
    nodeFactory: ((MultiNodeWarpSim, PeerId, Seam, CoroutineScope) -> WarpNode)?,
) {
    /** Peer ids in join order (`peer-1` hosts; the rest joined in index order). */
    public val peerIds: List<PeerId> = seamList.map { it.selfId }

    /** Each peer's [Seam] on the mesh. */
    public val seams: Map<PeerId, Seam> = seamList.associateBy { it.selfId }

    private val scopes: MutableMap<PeerId, CoroutineScope> = mutableMapOf()

    /** Live node map — entries are removed by [disconnect]. */
    public val nodes: MutableMap<PeerId, WarpNode> = mutableMapOf()

    // Guards the execution log; ops may be invoked from any node coroutine.
    private val lock = reentrantLock()
    private val executionLog = mutableMapOf<TaskId, MutableList<PeerId>>()

    /**
     * A virtual clock reading the test scheduler's current time — the clock every default
     * node runs on. Pass it to any extra [WarpNode] a custom [nodeFactory] builds so ring
     * stamps and settle windows share the test's virtual timeline.
     */
    public val virtualClock: () -> Instant =
        { Instant.fromEpochMilliseconds(scheduler.currentTime) }

    init {
        peerIds.forEach { id ->
            val child = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
            scopes[id] = child
            val seam = seams.getValue(id)
            nodes[id] = nodeFactory?.invoke(this, id, seam, child) ?: WarpNode(
                selfId = id,
                seam = seam,
                rosterFlow = seam.rosterSnapshot(),
                scope = child,
                quilterConfig = quilterConfig,
                clock = virtualClock,
                heartbeatConfig = heartbeatConfig,
                strategy = strategy,
                registry = registryFactory(this, id),
                epoch = 0L,
            )
        }
    }

    // ── Node & task access ───────────────────────────────────────────────────

    /** The peer id at join-order [index] (0-based; index 0 is the host). */
    public fun peer(index: Int): PeerId = peerIds[index]

    /** The live node at join-order [index]. Fails if it was [disconnect]ed. */
    public fun node(index: Int): WarpNode = nodes.getValue(peer(index))

    /**
     * A [TaskId] (`"$prefix-<i>"`) that the consistent-hash ring assigns to [owner] under the
     * full-mesh roster — lets a test target a specific node deterministically. Matches the
     * ring [WarpNode] builds ([TaskRing] defaults) while all peers are live and unpartitioned.
     */
    public fun taskOwnedBy(owner: PeerId, prefix: String = "task"): TaskId {
        require(owner in peerIds) { "$owner is not a peer of this sim ($peerIds)" }
        val ring = TaskRing(peerIds.toSet())
        return generateSequence(0) { it + 1 }
            .map { TaskId("$prefix-$it") }
            .first { ring.owner(it) == owner }
    }

    // ── Tracked echo ops ─────────────────────────────────────────────────────

    /**
     * An [OpRegistry] with one pass-through op, [WARP_SIM_ECHO_OP], that records every
     * invocation into the sim-wide execution log ([executedBy] / [executedTaskIds]) before
     * echoing its args. The default registry for every node; call from a custom
     * `registryFactory` to keep tracking while adding further ops.
     */
    public fun trackedEchoRegistry(peer: PeerId): OpRegistry = OpRegistry().also { registry ->
        registry.register(
            WARP_SIM_ECHO_OP,
            Op { args ->
                recordExecution(peer, TaskId(args.decodeToString()))
                args
            },
        )
    }

    /**
     * Record that [peer] executed [taskId]. Called by [trackedEchoRegistry] ops; call it from
     * custom ops to keep [executedBy] / [executedTaskIds] (and [dumpState]) accurate.
     */
    public fun recordExecution(peer: PeerId, taskId: TaskId) {
        lock.withLock { executionLog.getOrPut(taskId) { mutableListOf() }.add(peer) }
    }

    /** The [TaskDescriptor] [enqueueEcho] uses: [WARP_SIM_ECHO_OP] with the task id as args. */
    public fun echoDescriptor(taskId: TaskId): TaskDescriptor =
        TaskDescriptor(op = WARP_SIM_ECHO_OP, args = taskId.value.encodeToByteArray())

    /** Enqueue [taskId] as a tracked echo task on the node of [on] (default: the host). */
    public fun enqueueEcho(taskId: TaskId, on: PeerId = peerIds.first()) {
        nodes.getValue(on).enqueue(taskId, echoDescriptor(taskId))
    }

    /** Every peer that executed [taskId], in execution order (empty if none has yet). */
    public fun executedBy(taskId: TaskId): List<PeerId> =
        lock.withLock { executionLog[taskId]?.toList().orEmpty() }

    /** All task ids that have been executed by at least one peer. */
    public fun executedTaskIds(): Set<TaskId> = lock.withLock { executionLog.keys.toSet() }

    // ── Mesh mutation ────────────────────────────────────────────────────────

    /**
     * Remove [id] from the mesh: close its node, tear its seam (it disappears from every
     * other peer's roster), and cancel its coroutines. The departed-peer scenario — surviving
     * nodes rebuild their rings and re-home the peer's tasks.
     */
    public suspend fun disconnect(id: PeerId) {
        nodes.remove(id)?.close()
        seams.getValue(id).close()
        scopes.remove(id)?.cancel()
    }

    /** Close every live node and cancel its scope. Called by [warpSimTest] at teardown. */
    public fun closeAll() {
        nodes.values.forEach { it.close() }
        nodes.clear()
        scopes.values.forEach { it.cancel() }
        scopes.clear()
    }

    // ── Bounded settle / await helpers ───────────────────────────────────────
    // Build convergence assertions on these — never on raw flows or advanceUntilIdle()
    // (the Quilter anti-entropy loops re-arm forever, so the scheduler never idles).

    /**
     * Advance virtual time through [rounds] anti-entropy intervals, one claim settle window
     * (when [strategy] has one), then [rounds] more intervals — the bounded, fixed-budget
     * drain for "let the mesh converge / prove it stays quiet" phases. For waits with a
     * concrete target, prefer [awaitResults] / [awaitTrue]: they finish as soon as the
     * condition holds and fail fast with a dump when it never does.
     */
    public suspend fun settle(rounds: Int = DEFAULT_SETTLE_ROUNDS) {
        require(rounds >= 0) { "rounds must be >= 0, was $rounds" }
        repeat(rounds) { pump() }
        val window = (strategy as? ClaimStrategy.RingWithIntent)?.settleWindow ?: Duration.ZERO
        if (window > Duration.ZERO) {
            delay(window)
            drainInstant()
        }
        repeat(rounds) { pump() }
    }

    /**
     * Suspend until every node in [on] has a result for every id in [taskIds]; fail fast
     * with a state dump otherwise. The standard "tasks executed and boards converged" await.
     */
    public suspend fun awaitResults(
        taskIds: Collection<TaskId>,
        on: Collection<PeerId> = nodes.keys.toList(),
        within: Duration = DEFAULT_AWAIT,
    ) {
        awaitTrue("awaitResults(${taskIds.size} tasks) on $on", within) {
            on.all { id ->
                val node = nodes[id] ?: return@all false
                taskIds.all { taskId -> node.results[taskId] != null }
            }
        }
    }

    /**
     * Suspend until [cond] holds, polling every virtual millisecond; fail fast with a state
     * dump after [within] **virtual** time otherwise. The generic bounded await underneath
     * [awaitResults].
     */
    public suspend fun awaitTrue(what: String, within: Duration = DEFAULT_AWAIT, cond: () -> Boolean) {
        try {
            withTimeout(within) {
                while (!cond()) delay(TICK)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(dumpState("$what timed out after $within (virtual)"))
        }
    }

    /**
     * Render a per-node diagnostic snapshot — results-board sizes, execution / failover /
     * duplicate counters, and the tracked execution log — so a non-converging mesh names
     * itself instead of just timing out. Used as the body of the [AssertionError] thrown by
     * the bounded await helpers, and callable directly from a failing assertion.
     */
    public fun dumpState(reason: String): String = buildString {
        appendLine("MultiNodeWarpSim state dump — $reason")
        peerIds.forEach { id ->
            val node = nodes[id]
            if (node == null) {
                appendLine("  $id: DISCONNECTED")
            } else {
                val boardIds = node.results.taskIds
                val shown = boardIds.take(DUMP_TASKS_SHOWN).joinToString { it.value }
                val more = if (boardIds.size > DUMP_TASKS_SHOWN) ", …" else ""
                appendLine(
                    "  $id: results=${boardIds.size} [$shown$more] " +
                        "executions=${node.executions.value} failovers=${node.failovers.value} " +
                        "duplicates=${node.duplicates.value}",
                )
            }
        }
        val log = lock.withLock { executionLog.mapValues { (_, peers) -> peers.toList() } }
        appendLine("  executionLog: ${log.size} tasks executed")
        log.entries.filter { it.value.size > 1 }.forEach { (taskId, peers) ->
            appendLine("    ${taskId.value} executed ${peers.size}x by $peers")
        }
    }

    // ── Private internals ────────────────────────────────────────────────────

    /** One anti-entropy interval of virtual time, then drain the boundary instant. */
    private suspend fun pump() {
        delay(quilterConfig.antiEntropyInterval)
        drainInstant()
    }

    /**
     * Let pending work at the current virtual instant drain without advancing the clock:
     * under [StandardTestDispatcher]'s FIFO scheduling, yielding hands the single test
     * thread back so already-scheduled coroutines run at *this* instant.
     */
    private suspend fun drainInstant(): Unit = repeat(DRAIN_YIELDS) { yield() }

    private companion object {
        val TICK = 1.milliseconds
        val DEFAULT_AWAIT = 2.seconds
        const val DEFAULT_SETTLE_ROUNDS = 5
        const val DRAIN_YIELDS = 10
        const val DUMP_TASKS_SHOWN = 8
    }
}

/**
 * Weave an [n]-peer [InMemoryLoom] mesh and stand up one coordination-free [WarpNode] per
 * peer, returning the ready [MultiNodeWarpSim]. Prefer [warpSimTest], which also wires
 * `runTest(StandardTestDispatcher(), timeout = WARP_SIM_WEDGE_BACKSTOP)` and teardown; use this
 * directly only when the test needs to own the `runTest` invocation.
 *
 * @param n Number of peers (the first hosts the mesh; the rest join).
 * @param nodeScope Scope for node coroutines — pass
 *   [kotlinx.coroutines.test.TestScope.backgroundScope] so the re-arming anti-entropy and
 *   heartbeat loops are cancelled at test teardown.
 * @param scheduler The test scheduler ([kotlinx.coroutines.test.TestScope.testScheduler]) —
 *   backs [MultiNodeWarpSim.virtualClock] so node clocks track virtual time.
 * @param quilterConfig Anti-entropy cadence for every node. Defaults to the short-cadence
 *   [MULTI_NODE_WARP_SIM_QUILTER_CONFIG].
 * @param heartbeatConfig Heartbeat liveness timing for every node. Defaults to production
 *   timing (5 s / 15 s / 60 s) — virtual, so it is free; inject a short cadence to test
 *   partition detection.
 * @param strategy Claim strategy for every node. Defaults to [ClaimStrategy.RingWithIntent].
 * @param registryFactory Builds each node's [OpRegistry]. Defaults to
 *   [MultiNodeWarpSim.trackedEchoRegistry] — a pass-through echo op that records into the
 *   sim's execution log. Ignored for nodes built by a custom [nodeFactory].
 * @param nodeFactory Override to wire a custom [WarpNode] (e.g. a `lazyFetch`-capable node).
 *   Parameters: `(sim, id, seam, childScope)`. Use `sim.virtualClock`, `sim.quilterConfig`,
 *   and `seam.rosterSnapshot()` to stay on the harness's virtual timeline.
 */
public suspend fun multiNodeWarpSim(
    n: Int,
    nodeScope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    quilterConfig: QuilterConfig = MULTI_NODE_WARP_SIM_QUILTER_CONFIG,
    heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(),
    registryFactory: (MultiNodeWarpSim, PeerId) -> OpRegistry = { sim, id -> sim.trackedEchoRegistry(id) },
    nodeFactory: ((MultiNodeWarpSim, PeerId, Seam, CoroutineScope) -> WarpNode)? = null,
): MultiNodeWarpSim {
    require(n >= 1) { "n must be >= 1, was $n" }
    val loom = InMemoryLoom()
    val seams = buildList {
        add(loom.host(Pattern("multi-node-warp-sim")))
        repeat(n - 1) { i -> add(loom.join(InMemoryTag("multi-node-warp-sim-joiner-$i"))) }
    }
    return MultiNodeWarpSim(
        loom = loom,
        seamList = seams,
        nodeScope = nodeScope,
        scheduler = scheduler,
        quilterConfig = quilterConfig,
        heartbeatConfig = heartbeatConfig,
        strategy = strategy,
        registryFactory = registryFactory,
        nodeFactory = nodeFactory,
    )
}

/**
 * Wall-clock backstop for [warpSimTest] — the budget for a genuine **wedge**, *not* a performance
 * assertion. Mirrors `RAFT_SIM_WEDGE_BACKSTOP` (`:kuilt-raft-test`) and `TEST_WEDGE_BACKSTOP`
 * (`:kuilt-test`), which carry the same contract.
 *
 * ## Why it is deliberately loose
 *
 * A [MultiNodeWarpSim] test runs entirely on virtual time: [StandardTestDispatcher], an in-memory
 * [InMemoryLoom] mesh, and node clocks driven from [TestScope.testScheduler]. There is **no
 * real-clock input anywhere on the execution path**, so the virtual trajectory — and therefore the
 * total quantity of real work — is identical on every run. Machine load can change only the
 * wall-clock *rate* at which that fixed work is retired.
 *
 * A tight wall-clock cap over a fixed quantity of work is therefore not an assertion about the code
 * at all. It asserts *"this host can retire N units of work in T seconds"*. Measured on a 16-core
 * box, an unchanged binary slowed **2.65×** between load 7–10 and load 21–36, against **1.8×** of
 * headroom under the previous 5 s cap — degradation exceeding headroom, i.e. a *deterministic* red
 * on a busy runner rather than a flake. Mutation-verified with the ceiling as the only variable:
 * 5 s → 4/4 FAIL, 30 s → 4/4 PASS (kuilt #1891).
 *
 * ## Do NOT tighten this back to a few seconds
 *
 * The instinct is that a tight timeout buys fast failure. Here it does not — fast failure is already
 * bought, *load-independently*, by the bounded `await*` helpers' `within` bound (2 s of **virtual**
 * time, immune to contention), and those throw an [AssertionError] carrying a full
 * [MultiNodeWarpSim.dumpState]. A tight outer cap fires *before* that can speak, producing a bare
 * `UncompletedCoroutinesError` with no mesh state at all. Tightening it adds no detector; it
 * pre-empts the legible ones with a load-sensitive false-red generator.
 *
 * What it *is* for is the residual case the virtual bounds cannot cover: a wedge **outside** the
 * bounded helpers — an unbounded `await`, a deadlocked hand-rolled `Channel` receive. 30 s still
 * bounds that to half a minute.
 */
public val WARP_SIM_WEDGE_BACKSTOP: Duration = 30.seconds

/**
 * Build a [MultiNodeWarpSim] of [n] peers and run [body] under
 * `runTest(StandardTestDispatcher(), timeout = WARP_SIM_WEDGE_BACKSTOP)` — the canonical harness for
 * multi-node coordination-free warp tests. Closes every node after [body]. See
 * [MultiNodeWarpSim] for the full determinism contract.
 *
 * ```kotlin
 * @Test
 * fun tasksConverge() = warpSimTest(n = 3) { sim ->
 *     val tasks = (1..6).map { TaskId("t$it") }
 *     tasks.forEach { sim.enqueueEcho(it) }
 *     sim.awaitResults(tasks)
 * }
 * ```
 *
 * @param n Number of peers (default 3).
 * @param quilterConfig Anti-entropy cadence, forwarded to [multiNodeWarpSim].
 * @param heartbeatConfig Heartbeat timing, forwarded to [multiNodeWarpSim].
 * @param strategy Claim strategy, forwarded to [multiNodeWarpSim].
 * @param registryFactory Per-node registry builder, forwarded to [multiNodeWarpSim].
 * @param nodeFactory Custom node builder, forwarded to [multiNodeWarpSim].
 * @param timeout Wall-clock **wedge backstop**, not a performance budget — read
 *   [WARP_SIM_WEDGE_BACKSTOP] before changing it, and in particular before tightening it. Fast
 *   failure is the job of the bounded `await*` helpers' virtual `within` bounds, which are
 *   load-independent and dump state.
 */
public fun warpSimTest(
    n: Int = 3,
    quilterConfig: QuilterConfig = MULTI_NODE_WARP_SIM_QUILTER_CONFIG,
    heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(),
    registryFactory: (MultiNodeWarpSim, PeerId) -> OpRegistry = { sim, id -> sim.trackedEchoRegistry(id) },
    nodeFactory: ((MultiNodeWarpSim, PeerId, Seam, CoroutineScope) -> WarpNode)? = null,
    timeout: Duration = WARP_SIM_WEDGE_BACKSTOP,
    body: suspend TestScope.(MultiNodeWarpSim) -> Unit,
): TestResult = runTest(
    context = StandardTestDispatcher(),
    timeout = timeout,
) {
    val sim = multiNodeWarpSim(
        n = n,
        nodeScope = backgroundScope,
        scheduler = testScheduler,
        quilterConfig = quilterConfig,
        heartbeatConfig = heartbeatConfig,
        strategy = strategy,
        registryFactory = registryFactory,
        nodeFactory = nodeFactory,
    )
    try {
        body(sim)
    } finally {
        sim.closeAll()
    }
}
