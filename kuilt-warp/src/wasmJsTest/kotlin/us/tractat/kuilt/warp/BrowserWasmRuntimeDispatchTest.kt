/**
 * C3 go/no-go: real wasm execution via the browser [WebAssembly] JS API on wasmJs.
 *
 * Proves the browser wasm substrate end-to-end: a [TaskDescriptor] referencing an [OpId]
 * backed by the native browser WebAssembly API is dispatched through [WarpNode], executed
 * by the ring-owner peer, and the real wasm result merges onto both peers' boards.
 *
 * The kernel: `square(i32) → i32`, `n * n`. Args and result are 4-byte little-endian i32.
 * The same 43-byte binary as jvmTest/resources/square.wasm (#918), embedded as a
 * [byteArrayOf] literal in [BrowserWasmSquareOp] — classpath resources are not available
 * in the browser.
 *
 * Coroutine discipline mirrors [SymbolicDispatchTest] and [ChicoryRuntimeDispatchTest]:
 * [UnconfinedTestDispatcher] with bounded [advanceTimeBy] steps — never [advanceUntilIdle]
 * (anti-entropy timers re-arm forever).
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
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val C3_BROWSER_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun c3BrowserClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Bounded virtual-time advance: step through anti-entropy intervals to converge replication,
 * then past the RingWithIntent settle window, then a few more to let results merge back.
 * Never [advanceUntilIdle] (anti-entropy timers re-arm forever).
 */
private fun TestScope.settle() {
    repeat(6) { advanceTimeBy(C3_BROWSER_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(6) { advanceTimeBy(C3_BROWSER_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

class BrowserWasmRuntimeDispatchTest {

    /**
     * End-to-end: a wasm `square` kernel loaded via the browser [WebAssembly] API executes
     * on the ring-owner peer and the real result (`25` for input `5`) merges onto both boards.
     */
    @Test
    fun wasmSquareKernelRunsViaBrowserWebAssemblyAndResultMergesOnBothBoards() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c3-browser"))
            val seamB = loom.join(InMemoryTag("b"))

            val squareOpId = OpId("wasm:square")

            fun registryWith(op: Op) = OpRegistry().also { it.register(squareOpId, op) }

            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_BROWSER_QUILTER_CONFIG,
                clock = c3BrowserClock(testScheduler), registry = registryWith(browserSquareOp()),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = C3_BROWSER_QUILTER_CONFIG,
                clock = c3BrowserClock(testScheduler), registry = registryWith(browserSquareOp()),
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
                    assertEquals(expected, readI32Le(resultBytes), "square(5) via real wasm must equal 25")
                },
            )
        }
}
