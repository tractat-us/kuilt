package us.tractat.kuilt.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for [SoloDeadlineDetector].
 *
 * **Virtual time**: the deadline is a [kotlinx.coroutines.delay], so bounded [advanceTimeBy]
 * drives every case; `advanceUntilIdle()` is never used.
 *
 * **Clock**: a fixed clock is injected — never `Clock.System`.
 *
 * **Scope discipline**: the detector's timer and every collector run on
 * [kotlinx.coroutines.test.TestScope.backgroundScope] so nothing outlives the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoloDeadlineDetectorTest {
    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = FixedClock(now)
    private val deadline = 30.seconds

    private val self = PeerId("self")
    private val other = PeerId("other")

    private fun detector(scope: CoroutineScope, minimumMembers: Int = 2, wait: Duration = deadline) =
        SoloDeadlineDetector(minimumMembers, wait, clock, scope)

    private fun CoroutineScope.record(detector: SoloDeadlineDetector): List<SoloDeadlineEvent> {
        val events = mutableListOf<SoloDeadlineEvent>()
        launch { detector.events.toList(events) }
        return events
    }

    private fun neverPaired(observed: Int, required: Int): SoloDeadlineEvent =
        SoloDeadlineEvent.NeverPaired(observed, required, now)

    private fun paired(): SoloDeadlineEvent = SoloDeadlineEvent.Paired(now)

    @Test
    fun deadlineElapsesAloneEmitsExactlyOneNeverPaired() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope)
        val events = backgroundScope.record(detector)

        detector.observeMembership(setOf(self))
        advanceTimeBy(deadline - 1.seconds)
        assertTrue(events.isEmpty(), "no verdict before the deadline")

        advanceTimeBy(2.seconds)
        assertEquals(listOf(neverPaired(observed = 1, required = 2)), events)

        // Well past the deadline, still exactly one event.
        advanceTimeBy(deadline * 3)
        assertEquals(1, events.size)
    }

    @Test
    fun minimumReachedFirstEmitsPairedAndDisarms() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope)
        val events = backgroundScope.record(detector)

        detector.observeMembership(setOf(self))
        advanceTimeBy(1.seconds)
        detector.observeMembership(setOf(self, other))
        advanceTimeBy(1.seconds)
        assertEquals(listOf(paired()), events)

        // Long past the deadline: no NeverPaired ever follows.
        advanceTimeBy(deadline * 5)
        assertEquals(listOf(paired()), events)
    }

    @Test
    fun pairedThenEmptiedStaysSilent() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope)
        val events = backgroundScope.record(detector)

        detector.observeMembership(setOf(self, other))
        advanceTimeBy(1.seconds)
        // The room empties out again — that is the partition detector's business, not ours.
        detector.observeMembership(setOf(self))
        advanceTimeBy(deadline * 5)

        assertEquals(listOf(paired()), events)
    }

    @Test
    fun higherMinimumRequiresThatManyMembers() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope, minimumMembers = 3)
        val events = backgroundScope.record(detector)

        detector.observeMembership(setOf(self, other))
        advanceTimeBy(deadline + 1.seconds)

        assertEquals(listOf(neverPaired(observed = 2, required = 3)), events)
    }

    @Test
    fun neverObservedMembershipReportsZeroObserved() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope)
        val events = backgroundScope.record(detector)

        advanceTimeBy(deadline + 1.seconds)

        assertEquals(listOf(neverPaired(observed = 0, required = 2)), events)
    }

    @Test
    fun lateSubscriberStillSeesTheVerdict() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val detector = detector(backgroundScope)
        detector.observeMembership(setOf(self))
        advanceTimeBy(deadline + 1.seconds)

        val events = backgroundScope.record(detector)
        advanceTimeBy(1.seconds)

        assertEquals(listOf(neverPaired(observed = 1, required = 2)), events)
    }

    @Test
    fun neverPairedNeverReportsAMembershipThatMetTheRequirement() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val detector = detector(backgroundScope)
            val events = backgroundScope.record(detector)

            detector.observeMembership(setOf(self))
            advanceTimeBy(deadline + 1.seconds)
            // A paired roster arriving after the verdict must not retroactively become the
            // reported count: `observed` is written only on the below-minimum path.
            detector.observeMembership(setOf(self, other))
            advanceTimeBy(1.seconds)

            val verdict = events.single()
            assertIs<SoloDeadlineEvent.NeverPaired>(verdict)
            assertTrue(
                verdict.observed < verdict.required,
                "NeverPaired must never report a membership that met the requirement, was " +
                    "observed=${verdict.observed} required=${verdict.required}",
            )
        }

    @Test
    fun minimumMembersBelowTwoIsRejected() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        assertFailsWith<IllegalArgumentException> { detector(backgroundScope, minimumMembers = 1) }
    }

    @Test
    fun nonPositiveDeadlineIsRejected() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        assertFailsWith<IllegalArgumentException> { detector(backgroundScope, wait = Duration.ZERO) }
    }
}
