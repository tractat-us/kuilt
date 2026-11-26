package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FairShareAllocator] driven directly, over an in-memory ledger: no seam, no replicator, no
 * coroutines. These are the scheduling properties `HeddleNodeTest` pins through a whole node —
 * the §10.6 wake clamp (#1695), a return that keeps it (#1714), the one-front joiner set (#1752), the
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

    /** A fixed appetite per edge; every edge not named wants nothing. */
    private fun demandOf(vararg wants: Pair<AttachmentId, Demand>): (AttachmentId) -> Demand {
        val byEdge = mapOf(*wants)
        return { edge -> byEdge[edge] ?: Demand.NONE }
    }

    /**
     * One edge's effective virtual service (raw plus clamp): with [edge] the only child competing,
     * the front *is* that edge's effective virtual service. Reads, never writes.
     */
    private fun FairShareAllocator.effectiveVirtualService(cell: InMemoryLedgerCell, edge: AttachmentId): Rational =
        assertNotNull(virtualTime(cell.current, root, demandOf(edge to hungry)), "root has active children")

    @Test
    fun aWakingChildIsClampedToTheFrontInsteadOfBursting() {
        val cell = flatLedger(mapOf(self to 500L))
        val allocator = FairShareAllocator(self, policy)

        // g1 runs alone to its target; g2 is observed idle, so its next demand is a wake.
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to Demand(200L, 10L)))
        assertAll(
            { assertEquals(200L, issued(cell, e1), "e1 runs alone to its target") },
            { assertEquals(0L, issued(cell, e2), "e2 never demanded") },
        )

        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry, e2 to hungry))
        assertAll(
            { assertEquals(150L, issued(cell, e2), "the waker takes its fair half, not a burst") },
            { assertEquals(350L, issued(cell, e1), "the incumbent keeps its half of what is left") },
        )
    }

    @Test
    fun aNewbornAndAWakerInOneRoundAreSeatedOnTheSameFront() {
        val cell = flatLedger(mapOf(self to 500L))
        val allocator = FairShareAllocator(self, policy)
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to Demand(200L, 10L)))

        // One round: e3 is born and e2 wakes. Nothing is servable, so the round writes nothing (#3353)
        // and keeps only what it observed: e2's clamp to the front.
        val e3 = newborn(cell, "e3")
        val granted = allocator.schedule(
            root,
            cell,
            earmarked = 0L,
            demandOf(e1 to competingUnservable, e2 to competingUnservable, e3 to competingUnservable),
        )
        assertAll(
            { assertEquals(AllocationOutcome.NothingToDelegate, granted, "nothing is servable in the joining round") },
            { assertNull(cell.current.gauge(e3), "and an empty round writes no seat") },
        )

        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry, e2 to hungry, e3 to hungry))
        // Each checkpoint writes the gross position at the new issuance with that issuance as its fold,
        // so `ticks − folded · G` is the seat the first grant landed with.
        val seat = assertNotNull(cell.current.gauge(e3)).let { it.ticks - Int128.of(it.folded * TICKS_PER_UNIT) }
        assertAll(
            { assertEquals(Int128.of(200L * TICKS_PER_UNIT), seat, "seated at the front over e1 alone — the waker is a joiner too") },
            { assertEquals(100L, issued(cell, e3), "the newborn takes a third of what is left") },
            { assertEquals(100L, issued(cell, e2), "and so does the waker, clamped to the same front") },
            { assertEquals(300L, issued(cell, e1), "and the incumbent keeps its third") },
        )
    }

    /**
     * The restart rule (design §7.2): a seated child this allocator has never observed, and finds
     * demanding, is a waker, clamped to the front like any other.
     *
     * Peer B seats e3 at the origin and runs e1 to 100 and e2 to 60 on its own holdings; A merges
     * that and meets all three for the first time, e3 already 100 behind. On A, e1 and e3 are both
     * first seen demanding, so both are joiners. No child outside them is demanding, so the front
     * is the furthest-ahead child, e1 at 100, and e3 is clamped level with it: A's 100 units split
     * between them, where an unclamped e3 would have taken all 100. e2 is filled at 60 so that the
     * second-furthest-ahead child is a different place: a front taken one off the top would clamp
     * e3 to 60 and give it 70 of A's units. A first sighting carries no evidence of
     * how long the child idled, and leaving it its raw position would let a child that idled under
     * another scheduler, or before a restart, keep the whole gap as credit. Losing its lag once per
     * allocator that first sees it is the bounded side (§10.6, #3472).
     *
     * e1 spends what it was granted before A's call. The clamp counts what a waker holds, past the
     * front (#3721), so this rig empties e1 to isolate the front: what the clamp does with e1's
     * holdings is [aFirstObservationOfAFrontChildStillHoldingItsGrantsLeavesItsSiblingThatGap].
     *
     * The node-level twin is `HeddleNodeTest.aFirstObservationOfASeatedDemandingEdgeIsAWake`.
     */
    @Test
    fun aFirstObservationOfASeatedDemandingChildIsAWake() {
        val a = ReplicaId("a")
        val b = ReplicaId("b")
        val onB = flatLedger(mapOf(a to 100L, b to 160L))
        val e3 = newborn(onB, "e3")
        val filledAt60 = Demand(targetOutstanding = 60L, maximumUsefulGrant = 10L)
        FairShareAllocator(b, policy).schedule(root, onB, earmarked = 0L, demandOf(e1 to hungry, e2 to filledAt60))
        // e1's work runs: the clamp counts holdings (#3721), so this rig empties e1 to isolate the front.
        onB.apply(onB.current.spend(b, g1, 100L))
        val onA = InMemoryLedgerCell(onB.current)
        assertAll(
            { assertEquals(100L, issued(onA, e1), "A sees e1's run") },
            { assertEquals(0L, assertNotNull(onA.current.edge(e1)).outstanding, "the rig: and e1 spent it all") },
            { assertEquals(60L, issued(onA, e2), "and e2's, filled at 60: the rig, a second-furthest-ahead child below e1") },
            { assertEquals(gaugeOfUnits(0L, 0L), onA.current.gauge(e3), "and e3 seated at the origin, 100 behind") },
        )

        FairShareAllocator(a, policy).schedule(root, onA, earmarked = 0L, demandOf(e1 to hungry, e3 to hungry))
        assertAll(
            { assertEquals(50L, issued(onA, e3), "e3, first seen demanding, is clamped level with e1 and takes half") },
            { assertEquals(150L, issued(onA, e1), "and e1 takes the other half") },
        )
    }

    /**
     * [aFirstObservationOfASeatedDemandingChildIsAWake] with e1 still holding its 100: the clamp's
     * known error, pinned (#3721, ruling 3). e1 is the front, and a waker lands past the front by
     * what its edge has outstanding, so e1 is clamped 100 past itself and e3 to the front, 100
     * behind it. e3 then takes all 100 of A's units: e1 100, e3 100.
     *
     * An allocator that had watched B's run, where e3 idled while e1 was granted, would not clamp
     * e1 at all and would read 150/50. One that had watched e3 compete for those 100 would leave
     * the gap. A first sighting cannot tell the two histories apart, and this clamp takes the first:
     * e3 keeps exactly e1's outstanding of lag, the bound the clamp promises
     * (`FairShareAllocator`'s *Two histories*). The other choice, clamping e1 by less, credits a
     * holder its unspent grants, which #3238 ruled out.
     */
    @Test
    fun aFirstObservationOfAFrontChildStillHoldingItsGrantsLeavesItsSiblingThatGap() {
        val a = ReplicaId("a")
        val b = ReplicaId("b")
        val onB = flatLedger(mapOf(a to 100L, b to 160L))
        val e3 = newborn(onB, "e3")
        val filledAt60 = Demand(targetOutstanding = 60L, maximumUsefulGrant = 10L)
        FairShareAllocator(b, policy).schedule(root, onB, earmarked = 0L, demandOf(e1 to hungry, e2 to filledAt60))
        val onA = InMemoryLedgerCell(onB.current)
        val held = assertNotNull(onA.current.edge(e1)).outstanding
        assertAll(
            { assertEquals(100L, issued(onA, e1), "A sees e1's run") },
            { assertEquals(100L, held, "the rig: and e1 holds all of it") },
            { assertEquals(gaugeOfUnits(0L, 0L), onA.current.gauge(e3), "and e3 seated at the origin, 100 behind") },
        )

        val allocator = FairShareAllocator(a, policy)
        allocator.schedule(root, onA, earmarked = 0L, demandOf(e1 to hungry, e3 to hungry))
        val offsets = allocator.memory.wakeOffsets
        assertAll(
            { assertEquals(100L, issued(onA, e3), "e3 takes all of A's units, where kept memory gives it 50") },
            { assertEquals(100L, issued(onA, e1), "and e1 none, where kept memory gives it 150") },
            { assertEquals(Int128.of(held * TICKS_PER_UNIT), offsets[e1], "e1 is clamped past itself by exactly its outstanding") },
            {
                assertEquals(
                    Int128.of(100L * TICKS_PER_UNIT),
                    offsets[e3],
                    "and e3 to the front, so the gap it keeps is e1's outstanding, no more",
                )
            },
        )
    }

    /**
     * The clamp's floor (`max(0, outstanding)`, #3721): a waker whose edge reads a negative
     * outstanding is clamped to the front, never below it. [EdgeSummary] allows one transiently:
     * a spend can merge before the delegation it spent. A spend carries a witness of its spender's
     * own delegations and of one transfer's donor, so the shape needs two hops (the `witness` KDoc
     * on [EntitlementLedger]).
     *
     * B runs e1 to 20 and e2 to 60 and spends both. Then B delegates 10 more down e1 and hands them
     * on at g1, to C and from C to D, and D spends them. A merges B's ledger as it stood before that
     * delegation, and then D's spend alone, so A reads e1 at 20, outstanding −10. A has never seen
     * either child, so both are joiners and the front is e2, the furthest ahead, at 60. e1 lands
     * there: 40 units past its raw 20. Without the floor it would land 10 below the front, 30 past.
     *
     * Both demands are competing but unservable, so the round grants nothing and keeps only what it
     * observed: the clamps, read straight from the memory.
     */
    @Test
    fun aWakerWhoseEdgeReadsANegativeOutstandingIsClampedToTheFrontNotBelowIt() {
        val a = ReplicaId("a")
        val b = ReplicaId("b")
        val c = ReplicaId("c")
        val d = ReplicaId("d")
        val onB = flatLedger(mapOf(a to 100L, b to 100L))
        FairShareAllocator(b, policy).schedule(root, onB, earmarked = 0L, demandOf(e1 to Demand(20L, 10L), e2 to Demand(60L, 10L)))
        onB.apply(onB.current.spend(b, g1, 20L))
        onB.apply(onB.current.spend(b, GroupId("g2"), 60L))
        val beforeTheDelegation = onB.current
        onB.apply(onB.current.delegate(b, e1, 10L))
        onB.apply(onB.current.transfer(g1, b, c, 10L))
        onB.apply(onB.current.transfer(g1, c, d, 10L))
        val theSpend = assertNotNull(onB.current.spend(d, g1, 10L), "D spends the 10 B delegated")
        val onA = InMemoryLedgerCell(beforeTheDelegation)
        onA.merge(theSpend.delta)
        assertAll(
            { assertEquals(20L, issued(onA, e1), "the rig: A has not seen the delegation") },
            { assertEquals(-10L, assertNotNull(onA.current.edge(e1)).outstanding, "the rig: but has seen its spend, so e1 reads −10") },
            { assertEquals(60L, issued(onA, e2), "the rig: e2, the front, at 60") },
            { assertEquals(0L, assertNotNull(onA.current.edge(e2)).outstanding, "the rig: holding nothing, so it is not moved past itself") },
        )

        val allocator = FairShareAllocator(a, policy)
        val outcome = allocator.schedule(root, onA, earmarked = 0L, demandOf(e1 to competingUnservable, e2 to competingUnservable))
        assertAll(
            { assertEquals(AllocationOutcome.NothingToDelegate, outcome, "the rig: the round grants nothing") },
            { assertEquals(Int128.of(40L * TICKS_PER_UNIT), allocator.memory.wakeOffsets[e1], "e1 is clamped to the front, 40 past its raw 20, not 30") },
            { assertNull(allocator.memory.wakeOffsets[e2], "and e2, the front, holding nothing, stays where it is") },
        )
    }

    /**
     * A returner counts in the front a waker is clamped to at where it lands (#3446), and a second
     * wake keeps the first one's clamp (#1714). e1 runs alone to 200 and is seen leaving at the
     * grant that filled it; e2 then wakes beside e1 returning and e3 starved at 0, so its front is
     * mean(e1 at 200, e3 at 0) = 100, and it runs 20. Left out of the front, e1 would leave e2
     * clamped to e3 alone, at 0.
     *
     * e2 is then seen leaving, so its second wake is a return by its parent's clock, which nobody
     * moved: its shift is zero and it keeps the clamp of 100 it carries, at 120. Before #3376 that
     * second wake was a clamp into a front of e3 alone, and a `max` join with the stored offset kept
     * it at 120; that join is gone, since a clamped child's next wake is always a return (#3416
     * review). A return that replaced the offset instead of adding to it would give 20.
     */
    @Test
    fun aReturnerCountsWhereItLandsAndKeepsItsEarlierClamp() {
        // 220 = e1's 200 plus e2's 20: holdings run out from the second round on, so no later grant
        // can move a raw virtual service and mask the clamp.
        val cell = flatLedger(mapOf(self to 220L))
        val allocator = FairShareAllocator(self, policy)
        val starved = newborn(cell, "e3")

        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to Demand(200L, 10L), starved to competingUnservable))
        assertAll(
            { assertEquals(200L, issued(cell, e1), "e1 runs alone to its target") },
            // The grant that filled e1 is its leaving, so it asks again below as a returner, and a
            // returner counts in the front e2 wakes into at where it lands (#3446).
            { assertEquals(false, allocator.lastObservedDemanding(e1), "the rig: e1 was seen leaving") },
        )

        // e2 wakes into a front of mean(e1 landed at 200, e3 at 0) = 100, then runs its 20.
        allocator.schedule(
            root,
            cell,
            earmarked = 0L,
            demandOf(e1 to competingUnservable, e2 to Demand(20L, 10L), starved to competingUnservable),
        )
        assertAll(
            { assertEquals(20L, issued(cell, e2), "the waker takes the last 20") },
            { assertEquals(Rational.of(120L), allocator.effectiveVirtualService(cell, e2), "clamped by 100, then ran 20") },
        )

        // e2 sleeps, seen leaving; then e1 stops too, and e2 returns by a clock nobody moved.
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to competingUnservable, starved to competingUnservable))
        assertTrue(e2 in allocator.memory.departures, "the rig: e2 was seen leaving, so it comes back as a returner")
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e2 to hungry, starved to competingUnservable))

        assertEquals(
            Rational.of(120L),
            allocator.effectiveVirtualService(cell, e2),
            "a return adds its shift to the clamp e2 carries (replacing it gives 20)",
        )
    }

    /**
     * A newborn joining beside a returner is seated against a front that counts the returner where
     * it lands, after its shift, not where it stood while away (#3446). e2 leaves at 10 and e1 runs
     * on alone to 30, so the clock runs 20 and e2 comes back level with e1, at 30. Counted where it
     * stood, e2 would drag the front to 20 and the newborn would keep the 10 between.
     */
    @Test
    fun aNewbornBesideAReturnerIsSeatedAgainstWhereTheReturnerLands() {
        val cell = flatLedger(mapOf(self to 20L))
        val allocator = FairShareAllocator(self, policy)
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry, e2 to hungry))
        cell.apply(cell.current.mint(MintId("alone"), root, self, 20L))
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry))
        val e3 = newborn(cell, "e3")
        cell.apply(cell.current.mint(MintId("joined"), root, self, 10L))
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to competingUnservable, e2 to competingUnservable, e3 to hungry))
        assertAll(
            { assertEquals(30L to 10L, issued(cell, e1) to issued(cell, e2), "the rig: e1 ran on alone after e2 left") },
            { assertEquals(Rational.of(30L), allocator.effectiveVirtualService(cell, e2), "the rig: e2 came back moved by the clock's 20") },
            { assertEquals(Rational.of(40L), allocator.effectiveVirtualService(cell, e3), "e3 seated level with both, at 30, then ran its 10") },
        )
    }

    /**
     * A child first seen idle and unseated, which then wakes, is seated at the front and not also
     * clamped to it (#3454 review). An empty first round keeps its wake memory and drops its seats,
     * so e3 is seen idle in one empty call and wakes in a second, still with no gauge. Clamped
     * there, it carried an offset of the whole front into the call that seated it at the front, and
     * landed near twice the front. A child with no gauge is a newborn: its seat is what puts it at
     * the front.
     */
    @Test
    fun anUnseatedWakerIsSeatedAtTheFrontNotAlsoClamped() {
        val cell = flatLedger(mapOf(self to 200L))
        val allocator = FairShareAllocator(self, policy)
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry))
        val e3 = newborn(cell, "e3")
        // Two empty calls, the holdings spent: e3 seen idle, then waking, and seated by neither.
        val idle = allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to competingUnservable))
        val idleSeen = allocator.lastObservedDemanding(e3)
        val woke = allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to competingUnservable, e3 to hungry))
        val unseated = cell.current.gauge(e3)
        cell.apply(cell.current.mint(MintId("woke"), root, self, 10L))
        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to competingUnservable, e3 to hungry))
        assertAll(
            { assertEquals(200L, issued(cell, e1), "the rig: e1 ran alone to 200") },
            { assertEquals(listOf(AllocationOutcome.NothingToDelegate, AllocationOutcome.NothingToDelegate), listOf(idle, woke), "the rig: two empty calls") },
            { assertEquals(false, idleSeen, "the rig: e3 was seen idle") },
            { assertNull(unseated, "the rig: e3 woke with no gauge") },
            { assertEquals(10L, issued(cell, e3), "the rig: e3 took the 10") },
            { assertEquals(Rational.of(210L), allocator.effectiveVirtualService(cell, e3), "seated at the front, 200, then ran 10") },
        )
    }

    /**
     * A shift remainder is always on its edge's own grid, since a reweight is a new edge (#3416
     * review), so one at another denominator is a memory no allocator wrote, and the returner it
     * belongs to is refused at its pick rather than moved by a remainder dropped quietly. e2 returns
     * at once, its parent's clock still, carrying a remainder at denominator 2 on a weight of 1. The
     * control carries the same numerator at denominator 1 and returns.
     */
    @Test
    fun aShiftRemainderOffItsEdgesGridIsRefused() {
        fun returning(denominator: Long): AllocatorMemory = AllocatorMemory(
            wakeOffsets = emptyMap(),
            demandingObserved = mapOf(e2 to false),
            departures = mapOf(e2 to Int128.ZERO),
            shiftRemainders = mapOf(e2 to ShiftRemainder(Int128.of(1L), denominator)),
        )
        fun schedule(memory: AllocatorMemory) =
            FairShareAllocator(self, policy, memory).schedule(root, flatLedger(mapOf(self to 20L)), earmarked = 0L, demandOf(e1 to hungry, e2 to hungry))
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(2), schedule(returning(denominator = 1L)), "the control: on its grid, e2 returns") },
            {
                val refused = assertFailsWith<IllegalStateException> { schedule(returning(denominator = 2L)) }
                assertTrue("denominator 2" in (refused.message ?: ""), "the check names the denominator: $refused")
            },
        )
    }

    @Test
    fun earmarkedUnitsAreNeverDelegated() {
        val cell = flatLedger(mapOf(self to 100L))
        val allocator = FairShareAllocator(self, policy)

        val granted = allocator.schedule(root, cell, earmarked = 40L, demandOf(e1 to hungry, e2 to hungry))
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(6), granted, "60 delegable units at a quantum of 10") },
            { assertEquals(60L, issued(cell, e1) + issued(cell, e2), "the 40 earmarked units stay at the root") },
            { assertEquals(40L, cell.current.holdings(root, self), "…where the reservation can still spend them") },
        )
        assertEquals(AllocationOutcome.NothingToDelegate, allocator.schedule(root, cell, earmarked = 40L, demandOf(e1 to hungry)), "and a rerun finds nothing")
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
                // The first write is the seats with the first grant; inject before the second round's.
                if (base.writes == 1 && !injected) {
                    injected = true
                    base.apply(base.current.prepare(late))
                    base.apply(base.current.activate(late.id))
                }
                return base.mutateOrSkip(transform)
            }
        }
        val allocator = FairShareAllocator(self, policy)

        allocator.schedule(root, cell, earmarked = 0L, demandOf(e1 to hungry, e2 to hungry, late.id to hungry))
        assertAll(
            { assertTrue(injected, "the rig fired: the late edge arrived after the seat, before a pick") },
            { assertEquals(0L, issued(base, late.id), "it took no grant this round") },
            { assertNull(base.current.gauge(late.id), "and no gauge, so the next round can still seat it") },
            { assertEquals(100L, issued(base, e1) + issued(base, e2), "the seated edges took the whole supply") },
        )

        // The next call that grants seats it; `seat` refusing it would leave the gauge absent. More
        // supply first, since a call with nothing to grant writes nothing, a seat included.
        base.apply(base.current.mint(MintId("more"), root, self, 10L))
        allocator.schedule(root, base, earmarked = 0L, demandOf(late.id to hungry))
        assertNotNull(base.current.gauge(late.id), "seated on the following round")
    }

    /**
     * A sleeper credit past one lease is refused by both configs (§10.6). The credit a clamped
     * waker may keep is policy, and one lease, [LocalHeddle.MAX_RESERVATION], is its bound.
     */
    @Test
    fun aSleeperCreditPastOneLeaseIsRefused() {
        val max = LocalHeddle.MAX_RESERVATION
        assertAll(
            { assertEquals(max, PolicyConfig(quantum = 1L, sleeperCredit = max).sleeperCredit, "one lease is kept") },
            { assertEquals(max, LocalPolicy(defaultReservation = 1L, sleeperCredit = max).sleeperCredit, "by both configs") },
            { assertFailsWith<IllegalArgumentException> { PolicyConfig(quantum = 1L, sleeperCredit = max + 1L) } },
            { assertFailsWith<IllegalArgumentException> { LocalPolicy(defaultReservation = 1L, sleeperCredit = max + 1L) } },
        )
    }
}
