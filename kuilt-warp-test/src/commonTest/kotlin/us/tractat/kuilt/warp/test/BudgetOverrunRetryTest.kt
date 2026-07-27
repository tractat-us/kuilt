package us.tractat.kuilt.warp.test

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.WasmExecutionException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic tests for [retryingOnlyBudgetOverruns] — the discrimination that lets #1739's two
 * `:kuilt-warp-runtime` tests tolerate a saturated host without tolerating a broken runtime.
 *
 * These carry **no wall clock**: the scenario is a lambda that throws exactly the failure under
 * examination, so the property that matters — *which* failures are absorbed and which are fatal —
 * is pinned by construction rather than by hoping a build machine is busy. The real-CPU proof that
 * a runtime still recovers from a timeout stays in
 * [WasmRuntimeConformanceSuite.timeoutDoesNotPoisonSubsequentInvokes].
 */
class BudgetOverrunRetryTest {

    /**
     * A *transient* overrun — the signature of a contended host — is absorbed, and the reference
     * probe is never paid for on the way to a pass.
     */
    @Test
    fun transientBudgetOverrunIsRetriedUntilItSucceeds(): TestResult = runTest {
        var attempts = 0
        var probed = 0
        val result = retryingOnlyBudgetOverruns(
            what = "a scenario that overruns twice",
            budget = 200.milliseconds,
            referenceInvoke = { probed++ },
        ) {
            attempts++
            if (attempts < 3) throw budgetOverrun()
            "recovered"
        }
        assertAll(
            { assertEquals("recovered", result, "the scenario's value is returned once it succeeds") },
            { assertEquals(3, attempts, "the two overruns were retried, the third attempt returned") },
            { assertEquals(0, probed, "the host is only priced when every attempt has overrun") },
        )
    }

    /**
     * A *persistent* overrun is a real defect — a runtime that no longer frees its guest worker
     * overruns on every attempt — so the bounded retry still fails, and the message carries both
     * candidate diagnoses plus the freshly measured host cost that separates them.
     */
    @Test
    fun persistentBudgetOverrunStillFailsAndSaysWhichDiagnosisApplies(): TestResult = runTest {
        var attempts = 0
        val failure = assertFailsWith<AssertionError> {
            retryingOnlyBudgetOverruns(
                what = "a scenario that always overruns",
                budget = 200.milliseconds,
                attempts = 3,
                referenceInvoke = { },
            ) {
                attempts++
                throw budgetOverrun()
            }
        }
        val message = failure.message ?: ""
        assertAll(
            { assertEquals(3, attempts, "every attempt was spent before failing") },
            { assertContains(message, "all 3 attempts") },
            { assertContains(message, "attempt 3/3 overran") },
            { assertContains(message, "REAL defect", ignoreCase = false) },
            { assertContains(message, "measuring machine load") },
            { assertContains(message, "fresh, generously-budgeted runtime took") },
        )
    }

    /**
     * The load-bearing half: an execution failure that is **not** a budget overrun — a trap, a
     * stale interrupt, a rejected task on a dead worker — is exactly how a poisoned runtime
     * presents, so it must be fatal on the first attempt and reach the reader unwrapped.
     */
    @Test
    fun aFailureThatIsNotABudgetOverrunIsNotRetried(): TestResult = runTest {
        var attempts = 0
        val thrown = assertFailsWith<WasmExecutionException> {
            retryingOnlyBudgetOverruns(
                what = "a scenario that traps",
                budget = 200.milliseconds,
                referenceInvoke = { },
            ) {
                attempts++
                throw WasmExecutionException("WASM kernel trapped: unreachable")
            }
        }
        assertAll(
            { assertEquals(1, attempts, "a trap is fatal immediately") },
            { assertEquals("WASM kernel trapped: unreachable", thrown.message, "unwrapped") },
        )
    }

    /**
     * The marker is the **full** phrasing, not the bare word "exceeded" — because guest trap text is
     * interpolated raw into `"<phase> trapped: …"` by both non-JVM impls, and the vendored wasm3
     * interpreter throws `"linear memory limitation exceeded"` when a `memory.grow` passes its page
     * cap. That is the **sandbox memory ceiling firing**: a defect, and one no fixture reaches today,
     * so nothing but this test stops a looser marker from retrying it four times.
     */
    @Test
    fun aTrapWhoseEngineTextContainsExceededIsNotRetried(): TestResult = runTest {
        var attempts = 0
        val thrown = assertFailsWith<WasmExecutionException> {
            retryingOnlyBudgetOverruns(
                what = "a scenario whose guest breaches the memory ceiling",
                budget = 200.milliseconds,
                referenceInvoke = { },
            ) {
                attempts++
                throw WasmExecutionException("warp_run trapped: linear memory limitation exceeded")
            }
        }
        assertAll(
            { assertEquals(1, attempts, "a sandbox-guard trap is fatal immediately") },
            {
                assertEquals(
                    "warp_run trapped: linear memory limitation exceeded",
                    thrown.message,
                    "reaches the reader unwrapped",
                )
            },
        )
    }

    /**
     * The other half: a wrong-bytes or missing-throw assertion inside the scenario is the caller's
     * own verdict and must never be retried into a pass.
     */
    @Test
    fun anAssertionFailureInsideTheScenarioIsNotRetried(): TestResult = runTest {
        var attempts = 0
        val thrown = assertFailsWith<AssertionError> {
            retryingOnlyBudgetOverruns(
                what = "a scenario whose assertion fails",
                budget = 200.milliseconds,
                referenceInvoke = { },
            ) {
                attempts++
                throw AssertionError("wrong bytes")
            }
        }
        assertAll(
            { assertEquals(1, attempts, "an assertion failure is fatal immediately") },
            { assertEquals("wrong bytes", thrown.message, "the caller's own message survives") },
        )
    }

    /** The message every conforming runtime produces when an invocation exceeds its budget. */
    private fun budgetOverrun(): WasmExecutionException =
        WasmExecutionException("WASM execution exceeded 200ms")
}
