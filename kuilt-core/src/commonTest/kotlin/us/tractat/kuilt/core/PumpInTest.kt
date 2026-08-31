package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [pumpIn] must survive **both** ways a long-lived pump dies, and must still die when its scope is
 * cancelled (#1803 Shape B).
 *
 * ### The failure being pinned is a process abort, and this file cannot see it
 * On Kotlin/Native an unhandled coroutine exception is not a dead coroutine, it is a dead **process**:
 * `handleUncaughtCoroutineException` → the runtime default → `Uncaught Kotlin exception` → abort, with no
 * `setUnhandledExceptionHook` installed anywhere in kuilt's non-test sources. `runTest` **masks** exactly
 * that — kotlinx-coroutines-test collects the throw and reports it as a test failure even from a
 * detached, non-child scope — so every pump test written under `runTest` is structurally blind to the
 * real fatality (#1788). `PumpInProcessSurvivalTest` (a bare `@Test` in `appleTest`) owns that dimension.
 *
 * So these tests do not assert "the process lived". They assert the thing that *decides* whether it
 * lives, one step earlier on the same route: **nothing reached the scope's
 * [CoroutineExceptionHandler]**. That handler stands exactly where the runtime's global handler stands
 * on a real device — an installed handler is what a shipped app does *not* have — so a throwable arriving
 * there is precisely the throwable that would have aborted. It is also deterministic on every target,
 * which the abort is not.
 *
 * ### The arm that matters most is [aThrowingUpstreamIsCaughtAndReported]
 * A helper that guards only the collector body reads, at every call site, as though the class were
 * closed, while leaving process death wide open — `onEach { … }.launchIn(scope)` desugars to
 * `scope.launch { flow.onEach { … }.collect() }`, so the body guard is *inside* the collector and a
 * throw raised by the flow never enters it. That arm is therefore RED against a body-only helper and
 * green only once the upstream guard exists; the receipt is in the PR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PumpInTest {

    @Test
    fun aThrowingBodyIsReportedAndThePumpKeepsConsuming() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()

        val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig::record, PUMP) { value ->
            if (value == 2) error(BOOM)
            seen += value
        }
        runCurrent()

        assertAll(
            { assertEquals(listOf(1, 3), seen, "the item after the throwing one must still be consumed") },
            { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "one item failure, not an upstream one") },
            { assertIs<IllegalStateException>(rig.failures.singleOrNull()?.second) },
            { assertTrue(job.isCompleted, "the flow ran to completion") },
            { assertTrue(rig.unhandled.isEmpty(), "nothing may reach the handler — that is the abort route") },
        )
        rig.close()
    }

    @Test
    fun aThrowingUpstreamIsCaughtAndReported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()

        // The half no `onEach`-body guard can see: the flow itself fails, which ends it.
        val job = flow<Int> {
            emit(1)
            error(BOOM)
        }.pumpIn(rig.scope, rig::record, PUMP) { seen += it }
        runCurrent()

        assertAll(
            { assertEquals(listOf(1), seen, "everything emitted before the failure is still delivered") },
            { assertEquals(listOf(PumpFailure.UPSTREAM), rig.phases(), "the PUMP failed, not an item") },
            { assertIs<IllegalStateException>(rig.failures.singleOrNull()?.second) },
            // The pump is over — that is what an upstream failure means — but it ended with a diagnosis
            // rather than a SIGABRT, so the job completes instead of failing.
            { assertTrue(job.isCompleted && !job.isCancelled, "the pump ends, and ends normally") },
            { assertTrue(rig.unhandled.isEmpty(), "nothing may reach the handler — that is the abort route") },
        )
        rig.close()
    }

    @Test
    fun aCalleeMintedCancellationIsAnItemFailureNotAPumpDeath() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()

        // What a consumer's `withTimeout(…) { … }` throws at us while our own job is perfectly alive.
        // `runCatchingCancellable` rethrows this by type, which kills the pump *silently*.
        val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig::record, PUMP) { value ->
            if (value == 2) throw CancellationException("callee-minted")
            seen += value
        }
        runCurrent()

        assertAll(
            { assertEquals(listOf(1, 3), seen, "a minted cancellation must not end the pump") },
            { assertEquals(listOf(PumpFailure.ITEM), rig.phases()) },
            { assertTrue(job.isCompleted && !job.isCancelled) },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
        rig.close()
    }

    @Test
    fun cancellingTheScopeStillCancelsThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)

        // Suspended inside the BODY, so our own cancellation arrives at the body guard — the one place a
        // `catch (_: Throwable) { }` would swallow it and leave an uncancellable pump behind.
        val job = flowOf(1).pumpIn(rig.scope, rig::record, PUMP) { awaitCancellation() }
        runCurrent()
        assertTrue(job.isActive, "precondition: the pump is parked in the body")

        rig.scope.cancel()
        runCurrent()

        assertAll(
            { assertTrue(job.isCancelled, "our own cancellation must propagate through both guards") },
            { assertTrue(rig.failures.isEmpty(), "a cancellation is not a failure and must not be reported") },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
    }

    @Test
    fun aThrowingOnFailureObserverCannotKillThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()

        // A consumer's logger throwing — including a CancellationException, which is the one that would
        // escape the guard and kill the pump silently, i.e. the defect reached through its own report.
        val job = flowOf(1, 2, 3).pumpIn(
            rig.scope,
            onFailure = { phase, failure ->
                rig.record(phase, failure)
                throw CancellationException("observer")
            },
            name = PUMP,
        ) { value ->
            if (value == 2) error(BOOM)
            seen += value
        }
        runCurrent()

        assertAll(
            { assertEquals(listOf(1, 3), seen, "the pump survives its own failure report") },
            { assertEquals(listOf(PumpFailure.ITEM), rig.phases()) },
            { assertTrue(job.isCompleted && !job.isCancelled) },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
        rig.close()
    }

    /**
     * A pump scope shaped like production's: a [SupervisorJob] — which is the abort *mechanism*, not
     * protection, since suppressing parent propagation is what routes a throw to the global handler — plus
     * a [CoroutineExceptionHandler] standing where the runtime's global handler stands on a device, so
     * "would this have aborted?" becomes an assertion instead of an inference.
     */
    private class PumpRig(test: TestScope) {
        val failures = mutableListOf<Pair<PumpFailure, Throwable>>()
        val unhandled = mutableListOf<Throwable>()

        val scope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(test.testScheduler) +
                CoroutineExceptionHandler { _, thrown -> unhandled += thrown },
        )

        fun record(phase: PumpFailure, failure: Throwable) {
            failures += phase to failure
        }

        fun phases(): List<PumpFailure> = failures.map { it.first }

        fun close() = scope.cancel()
    }

    /**
     * The name has to reach the **launched coroutine's own context**, which is the only place a census
     * can read it from (#1811).
     *
     * `launchIn` takes no context parameter, so a name held anywhere else — a local, a field, a wrapper
     * around the flow — is invisible to `DebugProbes.dumpCoroutinesInfo()`, which walks
     * `CoroutineInfo.context` and nothing else. Read from *inside* the body for that reason: that is the
     * continuation whose context a dump reports, and asserting on anything the caller still holds would
     * be asserting on the instrument rather than the outcome.
     */
    @Test
    fun theNameReachesTheLaunchedCoroutinesOwnContext() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seenInBody = mutableListOf<String?>()

        val job = flowOf(1, 2).pumpIn(rig.scope, rig::record, PUMP) {
            seenInBody += currentCoroutineContext()[CoroutineName]?.name
        }
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf<String?>(PUMP, PUMP),
                    seenInBody,
                    "every item runs in the named coroutine — a dump reads the name off this context",
                )
            },
            { assertTrue(job.isCompleted, "the pump still ran to completion") },
            { assertTrue(rig.failures.isEmpty(), "naming a pump must not change what it reports") },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
        rig.close()
    }

    /**
     * Two pumps in one scope must be **separable**, which is the whole of #1811: the census cannot say
     * which of a peer's pumps wedged when they all render identically. A single-pump test would pass
     * against an implementation that hardcoded one constant name for every launch.
     */
    @Test
    fun twoPumpsInOneScopeCarryTheirOwnNames() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val names = mutableListOf<String?>()

        flowOf(1).pumpIn(rig.scope, rig::record, "$PUMP-left") {
            names += currentCoroutineContext()[CoroutineName]?.name
        }
        flowOf(1).pumpIn(rig.scope, rig::record, "$PUMP-right") {
            names += currentCoroutineContext()[CoroutineName]?.name
        }
        runCurrent()

        assertEquals(
            listOf<String?>("$PUMP-left", "$PUMP-right"),
            names,
            "sibling pumps must be distinguishable — one shared constant would leave them a single blob",
        )
        rig.close()
    }

    private companion object {
        const val BOOM = "pump-in boom"
        const val PUMP = "pump-in-test"
    }
}
