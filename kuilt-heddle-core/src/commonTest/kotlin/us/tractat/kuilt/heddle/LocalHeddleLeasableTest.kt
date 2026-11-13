package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One leasable predicate (#3173, plan Task L3, Amendment 9): a lease ends only at a leaf that the
 * topology declares a lane or a runtime child of a marked group. The ledger alone reads two other
 * groups here as leaves, and neither may be leased: `pool`, marked for runtime children with none
 * attached, and `acme`, whose one declared lane's edge has closed.
 */
class LocalHeddleLeasableTest {

    /** `root → {acme → batch, pool (marked, empty), tenants (marked) → !c-7f3a, s}`, every weight 1. */
    private val t = Topology(
        "root",
        listOf(
            Group("acme", Weight.ONE, listOf(Lane("batch"))),
            Group("pool", Weight.ONE, emptyList(), runtimeChildren = RuntimeChildren(Weight.ONE)),
            Group("tenants", Weight.ONE, emptyList(), runtimeChildren = RuntimeChildren(Weight.ONE)),
            Lane("s"),
        ),
    )
    private val acme = t.groupId("acme")
    private val pool = t.groupId("pool")
    private val s = t.groupId("s")
    private val c = t.runtimeChildId(listOf("tenants"), "c-7f3a")

    /** The tree with `c-7f3a` attached under `tenants` and `acme → batch` closed, with [supply] at the root. */
    private fun engine(supply: Long = 100L): LocalHeddle {
        var l = bootFrom(t)
        l = applyAll(t.attachRuntimeChild(listOf("tenants"), "c-7f3a", l, generation = 0L), l).also {
            assertEquals(emptyList(), it.refused, "the rig: the attach applies")
        }.ledger
        val batchEdge = assertNotNull(l.lineageOf(t.groupId("acme", "batch"))).last()
        l = l.piece(assertNotNull(l.close(batchEdge)).delta)
        return engineOver(l, t.groupId(), supply, leasable = Leasable.ByTopology(t))
    }

    @Test
    fun admitRefusesAGroupThatIsNotALaneOrRuntimeChild() {
        val h = engine()
        val before = h.snapshot().ledger
        val refusedPool = h.admit(pool, startedAt = 0L)
        val refusedAcme = h.admit(acme, startedAt = 0L)
        val unchanged = h.snapshot().ledger
        val lane = h.admit(s, startedAt = 0L)
        val runtime = h.admit(c, startedAt = 0L)
        assertAll(
            { assertTrue(before.isLeaf(pool) && before.isLeaf(acme), "the rig: the ledger reads both groups as leaves") },
            { assertEquals(AcquireOutcome.NoLiveLineage(pool), refusedPool, "a marked group with nothing attached") },
            { assertEquals(AcquireOutcome.NoLiveLineage(acme), refusedAcme, "a group whose declared lane closed") },
            { assertSame(before, unchanged, "neither refusal changed the ledger") },
            { assertEquals(s, (lane as? AcquireOutcome.Acquired)?.lease?.leaf, "a declared lane: $lane") },
            { assertEquals(c, (runtime as? AcquireOutcome.Acquired)?.lease?.leaf, "an attached runtime child: $runtime") },
        )
    }

    @Test
    fun acquireNeverEndsAtAGroupThatIsNotALaneOrRuntimeChild() {
        val h = engine()
        val everyone = setOf(acme, pool, s, c)
        val before = h.snapshot().ledger
        val alone = h.acquire(setOf(acme, pool), setOf(acme, pool), startedAt = 0L)
        val unchanged = h.snapshot().ledger
        val explained = listOf(acme, pool).map { h.explain(it, everyone, everyone) }
        val share = h.entitlement(s, everyone, totalSlots = 4L)
        val outs = (0 until 20).map {
            val out = h.acquire(everyone, everyone, startedAt = it.toLong())
            if (out is AcquireOutcome.Acquired) h.complete(out.lease.id, 1L)
            out
        }
        val leaves = outs.map { (it as? AcquireOutcome.Acquired)?.lease?.leaf ?: GroupId("$it") }
        assertAll(
            { assertEquals(AcquireOutcome.NoLiveLineage(t.groupId()), alone, "with only the two groups waiting, nothing can be leased") },
            { assertSame(before, unchanged) },
            { assertEquals(listOf(WaitExplanation.NoLiveLineage(acme), WaitExplanation.NoLiveLineage(pool)), explained) },
            { assertEquals(listOf(Rational.ZERO, Rational.ZERO), listOf(acme, pool).map { h.entitlement(it, everyone, 4L) }) },
            // Only s and tenants compete at the root, so s is owed half; with the groups counted it would be a quarter.
            { assertEquals(Rational.of(2L), share) },
            { assertEquals(10, leaves.count { it == s }, "s and c alternate: $leaves") },
            { assertEquals(10, leaves.count { it == c }, "s and c alternate: $leaves") },
        )
    }
}
