package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [FairSharePool.withSlot] charges a block the time it held its slot, however the block ends
 * (#3556). Every charge is read through `afterCompleteForTest`, so a test sees what reached the
 * engine rather than inferring it from the ledger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolWithSlotTest {

    /** One [FairSharePool.complete] call: the lease, the cost it was charged, and the engine's answer. */
    private data class Completion(val lease: LeaseId, val actualCost: Long, val outcome: LeaseOutcome)

    private fun FairSharePool.recordCompletions(): MutableList<Completion> {
        val completions = mutableListOf<Completion>()
        afterCompleteForTest = { lease, actualCost, outcome -> completions += Completion(lease, actualCost, outcome) }
        return completions
    }

    private suspend fun TestScope.assertNothingLeaked(p: FairSharePool, engine: LocalHeddle) {
        runCurrent()
        val open = p.openForTest
        val leases = engine.snapshot().leases
        val waiters = p.waitersForTest
        val fresh = p.acquire(web)
        assertAll(
            { assertEquals(0, open, "the pool counts no open lease") },
            { assertEquals(emptyList(), leases, "the engine holds no open lease") },
            { assertEquals(0, waiters, "no waiter is queued") },
            { assertEquals(web, fresh.granted().leaf, "a fresh waiter is granted") },
        )
    }

    @Test
    fun costUnitsRoundsUp() {
        // Past Duration's nanosecond range a value is stored in whole milliseconds, so this is
        // 9_223_372_036_854_000_000 ns, not Long.MAX_VALUE: the expectations derive from it.
        val huge = Long.MAX_VALUE.nanoseconds
        val hugeNanos = huge.inWholeNanoseconds
        assertAll(
            { assertEquals(0L, costUnits(Duration.ZERO, 1.seconds)) },
            { assertEquals(1L, costUnits(1.nanoseconds, 1.seconds)) },
            { assertEquals(3L, costUnits(3.seconds, 1.seconds)) },
            { assertEquals(4L, costUnits(3.seconds + 1.nanoseconds, 1.seconds)) },
            { assertEquals(hugeNanos, costUnits(huge, 1.nanoseconds)) },
            // 9_223_372_036_854_000_000 = 3 × 3_074_457_345_618_000_000 exactly.
            { assertEquals(3_074_457_345_618_000_000L, costUnits(huge, 3.nanoseconds)) },
            // 9_223_372_036_854_000_000 = 7_000_000 × 1_317_624_576_693 + 3_000_000, so the ceiling is
            // one more. Here `(e + u - 1) / u` overflows: e + 6_999_999 is past Long.MAX_VALUE.
            { assertEquals(1_317_624_576_694L, costUnits(huge, 7.milliseconds)) },
        )
    }

    @Test
    fun aBlockThatDelaysFiveUnitsIsChargedFive() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val completions = p.recordCompletions()
        val result = p.withSlot(web) {
            delay(5.seconds)
            "done"
        }
        assertAll(
            { assertEquals("done", result) },
            { assertEquals(listOf(5L), completions.map { it.actualCost }) },
            { assertEquals(listOf<LeaseOutcome>(LeaseOutcome.Done(5L, 2L, 0L)), completions.map { it.outcome }) },
            { assertEquals(0, p.openForTest) },
        )
    }

    @Test
    fun aBlockThatThrowsAfterTwoIsChargedTwoAndTheThrowableEscapes() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val completions = p.recordCompletions()
        assertFailsWith<Boom> {
            p.withSlot(web) {
                delay(2.seconds)
                throw Boom()
            }
        }
        assertAll(
            { assertEquals(listOf(2L), completions.map { it.actualCost }) },
            { assertIs<LeaseOutcome.Done>(completions.single().outcome) },
            { assertEquals(0, p.openForTest) },
        )
    }

    @Test
    fun aBlockCancelledAfterThreeIsChargedThreeAndTheCancellationPropagates() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val completions = p.recordCompletions()
        var blockStarted = false
        var blockFinished = false
        val job = launch {
            p.withSlot(web) {
                blockStarted = true
                delay(10.seconds)
                blockFinished = true
            }
        }
        advanceTimeBy(3.seconds)
        runCurrent()
        assertAll(
            { assertTrue(blockStarted, "the rig: the block is running") },
            { assertEquals(emptyList(), completions, "the rig: nothing is charged yet") },
        )
        job.cancel()
        runCurrent()
        assertAll(
            { assertTrue(job.isCancelled) },
            { assertFalse(blockFinished) },
            { assertEquals(listOf(3L), completions.map { it.actualCost }) },
            { assertIs<LeaseOutcome.Done>(completions.single().outcome) },
            { assertEquals(0, p.openForTest) },
            { assertEquals(emptyList(), engine.snapshot().leases) },
        )
    }

    @Test
    fun aBlockShorterThanOneUnitIsChargedOne() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val completions = p.recordCompletions()
        p.withSlot(web) { delay(500.milliseconds) }
        assertEquals(listOf(1L), completions.map { it.actualCost })
    }

    @Test
    fun aBlockThatTakesNoTimeIsChargedZero() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val completions = p.recordCompletions()
        p.withSlot(web) { }
        assertAll(
            { assertEquals(listOf(0L), completions.map { it.actualCost }) },
            { assertEquals(listOf<LeaseOutcome>(LeaseOutcome.Done(0L, 0L, 0L)), completions.map { it.outcome }) },
        )
    }

    @Test
    fun withSlotThrowsWhereAcquireAnswersRefused() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val completions = p.recordCompletions()
        var blockRan = false
        val refused = assertFailsWith<SlotRefusedException> { p.withSlot(nope) { blockRan = true } }
        assertAll(
            { assertEquals<WaitExplanation>(WaitExplanation.NoLiveLineage(nope), refused.reason) },
            { assertIs<IllegalStateException>(refused) },
            { assertFalse(blockRan, "the block never ran") },
            { assertEquals(emptyList(), completions, "nothing was charged") },
        )
    }

    @Test
    fun aWaiterCancelledBeforeItsBlockRunsIsHandedBackNotCharged() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        var blockRan = false
        val b = launch { p.withSlot(web) { blockRan = true } }
        runCurrent()
        assertEquals(1, p.waitersForTest, "the rig: B is queued behind A")
        val completions = p.recordCompletions()
        var fired = 0
        p.beforeResumeForTest = {
            fired++
            b.cancel()
        }
        p.complete(a.id, 3L)
        p.beforeResumeForTest = {}
        runCurrent()
        assertAll(
            { assertEquals(1, fired, "the rig: the hook cancelled B once, just before its resume") },
            { assertTrue(b.isCancelled) },
            { assertFalse(blockRan, "B's block never ran") },
            { assertEquals(listOf(a.id), completions.map { it.lease }, "only A's lease was completed") },
        )
        assertNothingLeaked(p, engine)
    }

    @Test
    fun aCompleteInsideTheBlockIsNotChargedTwice() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val completions = p.recordCompletions()
        var grants = 0
        p.beforeResumeForTest = { if (it is SlotOutcome.Granted) grants++ }
        val b = async { p.withSlot(web) { lease -> p.complete(lease.id, 1L) } }
        val c = async { p.withSlot(web) { lease -> lease.id } }
        runCurrent()
        val bLease = completions.first().lease
        val cLease = c.await()
        runCurrent()
        assertAll(
            { assertEquals(LeaseOutcome.Done(1L, 0L, 0L), b.await(), "the block's own complete settled B") },
            {
                assertEquals(
                    listOf(
                        Completion(bLease, 1L, LeaseOutcome.Done(1L, 0L, 0L)),
                        Completion(bLease, 0L, LeaseOutcome.Unknown),
                        Completion(cLease, 0L, LeaseOutcome.Done(0L, 0L, 0L)),
                    ),
                    completions,
                    "withSlot's own complete of B answers Unknown and charges nothing",
                )
            },
            { assertEquals(2, grants, "B and C were each granted exactly once") },
            { assertEquals(0, p.openForTest) },
        )
        assertNothingLeaked(p, engine)
    }
}
