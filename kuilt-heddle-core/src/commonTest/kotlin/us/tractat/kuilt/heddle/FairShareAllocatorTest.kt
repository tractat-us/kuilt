package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FairShareAllocator] driven directly, over an in-memory ledger: no seam, no replicator, no
 * coroutines. These are the scheduling properties `HeddleNodeTest` pins through a whole node —
 * the §10.6 wake clamp (#1695), its monotone join (#1714), the one-front joiner set (#1752), the
 * edge-not-level wake detector, and the earmark subtraction — stated against the one copy of the
 * loop that both now run.
 *
 * The numbers are the node tests' numbers, on purpose: the same scenario reaching the same
 * ledger through two harnesses is the evidence that the node delegates rather than diverges.
 */
class FairShareAllocatorTest {

    private val root = GroupId("root")
    private val g1 = GroupId("g1")
    private val e1 = AttachmentId("e1")
    private val e2 = AttachmentId("e2")
    private val self = ReplicaId("self")
    private val policy = PolicyConfig(quantum = 10L)

    /** A single process's ledger: the latest value in a `var`, every write applied in place. */
    private class InMemoryLedgerCell(initial: EntitlementLedger) : LedgerCell {
        override var current: EntitlementLedger = initial
            private set

        /** Every patch this cell has applied, so a test can count writes. */
        var writes: Int = 0
            private set

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            writes++
            return true
        }

        fun apply(patch: Patch<EntitlementLedger>?) {
            current = current.piece(assertNotNull(patch, "the fixture's own write must apply").delta)
        }

        fun merge(other: EntitlementLedger) {
            current = current.piece(other)
        }
    }

    /** `root → g1` and `root → g2`, both active, with [mint] held at the root. */
    private fun flatLedger(mint: Map<ReplicaId, Long>): InMemoryLedgerCell {
        val cell = InMemoryLedgerCell(EntitlementLedger.bootstrap(root, mint, nonce = "genesis"))
        for (record in listOf(
            AttachmentRecord(e1, root, g1, Weight.ONE),
            AttachmentRecord(e2, root, GroupId("g2"), Weight.ONE),
        )) {
            cell.apply(cell.current.prepare(record))
            cell.apply(cell.current.activate(record.id))
        }
        return cell
    }

    private fun newborn(cell: InMemoryLedgerCell, id: String): AttachmentId {
        val record = AttachmentRecord(AttachmentId(id), root, GroupId("g-$id"), Weight.ONE)
        cell.apply(cell.current.prepare(record))
        cell.apply(cell.current.activate(record.id))
        return record.id
    }

    private fun issued(cell: InMemoryLedgerCell, edge: AttachmentId): Long =
        assertNotNull(cell.current.edge(edge), "edge $edge exists").issued

    private val hungry = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

    /** Competing, so the clamp state machine sees it, but `maximumUsefulGrant = 0` trims every grant to nothing. */
    private val competingUnservable = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 0L)

    /**
     * One edge's effective virtual service (raw plus clamp): with [edge] the only child competing,
     * the front *is* that edge's effective virtual service. Reads, never writes.
     */
    private fun FairShareAllocator.effectiveVirtualService(cell: InMemoryLedgerCell, edge: AttachmentId): Rational =
        assertNotNull(virtualTime(cell.current, root) { mapOf(edge to hungry) }, "root has active children")

    @Test
    fun aWakingChildIsClampedToTheFrontInsteadOfBursting() {
        val cell = flatLedger(mapOf(self to 500L))
        val allocator = FairShareAllocator(self, policy)

        // g1 runs alone to its target; g2 is observed idle, so its next demand is a wake.
        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to Demand(200L, 10L)) }
        assertAll(
            { assertEquals(200L, issued(cell, e1), "e1 runs alone to its target") },
            { assertEquals(0L, issued(cell, e2), "e2 never demanded") },
        )

        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to hungry, e2 to hungry) }
        assertAll(
            { assertEquals(150L, issued(cell, e2), "the waker takes its fair half, not a burst") },
            { assertEquals(350L, issued(cell, e1), "the incumbent keeps its half of what is left") },
        )
    }

    @Test
    fun aNewbornAndAWakerInOneRoundAreSeatedOnTheSameFront() {
        val cell = flatLedger(mapOf(self to 500L))
        val allocator = FairShareAllocator(self, policy)
        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to Demand(200L, 10L)) }

        // One round: e3 is born and e2 wakes. Nothing is servable, so the gauge reads back as the seat.
        val e3 = newborn(cell, "e3")
        val granted = allocator.schedule(root, cell, earmarked = 0L) {
            mapOf(e1 to competingUnservable, e2 to competingUnservable, e3 to competingUnservable)
        }
        assertAll(
            { assertEquals(0, granted, "nothing is servable in the joining round") },
            {
                assertEquals(
                    Gauge(Rational.of(200L), folded = 0L),
                    cell.current.gauge(e3),
                    "seated at the front over e1 alone — the waker is a joiner too",
                )
            },
        )

        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to hungry, e2 to hungry, e3 to hungry) }
        assertAll(
            { assertEquals(100L, issued(cell, e3), "the newborn takes a third of what is left") },
            { assertEquals(100L, issued(cell, e2), "and so does the waker, clamped to the same front") },
            { assertEquals(300L, issued(cell, e1), "and the incumbent keeps its third") },
        )
    }

    /**
     * The wake detector fires on an observed idle→demand **transition**, never on first sight.
     *
     * Peer B seats e3 at the origin and runs e1 and e2 to 100 each on its own holdings; A merges
     * that and meets all three for the first time, e3 already 100 behind. Treating first sight as a
     * wake would make e1 and e3 joiners, clamp e3 to the front over the one non-joiner, and split
     * A's round between them instead of paying e3 its deficit.
     *
     * **e2 is load-bearing.** It is the non-joiner that holds that front at 100. Run e1 alone on B
     * instead, as `HeddleNodeTest.firstObservationOfADemandingEdgeIsNotTreatedAsAWake` does, and
     * the only non-joiner sits idle at the origin: the wrongly-detected wake clamps e3 to 0, which
     * moves nothing, and the test stays green with first sight counted as a wake. Measured: that
     * mutation reds this test and leaves the node-level one green.
     */
    @Test
    fun aFirstObservationIsNotAWake() {
        val a = ReplicaId("a")
        val b = ReplicaId("b")
        val onB = flatLedger(mapOf(a to 100L, b to 200L))
        val e3 = newborn(onB, "e3")
        FairShareAllocator(b, policy).schedule(root, onB, earmarked = 0L) { mapOf(e1 to hungry, e2 to hungry) }
        val onA = InMemoryLedgerCell(onB.current)
        assertAll(
            { assertEquals(100L, issued(onA, e1), "A sees e1's run") },
            { assertEquals(100L, issued(onA, e2), "and e2's") },
            { assertEquals(Rational.ZERO, assertNotNull(onA.current.gauge(e3)).floor, "and e3 seated at the origin") },
        )

        FairShareAllocator(a, policy).schedule(root, onA, earmarked = 0L) { mapOf(e1 to hungry, e3 to hungry) }
        assertAll(
            { assertEquals(100L, issued(onA, e3), "an unclamped first sight honours e3's deficit in full") },
            { assertEquals(100L, issued(onA, e1), "and e1, 100 ahead, takes none of A's grants") },
        )
    }

    @Test
    fun aRewakeCannotLowerAnEffectiveVirtualService() {
        // 220 = e1's 200 plus e2's 20: holdings run out from the second round on, so no later grant
        // can move a raw virtual service and mask the clamp.
        val cell = flatLedger(mapOf(self to 220L))
        val allocator = FairShareAllocator(self, policy)
        val starved = newborn(cell, "e3")

        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to Demand(200L, 10L), starved to competingUnservable) }
        assertEquals(200L, issued(cell, e1), "e1 runs alone to its target")

        // e2 wakes into a front of mean(e1 at 200, e3 at 0) = 100, then runs its 20.
        allocator.schedule(root, cell, earmarked = 0L) {
            mapOf(e1 to competingUnservable, e2 to Demand(20L, 10L), starved to competingUnservable)
        }
        assertAll(
            { assertEquals(20L, issued(cell, e2), "the waker takes the last 20") },
            { assertEquals(Rational.of(120L), allocator.effectiveVirtualService(cell, e2), "clamped by 100, then ran 20") },
        )

        // e2 sleeps; then e1 stops too, and e2 re-wakes into a front of e3 alone, at 0.
        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to competingUnservable, starved to competingUnservable) }
        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e2 to hungry, starved to competingUnservable) }

        assertEquals(
            Rational.of(120L),
            allocator.effectiveVirtualService(cell, e2),
            "a re-wake into a lower front must not refund the earlier clamp (replacement gives 20)",
        )
    }

    @Test
    fun earmarkedUnitsAreNeverDelegated() {
        val cell = flatLedger(mapOf(self to 100L))
        val allocator = FairShareAllocator(self, policy)

        val granted = allocator.schedule(root, cell, earmarked = 40L) { mapOf(e1 to hungry, e2 to hungry) }
        assertAll(
            { assertEquals(6, granted, "60 delegable units at a quantum of 10") },
            { assertEquals(60L, issued(cell, e1) + issued(cell, e2), "the 40 earmarked units stay at the root") },
            { assertEquals(40L, cell.current.holdings(root, self), "…where the reservation can still spend them") },
        )
        assertEquals(0, allocator.schedule(root, cell, earmarked = 40L) { mapOf(e1 to hungry) }, "and a rerun finds nothing")
    }

    /**
     * An edge that appears **between the seat and the first pick** — a remote `Activate` merged
     * mid-call — is not a candidate this round (#1752). It has no gauge, so it reads from its own
     * origin; were it picked, `delegate` would write its gauge at that origin and `seat` would
     * refuse it forever.
     */
    @Test
    fun anEdgeArrivingMidRoundIsNotACandidateUntilSeated() {
        val base = flatLedger(mapOf(self to 100L))
        val late = AttachmentRecord(AttachmentId("late"), root, GroupId("g-late"), Weight.ONE)
        var injected = false
        val cell = object : LedgerCell {
            override val current: EntitlementLedger get() = base.current
            override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
                // The first write is the seat bump; inject before the second, the first pick.
                if (base.writes == 1 && !injected) {
                    injected = true
                    base.apply(base.current.prepare(late))
                    base.apply(base.current.activate(late.id))
                }
                return base.mutateOrSkip(transform)
            }
        }
        val allocator = FairShareAllocator(self, policy)

        allocator.schedule(root, cell, earmarked = 0L) { mapOf(e1 to hungry, e2 to hungry, late.id to hungry) }
        assertAll(
            { assertTrue(injected, "the rig fired: the late edge arrived after the seat, before a pick") },
            { assertEquals(0L, issued(base, late.id), "it took no grant this round") },
            { assertNull(base.current.gauge(late.id), "and no gauge, so the next round can still seat it") },
            { assertEquals(100L, issued(base, e1) + issued(base, e2), "the seated edges took the whole supply") },
        )

        // The next round seats it; `seat` refusing it would leave the gauge absent.
        allocator.schedule(root, base, earmarked = 0L) { mapOf(late.id to hungry) }
        assertNotNull(base.current.gauge(late.id), "seated on the following round")
    }
}
