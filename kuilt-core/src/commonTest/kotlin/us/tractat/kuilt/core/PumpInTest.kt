package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.test.assertSame
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
 *
 * ### Every discipline test runs under both starts
 * [pumpIn] takes a [CoroutineStart] (#2826), and under [CoroutineStart.UNDISPATCHED] a cold flow is
 * collected inside the `pumpIn` call itself, before the test has run its scheduler. The guards must not
 * care: [underEveryStart] runs each discipline test once per start, each with its own rig, and labels
 * every assertion with the start it ran under.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PumpInTest {

    @Test
    fun aThrowingBodyIsReportedAndThePumpKeepsConsuming() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()

            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig::record, PUMP, start) { value ->
                if (value == 2) error(BOOM)
                seen += value
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1, 3), seen, "[$start] the item after the throwing one must still be consumed") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$start] one item failure, not an upstream one") },
                { assertIs<IllegalStateException>(rig.failures.singleOrNull()?.second, "[$start]") },
                { assertTrue(job.isCompleted, "[$start] the flow ran to completion") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start] nothing may reach the handler — that is the abort route") },
            )
        }
    }

    @Test
    fun aThrowingUpstreamIsCaughtAndReported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()

            // The half no `onEach`-body guard can see: the flow itself fails, which ends it.
            val job = flow<Int> {
                emit(1)
                error(BOOM)
            }.pumpIn(rig.scope, rig::record, PUMP, start) { seen += it }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), seen, "[$start] everything emitted before the failure is still delivered") },
                { assertEquals(listOf(PumpFailure.UPSTREAM), rig.phases(), "[$start] the PUMP failed, not an item") },
                { assertIs<IllegalStateException>(rig.failures.singleOrNull()?.second, "[$start]") },
                // The pump is over — that is what an upstream failure means — but it ended with a diagnosis
                // rather than a SIGABRT, so the job completes instead of failing.
                { assertTrue(job.isCompleted && !job.isCancelled, "[$start] the pump ends, and ends normally") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start] nothing may reach the handler — that is the abort route") },
            )
        }
    }

    @Test
    fun aCalleeMintedCancellationIsAnItemFailureNotAPumpDeath() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()

            // What a consumer's `withTimeout(…) { … }` throws at us while our own job is perfectly alive.
            // `runCatchingCancellable` rethrows this by type, which kills the pump *silently*.
            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig::record, PUMP, start) { value ->
                if (value == 2) throw CancellationException("callee-minted")
                seen += value
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1, 3), seen, "[$start] a minted cancellation must not end the pump") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$start]") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$start]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
    }

    @Test
    fun cancellingTheScopeStillCancelsThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            // Suspended inside the BODY, so our own cancellation arrives at the body guard — the one place a
            // `catch (_: Throwable) { }` would swallow it and leave an uncancellable pump behind.
            val job = flowOf(1).pumpIn(rig.scope, rig::record, PUMP, start) { awaitCancellation() }
            runCurrent()
            assertTrue(job.isActive, "[$start] precondition: the pump is parked in the body")

            rig.scope.cancel()
            runCurrent()

            assertAll(
                { assertTrue(job.isCancelled, "[$start] our own cancellation must propagate through both guards") },
                { assertTrue(rig.failures.isEmpty(), "[$start] a cancellation is not a failure and must not be reported") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
    }

    @Test
    fun aThrowingOnFailureObserverCannotKillThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
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
                start = start,
            ) { value ->
                if (value == 2) error(BOOM)
                seen += value
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1, 3), seen, "[$start] the pump survives its own failure report") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$start]") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$start]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
    }

    /**
     * An `Error` is not a pump failure, it is the end of the process (#2890): it must leave the pump
     * through the same route an unguarded launch would take, and `onFailure` must never see it — a
     * consumer handed one can only log it and carry on, which is the one thing it must not do.
     *
     * Under [CoroutineStart.UNDISPATCHED] the throw happens inside the `pumpIn` call itself, so this also
     * pins that the `Error` fails the pump's [kotlinx.coroutines.Job] rather than being thrown at the
     * caller — the test would fail with it, uncaught, if it were.
     */
    @Test
    fun anErrorFromTheBodyFailsThePumpUnreported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()
            val thrown = PumpTestError()

            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig::record, PUMP, start) { value ->
                if (value == 2) throw thrown
                seen += value
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), seen, "[$start] the pump dies with the Error — nothing after it is consumed") },
                { assertTrue(rig.failures.isEmpty(), "[$start] an Error must never reach onFailure") },
                { assertTrue(job.isCancelled, "[$start] the pump's Job fails rather than completing") },
                { assertSame(thrown, rig.unhandled.singleOrNull(), "[$start] the Error itself escapes the pump") },
            )
        }
    }

    @Test
    fun anErrorFromUpstreamFailsThePumpUnreported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()
            val thrown = PumpTestError()

            val job = flow<Int> {
                emit(1)
                throw thrown
            }.pumpIn(rig.scope, rig::record, PUMP, start) { seen += it }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), seen, "[$start]") },
                { assertTrue(rig.failures.isEmpty(), "[$start] an upstream Error is not PumpFailure.UPSTREAM") },
                { assertTrue(job.isCancelled, "[$start] the pump's Job fails rather than completing normally") },
                { assertSame(thrown, rig.unhandled.singleOrNull(), "[$start] the Error itself escapes the pump") },
            )
        }
    }

    /** The other half of #2890: an observer that throws an `Error` must be able to let it escape. */
    @Test
    fun anErrorFromOnFailureEscapesThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()
            val thrown = PumpTestError()

            val job = flowOf(1, 2, 3).pumpIn(
                rig.scope,
                onFailure = { phase, failure ->
                    rig.record(phase, failure)
                    throw thrown
                },
                name = PUMP,
                start = start,
            ) { value ->
                if (value == 2) error(BOOM)
                seen += value
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), seen, "[$start] the pump dies with the observer's Error") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$start] the Exception was still reported first") },
                { assertTrue(job.isCancelled, "[$start]") },
                { assertSame(thrown, rig.unhandled.singleOrNull(), "[$start]") },
            )
        }
    }

    /**
     * The point of [CoroutineStart.UNDISPATCHED] (#2826): over a hot flow with no replay, an emission made
     * the instant `pumpIn` returns must arrive. This is the shape of a joiner relaying `Room.incoming` from
     * its `init` while the leader answers its hello at once.
     *
     * Nothing runs the scheduler between the call and the emission — that gap is the whole race. Read
     * against [aDefaultPumpIsNotYetSubscribedWhenPumpInReturns], which makes the identical emission and
     * loses it, so this test cannot pass on a rig where the timing does not matter.
     */
    @Test
    fun anUndispatchedPumpIsSubscribedWhenPumpInReturns() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()
        val hot = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)

        val job = hot.pumpIn(rig.scope, rig::record, PUMP, CoroutineStart.UNDISPATCHED) { seen += it }
        val subscribersOnReturn = hot.subscriptionCount.value
        hot.tryEmit(WELCOME)
        runCurrent()

        assertAll(
            { assertEquals(1, subscribersOnReturn, "the pump must be subscribed before pumpIn returns") },
            { assertEquals(listOf(WELCOME), seen, "an emission made immediately after pumpIn returns must arrive") },
            { assertTrue(job.isActive, "the pump is still listening") },
            { assertTrue(rig.failures.isEmpty()) },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
        rig.close()
    }

    /**
     * The control for [anUndispatchedPumpIsSubscribedWhenPumpInReturns], and the default's documented
     * behaviour: the pump has not subscribed when `pumpIn` returns, so a replay-free emission in that gap
     * is gone. The later emission proves the pump does run — it was simply not listening yet.
     */
    @Test
    fun aDefaultPumpIsNotYetSubscribedWhenPumpInReturns() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = PumpRig(this)
        val seen = mutableListOf<Int>()
        val hot = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)

        val job = hot.pumpIn(rig.scope, rig::record, PUMP) { seen += it }
        val subscribersOnReturn = hot.subscriptionCount.value
        hot.tryEmit(WELCOME)
        runCurrent()
        hot.tryEmit(LATER)
        runCurrent()

        assertAll(
            { assertEquals(0, subscribersOnReturn, "a DEFAULT pump has not subscribed when pumpIn returns") },
            { assertEquals(listOf(LATER), seen, "the early emission is lost; the pump itself is running") },
            { assertTrue(job.isActive) },
            { assertTrue(rig.failures.isEmpty()) },
            { assertTrue(rig.unhandled.isEmpty()) },
        )
        rig.close()
    }

    /**
     * A pump parked on a hot flow — where an UNDISPATCHED pump spends its life — is cancelled by its scope,
     * reports nothing, and lets go of its subscription.
     */
    @Test
    fun cancellingTheScopeCancelsAPumpParkedOnAHotFlow() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val hot = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
            val job = hot.pumpIn(rig.scope, rig::record, PUMP, start) { }
            runCurrent()
            assertEquals(1, hot.subscriptionCount.value, "[$start] precondition: the pump is subscribed")

            rig.scope.cancel()
            runCurrent()

            assertAll(
                { assertTrue(job.isCancelled, "[$start] the scope's cancellation must reach the pump") },
                { assertEquals(0, hot.subscriptionCount.value, "[$start] a cancelled pump unsubscribes") },
                { assertTrue(rig.failures.isEmpty(), "[$start] a cancellation is not a failure") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
    }

    /**
     * A pump launched into a scope that is **already** cancelled. A [CoroutineStart.DEFAULT] pump never
     * starts. An [CoroutineStart.UNDISPATCHED] one still runs in the caller's frame up to its first
     * suspension — `launch`'s semantics, documented on [pumpIn] — and a cold flow that never suspends is
     * consumed in full before the pump meets its cancellation. Either way it must end cancelled, and its
     * own cancellation must not be reported as a failure.
     */
    @Test
    fun aPumpLaunchedIntoACancelledScopeEndsCancelledAndReportsNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryStart { start, rig ->
            val seen = mutableListOf<Int>()
            rig.scope.cancel()

            val job = flowOf(1, 2).pumpIn(rig.scope, rig::record, PUMP, start) { seen += it }
            runCurrent()

            val consumedBeforeFirstSuspension = if (start == CoroutineStart.UNDISPATCHED) listOf(1, 2) else emptyList()
            assertAll(
                { assertTrue(job.isCancelled, "[$start] a pump in a cancelled scope ends cancelled") },
                {
                    assertEquals(
                        consumedBeforeFirstSuspension,
                        seen,
                        "[$start] only an UNDISPATCHED pump runs before it meets the cancellation",
                    )
                },
                { assertTrue(rig.failures.isEmpty(), "[$start] our own cancellation is not an upstream failure") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
    }

    private class PumpTestError : Error(BOOM)

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
     * Run [block] once per [CoroutineStart] a pump is launched with in production, each with its own
     * [PumpRig], so a discipline test pins the discipline under both — see the class KDoc.
     */
    private inline fun TestScope.underEveryStart(block: (start: CoroutineStart, rig: PumpRig) -> Unit) {
        for (start in STARTS) {
            val rig = PumpRig(this)
            block(start, rig)
            rig.close()
        }
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
        underEveryStart { start, rig ->
            val seenInBody = mutableListOf<String?>()

            val job = flowOf(1, 2).pumpIn(rig.scope, rig::record, PUMP, start) {
                seenInBody += currentCoroutineContext()[CoroutineName]?.name
            }
            runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf<String?>(PUMP, PUMP),
                        seenInBody,
                        "[$start] every item runs in the named coroutine — a dump reads the name off this context",
                    )
                },
                { assertTrue(job.isCompleted, "[$start] the pump still ran to completion") },
                { assertTrue(rig.failures.isEmpty(), "[$start] naming a pump must not change what it reports") },
                { assertTrue(rig.unhandled.isEmpty(), "[$start]") },
            )
        }
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
        const val WELCOME = 1
        const val LATER = 2
        val STARTS = listOf(CoroutineStart.DEFAULT, CoroutineStart.UNDISPATCHED)
    }
}
