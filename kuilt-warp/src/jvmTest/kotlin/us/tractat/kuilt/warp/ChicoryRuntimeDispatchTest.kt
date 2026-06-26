/**
 * C3 go/no-go: real wasm execution via Chicory on the JVM.
 *
 * Proves the JVM wasm substrate end-to-end: a [TaskDescriptor] referencing a [OpId] backed
 * by a pre-compiled `square.wasm` kernel is dispatched through [WarpNode], executed by the
 * ring owner via Chicory, and the real wasm result merges onto both peers' boards.
 *
 * The kernel: `square(i32) → i32`, `n * n`. Args and result are 4-byte little-endian i32.
 *
 * Coroutine discipline mirrors [SymbolicDispatchTest]: [UnconfinedTestDispatcher] with
 * bounded [advanceTimeBy] steps — never [advanceUntilIdle] (anti-entropy timers re-arm forever).
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val C3_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun c3Clock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private val C3_CADENCE = C3_QUILTER_CONFIG.antiEntropyInterval

class ChicoryRuntimeDispatchTest {

    /**
     * End-to-end: a wasm `square` kernel loaded via Chicory executes on the ring-owner peer
     * and the real result (`25` for input `5`) merges onto both boards.
     *
     * Red (stub): [chicorySquareOp] returns zeros → assertion `25 == 0` fails.
     * Green (wired): Chicory runs real wasm → assertion passes.
     */
    @Test
    fun wasmSquareKernelRunsViaChicoryAndResultMergesOnBothBoards() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c3-chicory"))
            val seamB = loom.join(InMemoryTag("b"))

            val squareOpId = OpId("wasm:square")

            fun registryWith(op: Op) = OpRegistry().also { it.register(squareOpId, op) }

            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_QUILTER_CONFIG,
                clock = c3Clock(testScheduler), registry = registryWith(chicorySquareOp()),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_QUILTER_CONFIG,
                clock = c3Clock(testScheduler), registry = registryWith(chicorySquareOp()),
            )

            val input = 5
            val taskId = TaskId("square-$input")
            nodeA.enqueue(taskId, TaskDescriptor(squareOpId, writeI32Le(input)))
            settleUntil(
                cadence = C3_CADENCE,
                describe = {
                    "A.result=${nodeA.results[taskId] != null} B.result=${nodeB.results[taskId] != null}"
                },
            ) { nodeA.results[taskId] != null && nodeB.results[taskId] != null }

            val expected = input * input // 25
            assertAll(
                { assertNotNull(nodeA.results[taskId], "A must have the square result") },
                { assertNotNull(nodeB.results[taskId], "B must have the square result") },
                {
                    val resultBytes = checkNotNull(nodeA.results[taskId]).bytes
                    assertEquals(expected, readI32Le(resultBytes), "square(5) via real wasm must equal 25")
                },
            )
        }
}
