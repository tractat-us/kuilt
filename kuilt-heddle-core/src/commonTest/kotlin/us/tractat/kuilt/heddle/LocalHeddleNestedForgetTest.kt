package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An engine whose attached groups come and go stays the size of what it holds now (#3314, design
 * §5.5): a repository detached after its authors is forgotten too, not kept for good.
 *
 * Each cycle attaches an author beneath a repository, leases and completes one unit there, and
 * detaches the repository, which lands whole. The ledger's edge count and the snapshot's size after
 * [CYCLES] cycles must be where they were after [WARM], whatever the repository is called: a new
 * name each cycle, the same name attached again, or a new name with the lease held across the
 * detach, which waits on it, and completed after, alternately over and under its reservation. The
 * one-level case beside them is the baseline the forgetting design measured.
 */
class LocalHeddleNestedForgetTest {

    private val policy = LocalPolicy(defaultReservation = 1L)
    private val nested = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = ChildTemplate(Weight.of(2), template = ChildTemplate(Weight.ONE)))))
    private val flat = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = ChildTemplate(Weight.of(2)))))

    private class Run(val edgesAtWarm: Int, val bytesAtWarm: Int, val edgesAtEnd: Int, val bytesAtEnd: Int, val leases: Int, val detaches: Int, val retired: Int)

    private fun run(t: Topology, depth: Int, sameName: Boolean, holdAcross: Boolean = false): Run {
        val h = LocalHeddle.bootWhole(t, policy, epoch = 0L).also { it.reconcileCapacity(4L) }
        fun bytes() = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist()).length
        var leases = 0
        var detaches = 0
        var retired = 0
        var warm = 0 to 0
        for (i in 0 until CYCLES) {
            val top = GroupPath.Attached(listOf("ci"), if (sameName) "kuilt" else "r$i")
            val leaf = if (depth == 2) GroupPath.Nested(top, "alice") else top
            check(h.attach(leaf, generation = i + 1L).refusals.isEmpty()) { "the rig: cycle $i attaches" }
            val id = checkNotNull(t.findGroupId(leaf))
            val acquired = h.acquire(setOf(id), setOf(id), startedAt = i.toLong())
            if (holdAcross) {
                // The detach waits on the lease, which then completes over or under its reservation.
                val waited = h.detach(top)
                retired += waited.applied.count { it is TopologyStep.Retire }
                val cost = if (i % 2 == 0) 3L else 0L
                if (waited.refusals.isNotEmpty() && waited.refusals.all { it is StepRefusal.Draining } &&
                    acquired is AcquireOutcome.Acquired && h.complete(acquired.lease.id, actualCost = cost, waiting = emptySet()) is LeaseOutcome.Done
                ) {
                    leases++
                }
            } else if (acquired is AcquireOutcome.Acquired && h.complete(acquired.lease.id, actualCost = 1L, waiting = emptySet()) is LeaseOutcome.Done) {
                leases++
            }
            val detach = h.detach(top)
            if (detach.refusals.isEmpty()) detaches++
            retired += detach.applied.count { it is TopologyStep.Retire }
            if (i + 1 == WARM) warm = h.snapshot().ledger.allEdges().size to bytes()
        }
        return Run(warm.first, warm.second, h.snapshot().ledger.allEdges().size, bytes(), leases, detaches, retired)
    }

    @Test
    fun detachedRepositoriesAreForgottenWithTheirAuthors() {
        val runs = listOf(
            "one level" to run(flat, depth = 1, sameName = false),
            "nested, a new repository each cycle" to run(nested, depth = 2, sameName = false),
            "nested, the same repository again" to run(nested, depth = 2, sameName = true),
            "nested, the lease held across the detach" to run(nested, depth = 2, sameName = false, holdAcross = true),
        )
        assertAll(
            *runs.flatMap { (name, r) ->
                val levels = if (name == "one level") 1 else 2
                listOf(
                    { assertEquals(CYCLES, r.leases, "$name — the rig: every cycle leased and completed") },
                    { assertEquals(CYCLES, r.detaches, "$name — the rig: every detach landed whole") },
                    { assertEquals(CYCLES * levels, r.retired, "$name — the rig: every detach retired the whole subtree") },
                    { assertEquals(r.edgesAtWarm, r.edgesAtEnd, "$name: edges after $WARM cycles, then after $CYCLES") },
                    // Room for the names and generations growing a digit, nothing per cycle.
                    { assertTrue(r.bytesAtEnd <= r.bytesAtWarm + 64, "$name: ${r.bytesAtWarm} bytes after $WARM cycles, ${r.bytesAtEnd} after $CYCLES") },
                )
            }.toTypedArray(),
        )
    }

    private companion object {
        const val WARM = 20
        const val CYCLES = 200
    }
}
