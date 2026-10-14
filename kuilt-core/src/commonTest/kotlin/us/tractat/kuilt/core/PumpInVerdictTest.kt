package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: `Dispatchers.Unconfined` is not a thread — it runs a launch in the caller's frame, which is the immediate dispatch the #2829 race was measured under. Nothing here leaves the test thread.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [pumpIn]'s `onFailure` answers with a [PumpVerdict], and [PumpVerdict.STOP] ends **this** pump — before
 * the next item, with nothing reported for the stop and nothing done to the scope (#2829).
 *
 * ### Why every test runs under an immediate dispatcher
 * The defect was a race that exists only when the first item runs **inside** the `pumpIn` call: under
 * [CoroutineStart.UNDISPATCHED] or an immediate dispatcher, a `StateFlow`'s current value reaches the body
 * before `pumpIn` has returned the [Job] a consumer would cancel. A dispatched pump never enters that
 * window, so a test that ran only dispatched would pass against the race. [underEveryRig] therefore runs
 * each test once per dispatcher × start, labels every assertion with its rig, and
 * [aStopFromTheFirstItemOfAStateFlowNeedsNoJobReference] asserts that the immediate rigs really did run
 * the item inside the call — the control that the window was entered, not merely survived.
 *
 * The dispatched rig ([Kind.STANDARD] × [CoroutineStart.DEFAULT]) stays in as the control: the verdict
 * must mean the same thing whichever side of `pumpIn`'s return the failure lands on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PumpInVerdictTest {

    @Test
    fun aStopOnAnItemFailureEndsThePumpBeforeTheNextItem() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()

            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) { value ->
                bodyCalls += value
                error(BOOM)
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), bodyCalls, "[$rig] no item after the stopping one may reach the body") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$rig] the stop is not an UPSTREAM failure") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$rig] a stopped pump ends normally") },
                { assertTrue(rig.scopeJob.isActive, "[$rig] the scope survives its pump's stop") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig] nothing may reach the handler") },
            )
        }
    }

    @Test
    fun aContinueOnAnItemFailureProcessesTheNextItem() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()

            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig.answering(PumpVerdict.CONTINUE), PUMP, rig.start) {
                bodyCalls += it
                if (it == 1) error(BOOM)
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1, 2, 3), bodyCalls, "[$rig] CONTINUE consumes every later item") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$rig]") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$rig]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    /**
     * The issue's own shape: a `StateFlow` whose current value fails, under a rig where that value reaches
     * the body inside the `pumpIn` call. A `lateinit var job` cancelled from `onFailure` threw there, the
     * throw was absorbed, and the pump consumed the next value (#2829: `calls=2` on `main`). A verdict
     * needs nothing captured, so the stop holds on every rig.
     */
    @Test
    fun aStopFromTheFirstItemOfAStateFlowNeedsNoJobReference() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val state = MutableStateFlow(1)
            var calls = 0

            val job = state.pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) {
                calls++
                error(BOOM)
            }
            val callsWhenPumpInReturned = calls
            runCurrent()
            state.value = 2
            runCurrent()

            assertAll(
                {
                    assertEquals(
                        if (rig.immediate) 1 else 0,
                        callsWhenPumpInReturned,
                        "[$rig] the rig must put the first item inside the pumpIn call exactly when it claims to",
                    )
                },
                { assertEquals(1, calls, "[$rig] the value after the stopping one must never reach the body") },
                { assertEquals(0, state.subscriptionCount.value, "[$rig] a stopped pump lets go of its flow") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$rig]") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$rig]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    /**
     * An ordinary throw from `onFailure` has no verdict in it, and is read as [PumpVerdict.CONTINUE]: a
     * pump stopped by its own logger would be a dead pump nothing reported. A `CancellationException` is
     * the sharp case — rethrown, it would have cancelled the pump silently.
     */
    @Test
    fun aThrowingOnFailureIsReadAsContinue() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // Both kinds run whatever the first one does, so a red names every kind it reached.
        val failed = listOf(IllegalStateException("observer"), CancellationException("observer")).mapNotNull { thrown ->
            assertionFailureOf {
                underEveryRig { rig ->
                    val bodyCalls = mutableListOf<Int>()

                    val job = flowOf(1, 2, 3).pumpIn(
                        rig.scope,
                        onFailure = { phase, failure ->
                            rig.answering(PumpVerdict.STOP)(phase, failure)
                            throw thrown
                        },
                        name = PUMP,
                        start = rig.start,
                    ) {
                        bodyCalls += it
                        if (it == 1) error(BOOM)
                    }
                    runCurrent()

                    val label = "[$rig ${thrown::class.simpleName}]"
                    assertAll(
                        { assertEquals(listOf(1, 2, 3), bodyCalls, "$label a throw is not a STOP") },
                        { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), label) },
                        { assertTrue(job.isCompleted && !job.isCancelled, label) },
                        { assertTrue(rig.unhandled.isEmpty(), label) },
                    )
                }
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    @Test
    fun aStopLeavesTheScopeAndItsSiblingsRunning() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val sibling = MutableStateFlow(0)
            val siblingSeen = mutableListOf<Int>()
            val siblingJob = sibling.pumpIn(rig.scope, rig.answering(PumpVerdict.CONTINUE), "$PUMP-sibling", rig.start) {
                siblingSeen += it
            }
            var stoppedCalls = 0

            val stopped = flowOf(1, 2).pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) {
                stoppedCalls++
                error(BOOM)
            }
            runCurrent()
            sibling.value = 7
            runCurrent()

            assertAll(
                { assertEquals(1, stoppedCalls, "[$rig] precondition: the pump did stop") },
                { assertFalse(stopped.isCancelled, "[$rig] a stop is not a cancellation anyone else can see") },
                { assertTrue(rig.scopeJob.isActive, "[$rig] the scope is untouched by its pump's stop") },
                { assertTrue(siblingJob.isActive, "[$rig] a sibling pump is still running") },
                { assertEquals(listOf(0, 7), siblingSeen, "[$rig] and still consuming") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    /**
     * A STOP ends the collection by aborting the upstream's `emit`, which only works on a flow that lets
     * the abort through. `pumpIn` exists for consumer-authored and peer-supplied flows, so it cannot assume
     * that: this one implements [Flow] directly — no `SafeCollector` to catch the violation — swallows
     * the abort, and goes on emitting. The body must still never see another item.
     */
    @Test
    fun aStopHoldsAgainstAnUpstreamThatSwallowsTheAbort() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()
            val swallowed = mutableListOf<Int>()
            val swallowing = object : Flow<Int> {
                override suspend fun collect(collector: FlowCollector<Int>) {
                    for (value in 1..3) {
                        try {
                            collector.emit(value)
                        } catch (abort: CancellationException) {
                            // The defect under test: an upstream that swallows its downstream's abort.
                            swallowed += value
                        }
                    }
                }
            }

            val job = swallowing.pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) {
                bodyCalls += it
                error(BOOM)
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), bodyCalls, "[$rig] a swallowed abort must not reopen the body") },
                { assertEquals(listOf(1, 2, 3), swallowed, "[$rig] precondition: the upstream really did swallow") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$rig]") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$rig]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    /**
     * The common way an upstream swallows the abort: a `flow {}` relaying a channel that wraps each `emit`
     * in `catch (e: Exception)`. It catches the abort, then every later emit's transparency violation, so
     * it never ends — and a STOP cannot end it either, exactly as `take` or `first` could not. What STOP
     * still guarantees, and what this pins: the body never sees another item, nothing more is reported,
     * and the pump's owner can still end it by cancelling its scope.
     *
     * Whether the pump's [Job] is still active before that cancel is deliberately not asserted: it is the
     * documented limit, and a future fix that ended such a pump should not go red here.
     */
    @Test
    fun aStopAgainstAnUpstreamThatCatchesAroundEmitNeverReachesTheBodyAgain() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()
            var upstreamCaught = 0
            val inbox = Channel<Int>(Channel.UNLIMITED)
            (1..5).forEach { inbox.trySend(it) }
            val relay = flow {
                while (true) {
                    val value = inbox.receive()
                    try {
                        emit(value)
                    } catch (e: Exception) {
                        // The defect under test: a relay that logs and carries on whatever `emit` threw.
                        upstreamCaught++
                    }
                }
            }

            val job = relay.pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) {
                bodyCalls += it
                error(BOOM)
            }
            runCurrent()
            val caughtBeforeCancel = upstreamCaught
            rig.scope.cancel()
            runCurrent()

            assertAll(
                { assertEquals(5, caughtBeforeCancel, "[$rig] precondition: the relay caught every emit's throw") },
                { assertEquals(listOf(1), bodyCalls, "[$rig] no item after the stopping one may reach the body") },
                { assertEquals(listOf(PumpFailure.ITEM), rig.phases(), "[$rig] nothing more is reported") },
                { assertTrue(job.isCancelled, "[$rig] the owner can still end the pump through its scope") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    @Test
    fun aStopOnAnUpstreamFailureReportsItOnceAndEndsNormally() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val seen = mutableListOf<Int>()

            val job = flow {
                emit(1)
                error(BOOM)
            }.pumpIn(rig.scope, rig.answering(PumpVerdict.STOP), PUMP, rig.start) { seen += it }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), seen, "[$rig]") },
                { assertEquals(listOf(PumpFailure.UPSTREAM), rig.phases(), "[$rig] one report, not a second for the stop") },
                { assertTrue(job.isCompleted && !job.isCancelled, "[$rig] the flow ended; STOP adds nothing to that") },
                { assertTrue(rig.scopeJob.isActive, "[$rig]") },
                { assertTrue(rig.unhandled.isEmpty(), "[$rig]") },
            )
        }
    }

    /** An `Error` from `onFailure` is not a verdict: it escapes and fails the pump, as before (#2890). */
    @Test
    fun anErrorFromOnFailureStillFailsThePump() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()
            val thrown = VerdictTestError()

            val job = flowOf(1, 2, 3).pumpIn(
                rig.scope,
                onFailure = { phase, failure ->
                    rig.answering(PumpVerdict.CONTINUE)(phase, failure)
                    throw thrown
                },
                name = PUMP,
                start = rig.start,
            ) {
                bodyCalls += it
                error(BOOM)
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), bodyCalls, "[$rig] the pump dies with the observer's Error") },
                { assertTrue(job.isCancelled, "[$rig] the pump's Job fails") },
                { assertSame(thrown, rig.unhandled.singleOrNull(), "[$rig] the Error itself escapes") },
            )
        }
    }

    /** An `Error` from the body never reaches `onFailure`, so no verdict can keep the pump alive. */
    @Test
    fun anErrorFromTheBodyIsNeverOfferedForAVerdict() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        underEveryRig { rig ->
            val bodyCalls = mutableListOf<Int>()
            val thrown = VerdictTestError()

            val job = flowOf(1, 2, 3).pumpIn(rig.scope, rig.answering(PumpVerdict.CONTINUE), PUMP, rig.start) {
                bodyCalls += it
                throw thrown
            }
            runCurrent()

            assertAll(
                { assertEquals(listOf(1), bodyCalls, "[$rig]") },
                { assertTrue(rig.phases().isEmpty(), "[$rig] an Error is never reported") },
                { assertTrue(job.isCancelled, "[$rig]") },
                { assertSame(thrown, rig.unhandled.singleOrNull(), "[$rig]") },
            )
        }
    }

    private class VerdictTestError : Error(BOOM)

    /** The message of the [AssertionError] [block] throws, or `null` when it passes. */
    private inline fun assertionFailureOf(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (failure: AssertionError) {
            failure.message
        }

    /** Which dispatcher a [Rig] runs its pumps on. */
    private enum class Kind {
        /** Dispatched: the control, where the first item always runs after `pumpIn` returns. */
        STANDARD,

        /** The test library's immediate dispatcher, sharing the test scheduler. */
        UNCONFINED_TEST,

        /** The production immediate dispatcher. */
        UNCONFINED,
        ;

        fun dispatcher(test: TestScope): CoroutineDispatcher = when (this) {
            STANDARD -> StandardTestDispatcher(test.testScheduler)
            UNCONFINED_TEST -> UnconfinedTestDispatcher(test.testScheduler)
            UNCONFINED -> Dispatchers.Unconfined
        }
    }

    /**
     * One dispatcher × start, in a scope shaped like production's: a [SupervisorJob] plus a
     * [CoroutineExceptionHandler] standing where the runtime's global handler stands on a device — see
     * `PumpInTest`'s class KDoc for why that handler, not the process, is what is asserted on.
     */
    private class Rig(test: TestScope, val kind: Kind, val start: CoroutineStart) {
        val failures = mutableListOf<Pair<PumpFailure, Throwable>>()
        val unhandled = mutableListOf<Throwable>()
        val scopeJob: Job = SupervisorJob()
        val scope = CoroutineScope(
            scopeJob + kind.dispatcher(test) + CoroutineExceptionHandler { _, thrown -> unhandled += thrown },
        )

        /** Whether a launch here runs its first item before `pumpIn` returns. */
        val immediate: Boolean get() = kind != Kind.STANDARD || start == CoroutineStart.UNDISPATCHED

        /** An `onFailure` that records the failure and answers [verdict]. */
        fun answering(verdict: PumpVerdict): (PumpFailure, Throwable) -> PumpVerdict = { phase, failure ->
            failures += phase to failure
            verdict
        }

        fun phases(): List<PumpFailure> = failures.map { it.first }

        override fun toString(): String = "$kind/$start"
    }

    /**
     * Run [block] once per dispatcher × start. Every rig runs even after one fails, and the failure names
     * each rig that failed: which rigs go red is the evidence that the race window was entered, so
     * stopping at the first would hide it.
     */
    private inline fun TestScope.underEveryRig(block: (rig: Rig) -> Unit) {
        val rigs = Kind.entries.flatMap { kind -> STARTS.map { start -> Rig(this, kind, start) } }
        val failed = rigs.mapNotNull { rig ->
            val failure = assertionFailureOf { block(rig) }
            rig.scope.cancel()
            failure?.let { "[$rig] $it" }
        }
        if (failed.isNotEmpty()) {
            throw AssertionError("${failed.size} of ${rigs.size} rigs failed:\n${failed.joinToString("\n")}")
        }
    }

    private companion object {
        const val BOOM = "pump-in verdict boom"
        const val PUMP = "pump-in-verdict-test"
        val STARTS = listOf(CoroutineStart.DEFAULT, CoroutineStart.UNDISPATCHED)
    }
}
