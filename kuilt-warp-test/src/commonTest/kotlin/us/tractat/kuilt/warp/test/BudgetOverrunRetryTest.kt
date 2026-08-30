package us.tractat.kuilt.warp.test

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.WasmExecutionException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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
            prepareReference = { probed++; { } },
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
                prepareReference = { { } },
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
                prepareReference = { { } },
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
                prepareReference = { { } },
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
                prepareReference = { { } },
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

    // ── The diagnostic itself (#1810) ────────────────────────────────────────────────────────

    /**
     * An **absorbed** overrun has to leave a trace. Before #1810 it accumulated into a
     * `StringBuilder` read only on exhaustion, so the drift from "no retry ever consumed" to
     * "three of four consumed every run" was invisible until the day all four failed — at which
     * point it reads as a fresh regression rather than as weeks of drift. That is #1739's own
     * thesis one level up: a signal that only speaks when it is already too late.
     */
    @Test
    fun anAbsorbedOverrunIsReportedToTheSinkAsItHappens(): TestResult = runTest {
        var attempts = 0
        val emitted = mutableListOf<String>()
        val result = retryingOnlyBudgetOverruns(
            what = "a scenario that overruns twice",
            budget = 200.milliseconds,
            emit = { emitted += it },
            prepareReference = { { } },
        ) {
            attempts++
            if (attempts < 3) throw budgetOverrun()
            "recovered"
        }
        assertAll(
            { assertEquals("recovered", result, "the absorbed overruns still ended in a pass") },
            { assertEquals(2, emitted.size, "both absorbed overruns were reported, not just counted") },
            { assertContains(emitted[0], "attempt 1/4 overran") },
            { assertContains(emitted[1], "attempt 2/4 overran") },
            { assertContains(emitted[0], "a scenario that overruns twice", ignoreCase = false) },
        )
    }

    /**
     * The complementary arm, and the one that makes the arm above mean something: a scenario that
     * passes first time reports **nothing**. Without this, a sink that fired unconditionally — or
     * on every attempt including the successful one — would satisfy the test above while destroying
     * the very distinction it exists to draw ("passed cleanly" vs "passed on the third try").
     */
    @Test
    fun aCleanPassReportsNothingToTheSink(): TestResult = runTest {
        val emitted = mutableListOf<String>()
        val result = retryingOnlyBudgetOverruns(
            what = "a scenario that passes first time",
            budget = 200.milliseconds,
            emit = { emitted += it },
            prepareReference = { { } },
        ) { "clean" }
        assertAll(
            { assertEquals("clean", result) },
            { assertEquals(emptyList(), emitted, "a clean pass consumed no retry, so it reports none") },
        )
    }

    /**
     * The reference price must compare like with like. The measurement under test times an
     * `invoke` on an already-loaded op; the reference used to time construct + `load` + `invoke`
     * together, and parse-plus-instantiate dominates a three-byte reverse. That inflated the
     * reported cost, which pushes the reader toward *"at or near the budget ⇒ blame the host"* —
     * the comfortable branch — when the honest reading may be *"well under ⇒ REAL defect"*.
     *
     * Pinned against an injected clock rather than a real one: `prepare` charges 9 s, each invoke
     * 3 ms, so a reference that still timed the load would report `9.003s`.
     */
    @Test
    fun theReferencePriceTimesTheInvokeAloneNotTheLoadBeforeIt(): TestResult = runTest {
        val clock = TestTimeSource()
        val failure = assertFailsWith<AssertionError> {
            retryingOnlyBudgetOverruns(
                what = "a scenario that always overruns",
                budget = 200.milliseconds,
                attempts = 1,
                timeSource = clock,
                prepareReference = {
                    clock += 9.seconds
                    val invoke: suspend () -> Unit = { clock += 3.milliseconds }
                    invoke
                },
            ) { throw budgetOverrun() }
        }
        val message = failure.message ?: ""
        assertAll(
            { assertContains(message, "took 3ms", message = "the invocation alone is the price") },
            { assertFalse("9.003s" in message, "the load is not in the timed region: $message") },
        )
    }

    /**
     * And it must not be a **single** sample, or one scheduling hiccup lands directly in the
     * number the reader is asked to judge the host by. The fastest of several is the honest floor:
     * contention can only ever make a sample slower.
     */
    @Test
    fun theReferencePriceIsTheFastestSampleSoOneHiccupCannotSetIt(): TestResult = runTest {
        val clock = TestTimeSource()
        var prepared = 0
        var invoked = 0
        // A hiccup first, then the host's real cost. A single sample would report the hiccup.
        val costs = listOf(40.milliseconds, 2.milliseconds, 30.milliseconds)
        val failure = assertFailsWith<AssertionError> {
            retryingOnlyBudgetOverruns(
                what = "a scenario that always overruns",
                budget = 200.milliseconds,
                attempts = 1,
                timeSource = clock,
                prepareReference = {
                    prepared++
                    val invoke: suspend () -> Unit = { clock += costs[invoked++ % costs.size] }
                    invoke
                },
            ) { throw budgetOverrun() }
        }
        assertAll(
            { assertEquals(1, prepared, "the reference runtime is built and loaded exactly once") },
            { assertEquals(costs.size, invoked, "every sample was taken") },
            { assertContains(failure.message ?: "", "took 2ms", message = "the fastest sample, not the first") },
        )
    }

    /** The message every conforming runtime produces when an invocation exceeds its budget. */
    private fun budgetOverrun(): WasmExecutionException =
        WasmExecutionException("WASM execution exceeded 200ms")
}
