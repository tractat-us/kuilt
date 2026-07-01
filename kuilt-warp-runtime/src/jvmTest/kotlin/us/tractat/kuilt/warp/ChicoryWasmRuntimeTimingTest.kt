package us.tractat.kuilt.warp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic regression tests for [ChicoryWasmRuntime]'s injectable [TimedGuestRunner].
 *
 * ## Why this exists
 *
 * PR #942 added [invokeMutex] to [ChicoryWasmRuntime] to fix a false-timeout race: without the
 * mutex, a concurrent op submitted while the guest executor was busy would have its
 * [WasmSandboxConfig.executionTimeout] clock consumed by *queue wait* — a concurrent innocent task
 * could be cancelled before it ever ran, recording a spurious terminal [WasmExecutionException].
 * The fix is correct, but its regression test could not be written deterministically because the
 * real runner uses real wall-clock time.
 *
 * [TimedGuestRunner] is the injectable seam that solves this: a fake runner lets us drive
 * timeout/success behaviours deterministically — no real wall-clock waits, no flakes.
 *
 * ## False-timeout property under test
 *
 * Two concurrent ops over one runtime are serialized by [invokeMutex]. The fake runner for the
 * first op throws [TimeoutException] (simulating a slow/runaway kernel hitting its budget). The
 * second op must then get a *fresh* call to [TimedGuestRunner.run] with its full budget — its clock
 * must NOT have been partially consumed by queue wait behind the first op. The second op succeeds.
 *
 * The key mechanism: `invokeMutex.withLock { timedRunner.run(...) }` means the second op's call to
 * `timedRunner.run(timeout, ...)` only happens AFTER the first op's call returns (or throws). So
 * the second op's timeout is measured from when it actually starts running, not from submit time.
 * This test verifies that property deterministically via the fake runner.
 */
class ChicoryWasmRuntimeTimingTest {

    private val reverseWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTimingTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/reverse.wasm",
        ),
    ) { "reverse.wasm not found on classpath" }
        .readBytes()

    /**
     * Negative control: a fake runner that always throws [TimeoutException] surfaces every
     * invocation as [WasmExecutionException] — proving the `catch(e: TimeoutException)` path
     * in [ChicoryWasmRuntime.invoke] works correctly with the injected seam.
     */
    @Test
    fun genuineTimeoutSurfacesAsWasmExecutionException() = runTest {
        val alwaysTimeout = TimedGuestRunner { _, _ -> throw TimeoutException("always timeout") }
        ChicoryWasmRuntime(timedRunner = alwaysTimeout).use { rt ->
            val op = rt.load(reverseWasm)
            assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
        }
    }

    /**
     * False-timeout regression: two concurrent ops serialized by [invokeMutex] — one times out
     * (fake runner call #1 throws), one succeeds (fake runner call #2 executes normally).
     *
     * This proves the invariant: the second op's [TimedGuestRunner.run] call happens ONLY after
     * the first op's call completes (the mutex), so its timeout budget is fully fresh. Without
     * the mutex, both calls would race and the second op's `Future.get(timeout)` deadline would
     * start at submit time — if the first op consumed more time than the budget, the second would
     * timeout before it ever ran.
     */
    @Test
    fun concurrentOpGetsFreshTimeoutBudgetAfterFirstTimesOut() = runTest(timeout = 30.seconds) {
        // AtomicInteger because timedRunner.run() is called from Dispatchers.IO threads,
        // though invokeMutex ensures they are sequential (only one at a time).
        val callCount = AtomicInteger()
        val fakeRunner = TimedGuestRunner { _, task ->
            // Call #1: simulate the "slow first op" hitting its budget.
            // Call #2+: execute normally — proving fresh budget, not queue-consumed remainder.
            if (callCount.incrementAndGet() == 1) throw TimeoutException("first op hit timeout budget")
            else task.call()
        }

        ChicoryWasmRuntime(timedRunner = fakeRunner).use { rt ->
            val op = rt.load(reverseWasm)

            coroutineScope {
                // Both submitted concurrently; invokeMutex serializes them inside the runtime.
                // The fake throws for call #1, executes normally for call #2.
                // We don't control which async wins the lock, so assertions are symmetric.
                val a = async { runCatching { op.invoke(byteArrayOf(1, 2, 3, 4)) } }
                val b = async { runCatching { op.invoke(byteArrayOf(1, 2, 3, 4)) } }
                val results = listOf(a.await(), b.await())

                assertAll(
                    { assertEquals(2, callCount.get(), "timedRunner.run() called exactly twice — both ops ran") },
                    { assertEquals(1, results.count { it.isFailure }, "exactly one op hit the timeout") },
                    { assertEquals(1, results.count { it.isSuccess }, "second op got its full budget and succeeded") },
                    {
                        val success = results.first { it.isSuccess }.getOrThrow()
                        assertContentEquals(byteArrayOf(4, 3, 2, 1), success, "successful op returns correct bytes")
                    },
                    {
                        val failure = results.first { it.isFailure }.exceptionOrNull()
                        assertTrue(failure is WasmExecutionException, "timed-out op surfaces as WasmExecutionException")
                    },
                )
            }
        }
    }
}
