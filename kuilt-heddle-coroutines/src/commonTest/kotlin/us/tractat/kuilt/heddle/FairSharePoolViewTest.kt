package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** [FairSharePool.view] and the [PoolView] a [FairSharePool.withEngine] block is handed (#3572). */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolViewTest {

    /**
     * Seven waiters, in arrival order. Web's second asks for 12, four times the default, so who goes
     * next depends on each lane's reservations head first, not only on how many wait.
     */
    private val arrivals = listOf(web to 3L, web to 12L, mobile to 3L, web to 3L, mobile to 3L, web to 3L, mobile to 3L)

    private val expectedQueues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))

    /** Queue every arrival on [p], in order, collecting each grant into [grants]. */
    private fun TestScope.queueArrivals(p: FairSharePool, grants: MutableList<Lease>) {
        arrivals.forEach { (leaf, reservation) ->
            backgroundScope.launch { grants += p.acquire(leaf, reservation).granted() }
        }
        runCurrent()
    }

    /**
     * The issue's done-when: from inside [FairSharePool.withEngine], `previewPicks` over the view's
     * own queues lists the leaves in exactly the order the pool grants them. The block raises the
     * slot count from zero, an inner frame that hands out nothing, so every slot frees at once when
     * it ends and nothing changes the engine between the preview and the grants.
     */
    @Test
    fun previewPicksOverTheViewIsTheOrderThePoolGrants() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 0)
        val grants = mutableListOf<Lease>()
        queueArrivals(p, grants)
        var handed: PoolView? = null
        var uniform: List<GroupId> = emptyList()
        val predicted = p.withEngine { engine, view ->
            handed = view
            p.resize(arrivals.size)
            uniform = engine.previewPicks(view.queues.mapValues { (_, queue) -> queue.map { 3L } }, view.waiting)
            engine.previewPicks(view.queues, view.waiting)
        }
        runCurrent()
        val byLease = grants.sortedBy { it.id }
        assertAll(
            { assertEquals(expectedQueues, handed?.queues, "the rig: the view holds each lane's reservations head first") },
            { assertEquals(listOf(web, mobile), handed?.queues?.keys?.toList(), "in arrival order") },
            { assertEquals(arrivals.size, predicted.size, "the rig: every waiter is predicted, so the whole order is compared") },
            { assertNotEquals(uniform, predicted, "the rig: web's 12 changes the order, so the reservations are read") },
            { assertEquals(predicted, byLease.map { it.leaf }, "the pool grants in the predicted order") },
            {
                assertEquals(
                    expectedQueues,
                    byLease.groupBy({ it.leaf }, { it.reserved }),
                    "each lane's leases hold its waiters' reservations, head first",
                )
            },
        )
    }

    @Test
    fun aViewSharesNothingWithThePool() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 0)
        val grants = mutableListOf<Lease>()
        queueArrivals(p, grants)
        val before = p.view()
        val inBlock = p.withEngine { _, view ->
            p.resize(2)
            view
        }
        runCurrent()
        val after = p.view()
        assertAll(
            { assertEquals(PoolView(expectedQueues, open = 0, slots = 0), before) },
            { assertEquals(before, inBlock, "the view a block is handed is taken as it starts") },
            { assertEquals(2, grants.size, "the rig: the pool granted two waiters after both views were taken") },
            { assertEquals(PoolView(expectedQueues, open = 0, slots = 0), before, "the earlier view is unchanged") },
            { assertEquals(PoolView(expectedQueues, open = 0, slots = 0), inBlock, "so is the block's") },
            { assertEquals(2, after.open) },
            { assertEquals(2, after.slots) },
            { assertEquals(arrivals.size - 2, after.queues.values.sumOf { it.size }) },
        )
    }

    @Test
    fun aViewOfAnIdlePoolHasNoQueue() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 3)
        p.acquire(web).granted()
        assertEquals(PoolView(emptyMap(), open = 1, slots = 3), p.view())
    }

    @Test
    fun aViewRefusesAnInvalidState() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { PoolView(emptyMap(), open = -1, slots = 0) } },
            { assertFailsWith<IllegalArgumentException> { PoolView(emptyMap(), open = 0, slots = -1) } },
            { assertFailsWith<IllegalArgumentException> { PoolView(mapOf(web to emptyList()), open = 0, slots = 0) } },
            { assertFailsWith<IllegalArgumentException> { PoolView(mapOf(web to listOf(0L)), open = 0, slots = 0) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    PoolView(mapOf(web to listOf(LocalHeddle.MAX_RESERVATION + 1)), open = 0, slots = 0)
                }
            },
            {
                val lowered = PoolView(mapOf(web to listOf(LocalHeddle.MAX_RESERVATION)), open = 2, slots = 1)
                assertEquals(2, lowered.open, "open may stand above a lowered slot count")
            },
        )
    }
}
