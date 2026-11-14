package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalHeddle] forgets a retired edge in the swap that retires it (#3192, design §5.5), so an
 * engine whose runtime children come and go stays the same size however long it runs.
 */
class LocalHeddleForgetTest {

    private val tree = Topology(
        "root",
        listOf(
            Topology.Group("tenants", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
            Topology.Lane("steady"),
        ),
    )
    private val policy = LocalPolicy(defaultReservation = 2L)
    private val tenants = listOf("tenants")

    private fun engine(): LocalHeddle = LocalHeddle.fromTopology(tree, policy, epoch = 0L).also { it.reconcileCapacity(4L) }

    private fun encodedSize(h: LocalHeddle): Int = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist()).length

    /** Attach `u[i]`, serve one lease there, settle it, and detach it: one user arriving and leaving. */
    private fun LocalHeddle.cycle(i: Int) {
        check(attachRuntimeChild(tenants, "u$i", generation = 0L).refusals.isEmpty()) { "attach u$i" }
        val lease = lease(setOf(tree.runtimeChildId(tenants, "u$i")), reservation = 2L, startedAt = i.toLong())
        check(complete(lease.id, 1L + i % 3) is LeaseOutcome.Done) { "complete u$i" }
        check(detachRuntimeChild(tenants, "u$i").refusals.isEmpty()) { "detach u$i" }
    }

    /**
     * Two thousand users arrive, are served once and leave, and the snapshot after the last is
     * within a few digits of the snapshot after the twentieth. Before the engine forgot, every
     * departure left a retired edge behind, about 200 bytes of it.
     */
    @Test
    fun detachAndReattachKeepTheSnapshotBounded() {
        val h = engine()
        repeat(20) { h.cycle(it) }
        val warm = encodedSize(h)
        for (i in 20 until 2_000) h.cycle(i)
        val after = encodedSize(h)
        assertAll(
            { assertTrue(h.snapshot().ledger.leafSpentTotal() >= 2_000L, "the rig: every user was served") },
            { assertTrue(after <= warm + 64, "the snapshot grew from $warm to $after bytes over 1,980 departures") },
        )
    }

    /**
     * A prepared edge nothing wants carries nothing, so the swap forgets it with only the floor
     * moved: here one for `steady` at weight 2, left by a reload that never activated it. The
     * next swap, an unrelated attach, forgets it; a later reweight of `steady` to 2 prepares a new
     * edge above it rather than adopting it; and the ledger refuses the old id outright.
     */
    @Test
    fun aPreparedEdgeNothingWantsIsForgottenWithOnlyTheFloorMoved() {
        val h = engine()
        val steady = tree.groupId("steady")
        val stale = AttachmentRecord(attachmentIdOf(steady, Weight.of(2), 5L), tree.groupId(), steady, Weight.of(2))
        h.reshapeForTest { l -> l.piece(checkNotNull(l.prepare(stale)).delta) }
        val before = h.snapshot().ledger
        check(h.attachRuntimeChild(tenants, "u", generation = 0L).refusals.isEmpty())
        val after = h.snapshot().ledger
        val reweighted = Topology("root", listOf(tree.children[0], Topology.Lane("steady", Weight.of(2))))
        val reload = h.apply(reweighted, generation = 0L)
        val prepared = reload.applied.filterIsInstance<TopologyStep.Prepare>().map { it.record.id }
        assertAll(
            { assertEquals(Lifecycle.PREPARED, before.lifecycle(stale.id), "the rig: the stale edge is prepared") },
            { assertNull(after.lifecycle(stale.id), "the attach's swap forgot it") },
            { assertEquals(5L, after.forgottenGenerationFloor(), "its generation is still spent") },
            { assertEquals(before.holdings(tree.groupId(), LOCAL), after.holdings(tree.groupId(), LOCAL), "and nothing else moved") },
            { assertNull(after.prepare(stale), "the old id is refused") },
            {
                val fresh = prepared.singleOrNull()
                assertTrue(fresh != null && fresh != stale.id && checkNotNull(generationOf(fresh)) > 5L, "the reweight prepares above it: $reload")
            },
            { assertTrue(reload.refusals.isEmpty(), "and lands: $reload") },
        )
    }
}
