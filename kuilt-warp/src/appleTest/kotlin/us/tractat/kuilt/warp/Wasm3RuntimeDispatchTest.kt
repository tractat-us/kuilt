/**
 * C3 go/no-go: real wasm execution via wasm3 on Apple Kotlin/Native targets.
 *
 * Proves the native wasm substrate end-to-end: a [TaskDescriptor] referencing an
 * [OpId] backed by the `square` wasm kernel is dispatched through [WarpNode],
 * executed by the ring-owner peer via wasm3 cinterop, and the real wasm result
 * merges onto both peers' boards.
 *
 * The kernel: `square(i32) → i32`, `n * n`. Args and result are 4-byte
 * little-endian i32. Mirrors [ChicoryRuntimeDispatchTest] on Apple targets.
 *
 * Coroutine discipline mirrors [SymbolicDispatchTest]: [UnconfinedTestDispatcher]
 * with bounded [advanceTimeBy] steps — never [advanceUntilIdle] (anti-entropy
 * timers re-arm forever).
 *
 * Executes on macosArm64 and iosSimulatorArm64. The iosArm64 (device) target
 * links against libwasm3.a but is not executed on CI (no hardware runner).
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
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

private fun TestScope.settle() =
    drainAntiEntropy(
        C3_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

class Wasm3RuntimeDispatchTest {

    /**
     * End-to-end: a wasm `square` kernel loaded via wasm3 executes on the ring-owner
     * peer and the real result (`25` for input `5`) merges onto both boards.
     *
     * Red (stub): [wasm3SquareOp] returns zeros → assertion `25 == 0` fails.
     * Green (wired): wasm3 runs real wasm → assertion passes.
     */
    @Test
    fun wasmSquareKernelRunsViaWasm3AndResultMergesOnBothBoards() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c3-wasm3"))
            val seamB = loom.join(InMemoryTag("b"))

            val squareOpId = OpId("wasm:square")

            fun registryWith(op: Op) = OpRegistry().also { it.register(squareOpId, op) }

            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_QUILTER_CONFIG,
                clock = c3Clock(testScheduler), registry = registryWith(wasm3SquareOp()),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_QUILTER_CONFIG,
                clock = c3Clock(testScheduler), registry = registryWith(wasm3SquareOp()),
            )

            val input = 5
            val taskId = TaskId("square-$input")
            nodeA.enqueue(taskId, TaskDescriptor(squareOpId, writeI32Le(input)))
            settle()

            val expected = input * input // 25
            assertAll(
                { assertNotNull(nodeA.results[taskId], "A must have the square result") },
                { assertNotNull(nodeB.results[taskId], "B must have the square result") },
                {
                    val resultBytes = checkNotNull(nodeA.results[taskId]).bytes
                    assertEquals(expected, readI32Le(resultBytes), "square(5) via wasm3 must equal 25")
                },
            )
        }
}
