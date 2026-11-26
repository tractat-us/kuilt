package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A pool grants a waiter that asked for less than the engine's default a lease holding exactly
 * what it asked (#3648): the slot is picked at the default and shrunk straight after the pick. The
 * engine is then the one a twin reaches with no shrink at all, by admitting each granted leaf at
 * what its waiter asked, and the grants come in the order `previewPicks(queues, …)` predicts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolShrinkAtGrantTest {

    /** `farm → p:2 → {p0:3, p1:1}`, `farm → q:1 → {q0:1, q1:2}`: every lease crosses two edges. */
    private val tree = draft("farm") {
        group("p", Weight.of(2)) {
            lane("p0", Weight.of(3))
            lane("p1")
        }
        group("q") {
            lane("q0")
            lane("q1", Weight.of(2))
        }
    }
    private val leaves = listOf(tree.groupId("p", "p0"), tree.groupId("p", "p1"), tree.groupId("q", "q0"), tree.groupId("q", "q1"))

    /**
     * Waiters in arrival order, asking either side of the default of [R]: eight of the eleven below
     * it, one at it, two above.
     */
    private val arrivals = listOf(
        leaves[0] to 1L, leaves[2] to 2L, leaves[1] to 4L, leaves[3] to 1L, leaves[0] to 7L, leaves[2] to 1L,
        leaves[1] to 3L, leaves[3] to 2L, leaves[0] to 2L, leaves[2] to 6L, leaves[3] to 1L,
    )

    /** The engine with capacity reconciled and a seeded history behind it, every lease closed. Built twice, it is the same engine. */
    private fun warmed(): LocalHeddle {
        val (h, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = R), epoch = 0L)
        check(booted.refusals.isEmpty())
        h.reconcileCapacity(arrivals.size.toLong())
        val rng = Random(WARM_SEED)
        repeat(WARM_UP) { step ->
            val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet().ifEmpty { setOf(leaves[step % leaves.size]) }
            val picked = h.acquire(waiting, waiting, startedAt = 0L, reservation = 1L + rng.nextInt(6))
            check(picked is AcquireOutcome.Acquired) { "warm-up step $step: $picked" }
            h.complete(picked.lease.id, rng.nextInt(9).toLong(), waiting)
        }
        return h
    }

    /**
     * The grants a pool over [h] makes for [queues], built with no shrink: the leaf a pick at [R]
     * goes to is read off a count-based preview of one pick, which changes nothing, and the grant is
     * an admit of it at `min(size, R)`, extended past [R] by the rest. [size] is what the grant holds.
     */
    private fun model(h: LocalHeddle, queues: Map<GroupId, List<Long>>, size: (Long) -> Long): List<GroupId> {
        val remaining = queues.mapValuesTo(LinkedHashMap()) { (_, queue) -> ArrayDeque(queue) }
        val picks = ArrayList<GroupId>()
        while (true) {
            val waiting = remaining.filterValues { it.isNotEmpty() }.keys.toSet()
            if (waiting.isEmpty()) break
            val leaf = h.previewPicks(waiting.associateWith { 1 }, waiting, listOf(R)).single()
            val held = size(remaining.getValue(leaf).removeFirst())
            val admitted = h.admit(leaf, waiting, startedAt = 0L, reservation = minOf(held, R))
            check(admitted is AcquireOutcome.Acquired && admitted.lease.reserved == minOf(held, R)) { "the model's admit of ${leaf.value}: $admitted" }
            if (held > R) {
                val stillWaiting = remaining.filterValues { it.isNotEmpty() }.keys
                check(h.extend(admitted.lease.id, held - R, stillWaiting) == LeaseOutcome.Done(0L, 0L, 0L))
            }
            picks += leaf
        }
        return picks
    }

    /** Every edge on a leaf's lineage, and its raw position. */
    private fun positions(h: LocalHeddle): Map<AttachmentId, VirtualTime?> {
        val ledger = h.snapshot().ledger
        return leaves.flatMap { ledger.lineageOf(it).orEmpty() }.distinct().sorted().associateWith { ledger.virtualServiceWide(it) }
    }

    private fun nextPicks(h: LocalHeddle): List<GroupId> =
        h.previewPicks(leaves.associateWith { NEXT_PICKS }, leaves.toSet(), List(NEXT_PICKS) { 1L + it % 5 })

    @Test
    fun aPoolGrantsAWaiterBelowTheDefaultALeaseAtItsOwnSize() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = warmed()
        val p = pool(slots = 0, engine = engine)
        val grants = mutableListOf<Lease>()
        arrivals.forEach { (leaf, asked) -> backgroundScope.launch { grants += p.acquire(leaf, asked).granted() } }
        runCurrent()
        var queues: Map<GroupId, List<Long>> = emptyMap()
        val predicted = p.withEngine { e, view ->
            queues = view.queues
            p.resize(arrivals.size)
            e.previewPicks(view.queues, view.waiting)
        }
        runCurrent()
        val byLease = grants.sortedBy { it.id }

        val twin = warmed()
        val modelled = model(twin, queues) { it }
        val unshrunk = warmed()
        val modelledUnshrunk = model(unshrunk, queues) { maxOf(it, R) }
        val asked = arrivals.groupBy({ it.first }, { it.second })
        assertAll(
            { assertEquals(arrivals.size, byLease.size, "the rig: every waiter is granted") },
            { assertEquals(8, arrivals.count { it.second < R }, "the rig: eight waiters ask below the default") },
            { assertEquals(asked, byLease.groupBy({ it.leaf }, { it.reserved }), "each lease holds what its waiter asked, head first") },
            { assertEquals(byLease.map { it.reserved }, engine.snapshot().leases.sortedBy { it.id }.map { it.reserved }, "on the engine's side too") },
            { assertEquals(modelled, byLease.map { it.leaf }, "the grants against the model with no shrink") },
            { assertEquals(predicted, byLease.map { it.leaf }, "and the preview predicts them") },
            { assertEquals(positions(twin), positions(engine), "positions") },
            { assertEquals(twin.snapshot().allocatorMemory, engine.snapshot().allocatorMemory, "the wake memory") },
            { assertEquals(twin.snapshot().leases, engine.snapshot().leases, "the open leases") },
            { assertEquals(nextPicks(twin), nextPicks(engine), "the next $NEXT_PICKS picks") },
            // The control: a pool that skipped the shrink would hold max(asked, R), and the oracle tells it apart.
            { assertNotEquals(positions(unshrunk), positions(engine), "the control: unshrunk grants leave other positions") },
            { assertNotEquals(modelledUnshrunk, modelled, "and another order") },
        )
    }

    private companion object {
        const val R = 4L
        const val WARM_SEED = 3648
        const val WARM_UP = 30
        const val NEXT_PICKS = 30
    }
}
