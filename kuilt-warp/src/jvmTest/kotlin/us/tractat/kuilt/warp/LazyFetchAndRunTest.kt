/**
 * C5b end-to-end: lazy fetch-load-run of a kernel a node never had.
 *
 * A node whose [OpRegistry] lacks `reverse` is assigned a `reverse` task it owns on the ring.
 * It fetches the `reverse` bobbin from a peer that holds the bytes (via the node-owned
 * [BobbinExchange] over a reserved mux channel), loads it under the real [ChicoryWasmRuntime]
 * sandbox, registers it for reuse, runs it, and the reversed-bytes [OpResult] merges onto both
 * boards. This is the first point a peer executes a kernel it did not compile or hold.
 *
 * Coordination-free path (no Raft). Coroutine discipline mirrors [ChicoryRuntimeDispatchTest]:
 * [UnconfinedTestDispatcher] with bounded [advanceTimeBy] — never [advanceUntilIdle] (anti-entropy
 * timers re-arm forever). The wasm guest runs on real wall-clock time inside the runtime; the
 * `runTest` timeout sits well above it.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val C5B_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun c5bClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() {
    repeat(8) { advanceTimeBy(C5B_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(8) { advanceTimeBy(C5B_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

class LazyFetchAndRunTest {

    private val reverseWasm: ByteArray = checkNotNull(
        LazyFetchAndRunTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/reverse.wasm"),
    ) { "reverse.wasm not found on classpath" }
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
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
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
                    settle()

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
}
