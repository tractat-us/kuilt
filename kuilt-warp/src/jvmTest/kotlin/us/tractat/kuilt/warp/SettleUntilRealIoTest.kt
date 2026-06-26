/**
 * Regression guard for [settleUntil] (the #972 fix): the warp end-to-end tests run under virtual
 * time, but a real wasm kernel completes on a real `Dispatchers.IO` thread. The replaced fixed-pump
 * `settle()` advanced a constant number of virtual pumps and so RACED that real completion — under
 * load the result hadn't landed and `checkNotNull(result)` threw.
 *
 * This pins the new behaviour deterministically with a deliberately-slowed op: a kernel that burns a
 * fixed REAL wall-clock interval on `Dispatchers.IO` would lose against any fixed pump budget, yet
 * [settleUntil] still produces the result because it WAITS for the actual completion. The
 * `elapsed >= realDelay` assertion proves it genuinely waited rather than returning early by luck.
 *
 * This is the sanctioned real-threading test exception (CLAUDE.md): the file-level suppressions cover
 * the deliberate real `Dispatchers.IO` op and `Thread.sleep` that model the real-IO guest.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate real-IO op models the real Dispatchers.IO wasm guest

package us.tractat.kuilt.warp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val REAL_IO_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun realIoClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/** A square op that burns [realDelayMs] of REAL wall-clock on `Dispatchers.IO` — models a heavy guest. */
private fun slowSquareOp(realDelayMs: Long): Op = Op { args ->
    withContext(Dispatchers.IO) {
        Thread.sleep(realDelayMs)
        writeI32Le(readI32Le(args) * readI32Le(args))
    }
}

class SettleUntilRealIoTest {

    @Test
    fun settleUntilWaitsForASlowRealIoCompletion() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val realDelayMs = 300L
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("settle-realio"))
            val seamB = loom.join(InMemoryTag("b"))
            val opId = OpId("wasm:square")
            fun reg() = OpRegistry().also { it.register(opId, slowSquareOp(realDelayMs)) }
            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = REAL_IO_CONFIG,
                clock = realIoClock(testScheduler), registry = reg(),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = REAL_IO_CONFIG,
                clock = realIoClock(testScheduler), registry = reg(),
            )

            val taskId = TaskId("square-5")
            nodeA.enqueue(taskId, TaskDescriptor(opId, writeI32Le(5)))

            val startNanos = System.nanoTime()
            settleUntil(
                cadence = REAL_IO_CONFIG.antiEntropyInterval,
                describe = {
                    "A.result=${nodeA.results[taskId] != null} B.result=${nodeB.results[taskId] != null}"
                },
            ) { nodeA.results[taskId] != null && nodeB.results[taskId] != null }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            assertAll(
                { assertNotNull(nodeA.results[taskId], "A must have the result after the slow real-IO op") },
                { assertNotNull(nodeB.results[taskId], "result must converge onto B") },
                { assertEquals(25, readI32Le(checkNotNull(nodeA.results[taskId]).bytes), "square(5)=25") },
                {
                    assertTrue(
                        elapsedMs >= realDelayMs - 50,
                        "settleUntil must actually WAIT for the ${realDelayMs}ms real-IO op (waited ${elapsedMs}ms)",
                    )
                },
            )
        }
}
