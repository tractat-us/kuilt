/**
 * C5b end-to-end: lazy fetch-load-run of a kernel a node never had.
 *
 * A node whose [OpRegistry] lacks `reverse` is assigned a `reverse` task it owns on the ring.
 * It fetches the `reverse` bobbin from a peer that holds the bytes (via the node-owned
 * [BobbinExchange] over a reserved mux channel), loads it under the real [ChicoryWasmRuntime]
 * sandbox, registers it for reuse, runs it, and the reversed-bytes [OpResult] merges onto both
 * boards. This is the first point a peer executes a kernel it did not compile or hold.
 *
 * Coordination-free path (no Raft). Coroutine discipline: [StandardTestDispatcher] (FIFO at each
 * virtual instant — deterministic ordering of the inter-node anti-entropy round-trips) with bounded
 * [advanceTimeBy] — never [advanceUntilIdle] (anti-entropy timers re-arm forever). These tests stood
 * up two timer-driven [WarpNode]s and previously ran on [kotlinx.coroutines.test.UnconfinedTestDispatcher],
 * whose eager-inline execution made convergence load-dependent and flaked under CPU load (#966); FIFO
 * ordering fixes it. The wasm guest runs on real wall-clock time inside the runtime; the `runTest`
 * timeout sits well above it.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

private val C5B_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun c5bClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

// Real-vs-virtual coupling: WarpNode's anti-entropy timers run under StandardTestDispatcher virtual
// time, but op.invoke suspends on a *real* Dispatchers.IO thread (the Chicory guest burns real
// wall-clock CPU — see ChicoryWasmRuntime.invoke). A purely virtual settle never waits for that IO
// crossing: advanceTimeBy/runCurrent are synchronous and return before a CPU-starved guest run has
// enqueued its result, so under load the fetch→load→run→record→converge race is lost and the board
// reads null. That is the #966 flake — it is the real-IO wall-clock dependency, not virtual ordering.
//
// [settleUntil] removes the race: it interleaves bounded virtual-time anti-entropy pumps with a
// *real* wall-clock yield so the guest IO can complete and resume onto the scheduler between pumps,
// returning the instant the board converges (fast path) and otherwise failing fast at [maxReal].
// The blind [settle] is retained only for phases with no in-flight IO (post-convergence stability;
// the symbolic-only standby case where no op ever executes).
private val REAL_IO_YIELD_MILLIS = 5L

private fun TestScope.settle() {
    repeat(8) { advanceTimeBy(C5B_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(8) { advanceTimeBy(C5B_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

private fun TestScope.settleUntil(maxReal: Duration = 4.seconds, converged: () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (true) {
        repeat(4) { advanceTimeBy(C5B_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
        advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
        if (converged() || start.elapsedNow() >= maxReal) return
        // Deliberate real wall-clock yield: the Chicory guest runs on a real Dispatchers.IO thread,
        // so the only way for the test thread to let it finish (and enqueue its result) is to pause
        // real time. Thread.sleep blocks only this single test thread, never the real IO worker.
        @Suppress("ForbiddenMethodCall") // deliberate: real-IO crossing yield, see settleUntil KDoc
        Thread.sleep(REAL_IO_YIELD_MILLIS)
    }
}

class LazyFetchAndRunTest {

    private val reverseWasm: ByteArray = checkNotNull(
        LazyFetchAndRunTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/reverse.wasm"),
    ) { "reverse.wasm not found on classpath" }
        .readBytes()

    private val importsWasm: ByteArray = checkNotNull(
        LazyFetchAndRunTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/imports.wasm"),
    ) { "imports.wasm not found on classpath" }
        .readBytes()

    private val trapWasm: ByteArray = checkNotNull(
        LazyFetchAndRunTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/trap.wasm"),
    ) { "trap.wasm not found on classpath" }
        .readBytes()

    /** A task id the ring assigns to [owner] under the [peers] roster (default seed/vnodes). */
    private fun taskOwnedBy(owner: PeerId, peers: Set<PeerId>): TaskId {
        val ring = TaskRing(peers)
        return generateSequence(0) { it + 1 }
            .map { TaskId("reverse-$it") }
            .first { ring.owner(it) == owner }
    }

    @Test
    fun fetchingNodeFetchesLoadsAndRunsAKernelItNeverHad() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c5b-lazyfetch"))
            val seamB = loom.join(InMemoryTag("b"))

            val reverseOpId = OpId("wasm:reverse")
            val reverseHash = Creel().put(reverseWasm)
            val opToBobbin = { op: OpId -> if (op == reverseOpId) reverseHash else null }

            // Serving node B pre-populates its creel with the bobbin bytes; fetching node A starts empty.
            val servingCreel = Creel().also { it.put(reverseWasm) }
            val registryA = OpRegistry() // lacks reverse — must lazy-fetch then cache here

            ChicoryWasmRuntime().use { runtimeA ->
                ChicoryWasmRuntime().use { runtimeB ->
                    val nodeA = WarpNode(
                        selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = registryA,
                        lazyFetch = WarpLazyFetch(Creel(), runtimeA, opToBobbin),
                    )
                    @Suppress("UNUSED_VARIABLE")
                    val nodeB = WarpNode(
                        selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = OpRegistry(), // also lacks reverse; serves bytes from its creel
                        lazyFetch = WarpLazyFetch(servingCreel, runtimeB, opToBobbin),
                    )

                    val input = "Hello, warp!".encodeToByteArray()
                    val taskId = taskOwnedBy(seamA.selfId, setOf(seamA.selfId, seamB.selfId))
                    nodeA.enqueue(taskId, TaskDescriptor(reverseOpId, input))
                    settleUntil { nodeA.results[taskId] != null && nodeB.results[taskId] != null }

                    val expected = input.reversedArray()
                    val resultA = nodeA.results[taskId]
                    val resultB = nodeB.results[taskId]
                    assertAll(
                        { assertNotNull(resultA, "A must have the reverse result") },
                        { assertNotNull(resultB, "B must have the reverse result") },
                        { assertFalse(checkNotNull(resultA).isError, "result must not be a terminal error") },
                        { assertContentEquals(expected, checkNotNull(resultA).bytes, "A: reversed bytes") },
                        { assertContentEquals(expected, checkNotNull(resultB).bytes, "B: reversed bytes") },
                        {
                            assertTrue(
                                reverseOpId in registryA.registered,
                                "A's registry must cache the fetched op for reuse",
                            )
                        },
                    )
                }
            }
        }

    /**
     * C5b terminal-error (load-time): a node fetches [imports.wasm], [WasmRuntime.load] throws
     * [WasmLoadException], and [WarpNode] records a terminal [OpResult.failure] that converges
     * onto both boards.  The second [settle] must produce no churn — the board is stable and the
     * executions counter does not advance again, proving anti-entropy is not hot-looping on the
     * failed task.
     */
    @Test
    fun loadTimeBrokenKernelRecordsTerminalErrorOnBothBoards() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c5b-terminal-load"))
            val seamB = loom.join(InMemoryTag("b"))

            val badOpId = OpId("wasm:imports-broken")
            val badHash = Creel().put(importsWasm)
            val opToBobbin = { op: OpId -> if (op == badOpId) badHash else null }

            // B holds the broken bytes so A can fetch them; A's creel starts empty.
            val servingCreel = Creel().also { it.put(importsWasm) }

            ChicoryWasmRuntime().use { runtimeA ->
                ChicoryWasmRuntime().use { runtimeB ->
                    val nodeA = WarpNode(
                        selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = OpRegistry(),
                        lazyFetch = WarpLazyFetch(Creel(), runtimeA, opToBobbin),
                    )
                    @Suppress("UNUSED_VARIABLE")
                    val nodeB = WarpNode(
                        selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = OpRegistry(),
                        lazyFetch = WarpLazyFetch(servingCreel, runtimeB, opToBobbin),
                    )

                    val taskId = taskOwnedBy(seamA.selfId, setOf(seamA.selfId, seamB.selfId))
                    nodeA.enqueue(taskId, TaskDescriptor(badOpId, ByteArray(0)))
                    settleUntil { nodeA.results[taskId] != null && nodeB.results[taskId] != null }

                    val resultA = nodeA.results[taskId]
                    val resultB = nodeB.results[taskId]
                    val executionsAfterFirstSettle = nodeA.executions.value
                    assertAll(
                        { assertNotNull(resultA, "A must record a terminal error") },
                        { assertNotNull(resultB, "terminal error must converge onto B") },
                        { assertTrue(checkNotNull(resultA).isError, "A's result must be a terminal error") },
                        { assertTrue(checkNotNull(resultB).isError, "B's converged result must be terminal error") },
                        {
                            assertTrue(
                                executionsAfterFirstSettle > 0L,
                                "executions counter advanced — implies task removed from queue",
                            )
                        },
                    )

                    // Second settle must not produce churn — no retry storm on the broken bobbin.
                    settle()
                    assertAll(
                        {
                            assertEquals(
                                resultA, nodeA.results[taskId],
                                "A's board must be stable after second settle",
                            )
                        },
                        {
                            assertEquals(
                                resultB, nodeB.results[taskId],
                                "B's board must be stable after second settle",
                            )
                        },
                        {
                            assertEquals(
                                executionsAfterFirstSettle, nodeA.executions.value,
                                "executions counter must not advance again — no retry storm",
                            )
                        },
                    )
                }
            }
        }

    /**
     * C5b terminal-error (run-time): [trap.wasm] loads cleanly but traps during [Op.invoke],
     * surfacing as [WasmExecutionException].  Same convergence + stability assertions as the
     * load-time case, exercising the second [recordTerminalError] call site in
     * [WarpNode.executeViaRegistry].
     */
    @Test
    fun runTimeTrapKernelRecordsTerminalErrorOnBothBoards() =
        runTest(StandardTestDispatcher(), timeout = 10.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c5b-terminal-run"))
            val seamB = loom.join(InMemoryTag("b"))

            val trapOpId = OpId("wasm:trap-kernel")
            val trapHash = Creel().put(trapWasm)
            val opToBobbin = { op: OpId -> if (op == trapOpId) trapHash else null }

            val servingCreel = Creel().also { it.put(trapWasm) }

            ChicoryWasmRuntime().use { runtimeA ->
                ChicoryWasmRuntime().use { runtimeB ->
                    val nodeA = WarpNode(
                        selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = OpRegistry(),
                        lazyFetch = WarpLazyFetch(Creel(), runtimeA, opToBobbin),
                    )
                    @Suppress("UNUSED_VARIABLE")
                    val nodeB = WarpNode(
                        selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                        scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                        clock = c5bClock(testScheduler),
                        registry = OpRegistry(),
                        lazyFetch = WarpLazyFetch(servingCreel, runtimeB, opToBobbin),
                    )

                    val taskId = taskOwnedBy(seamA.selfId, setOf(seamA.selfId, seamB.selfId))
                    nodeA.enqueue(taskId, TaskDescriptor(trapOpId, ByteArray(0)))
                    settleUntil { nodeA.results[taskId] != null && nodeB.results[taskId] != null }

                    val resultA = nodeA.results[taskId]
                    val resultB = nodeB.results[taskId]
                    val executionsAfterFirstSettle = nodeA.executions.value
                    assertAll(
                        { assertNotNull(resultA, "A must record a terminal error on trap") },
                        { assertNotNull(resultB, "trap terminal error must converge onto B") },
                        { assertTrue(checkNotNull(resultA).isError, "A's result must be a terminal error") },
                        { assertTrue(checkNotNull(resultB).isError, "B's converged result must be terminal error") },
                        {
                            assertTrue(
                                executionsAfterFirstSettle > 0L,
                                "executions counter advanced — implies task removed from queue",
                            )
                        },
                    )

                    // Second settle must be stable — trap kernel must not be retried.
                    settle()
                    assertAll(
                        {
                            assertEquals(
                                resultA, nodeA.results[taskId],
                                "A's board must be stable after second settle",
                            )
                        },
                        {
                            assertEquals(
                                resultB, nodeB.results[taskId],
                                "B's board must be stable after second settle",
                            )
                        },
                        {
                            assertEquals(
                                executionsAfterFirstSettle, nodeA.executions.value,
                                "executions counter must not advance again — no retry storm",
                            )
                        },
                    )
                }
            }
        }

    /**
     * C5b regression guard: a symbolic-only node (no [WarpLazyFetch]) assigned an op its registry
     * lacks must **stand by** — the task stays pending, no result is recorded, no crash occurs.
     *
     * This is the pre-C5b behaviour that the lazy-fetch wiring must not change for null-[lazyFetch]
     * nodes.  Mirrors [SymbolicDispatchTest.ownerWithoutTheOpStandsByEvenWhenEnqueuerHasIt]; the
     * key distinction is that a fetchable bobbin mapping exists but the node cannot use it.
     */
    @Test
    fun symbolicOnlyNodeStandsByWhenLazyFetchAbsent() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c5b-symbolic-only"))
            val seamB = loom.join(InMemoryTag("b"))

            // An op backed by a real bobbin — but no node has lazyFetch wired.
            val fetchableOpId = OpId("wasm:fetchable-but-no-capability")

            // No lazyFetch on either node — symbolic-only configuration.
            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                clock = c5bClock(testScheduler),
                registry = OpRegistry(),
            )
            @Suppress("UNUSED_VARIABLE")
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C5B_QUILTER_CONFIG,
                clock = c5bClock(testScheduler),
                registry = OpRegistry(),
            )

            val taskId = taskOwnedBy(seamA.selfId, setOf(seamA.selfId, seamB.selfId))
            nodeA.enqueue(taskId, TaskDescriptor(fetchableOpId, ByteArray(0)))
            settle()

            assertAll(
                { assertNull(nodeA.results[taskId], "A lacks lazyFetch — must stand by, no result") },
                { assertNull(nodeB.results[taskId], "no result on B when owner stands by") },
            )
        }
}
