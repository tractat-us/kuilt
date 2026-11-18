package us.tractat.kuilt.heddle

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pool grants in the order fair share picks, not the order waiters arrive (#3555). */
class FairSharePoolFairnessTest {

    /**
     * One slot, deep queues on both lanes: the pool's grants match a twin engine fed the same calls
     * by hand, acquire by acquire and complete by complete.
     */
    @Test
    fun oneSlotGrantsInTheTwinEnginesOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val held = p.acquire(web).granted()
        val grants = mutableListOf<Lease>()
        val arrivals = List(30) { web } + List(10) { mobile }
        val jobs = arrivals.map { leaf ->
            launch {
                val lease = p.acquire(leaf).granted()
                grants += lease
                p.complete(lease.id, 3L)
            }
        }
        runCurrent()
        p.complete(held.id, 3L)
        jobs.joinAll()
        val poolOrder = grants.sortedBy { it.id }.map { it.leaf }

        val twin = farm()
        twin.reconcileCapacity(1L)
        val twinHeld = assertIs<AcquireOutcome.Acquired>(twin.acquire(setOf(web), setOf(web), startedAt = 0L)).lease
        twin.complete(twinHeld.id, 3L, setOf(web, mobile))
        val left = mutableMapOf(web to 30, mobile to 10)
        val twinOrder = poolOrder.indices.map {
            val before = left.filterValues { it > 0 }.keys.toSet()
            val lease = assertIs<AcquireOutcome.Acquired>(twin.acquire(before, before, startedAt = 0L)).lease
            left[lease.leaf] = left.getValue(lease.leaf) - 1
            val after = left.filterValues { it > 0 }.keys.toSet()
            twin.complete(lease.id, 3L, after)
            lease.leaf
        }
        assertAll(
            { assertEquals(40, poolOrder.size) },
            { assertEquals(twinOrder, poolOrder) },
            { assertTrue(poolOrder.take(8).any { it == mobile }, "the pool serves fair share, not arrival order: $poolOrder") },
        )
    }
}
