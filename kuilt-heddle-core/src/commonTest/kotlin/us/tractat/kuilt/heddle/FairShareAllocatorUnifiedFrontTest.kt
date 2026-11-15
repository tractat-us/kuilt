package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The UNIFIED joiner front (design §7.2, #3353): when no child other than the joiners is
 * demanding, the front is the furthest-ahead child at the parent, counting idle children, the
 * joiners themselves, and each newborn at its own origin. There is no competing set to come level
 * with, so everyone joining starts level with whoever has had the most.
 *
 * The arms are the probe scenarios whose split the old rule got wrong. Positions are on weight 1's
 * grid, 16 ticks to a unit, so `a@100` is 1,600 ticks; every grant is one unit.
 */
class FairShareAllocatorUnifiedFrontTest {

    private val root = GroupId("p")
    private val a = edgeOf(root, GroupId("p/a"))
    private val b = edgeOf(root, GroupId("p/b"))
    private val c = edgeOf(root, GroupId("p/c"))
    private val wants = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1L)

    /** A ledger over [watched] that counts the grants each child is delegated. */
    private class CountingCell(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = HashMap<AttachmentRecord, Int>()

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            watched.filter { current.baseIssuance(it.id) > before.baseIssuance(it.id) }.forEach { granted[it] = (granted[it] ?: 0) + 1 }
            return true
        }
    }

    private class Run(val outcome: AllocationOutcome, val granted: Map<AttachmentRecord, Int>, val memory: AllocatorMemory)

    /**
     * One [FairShareAllocator.schedule] over children seated at [seats] ticks, every one of them seen
     * idle before, so each that wants service now is a waker. [idle] still want nothing.
     */
    private fun wake(seats: Map<AttachmentRecord, Long>, idle: Set<AttachmentRecord>, supply: Long): Run =
        schedule(
            seats.mapValues { (_, ticks) -> gaugeAt(Int128.of(ticks), folded = 0L) },
            newborns = emptyList(),
            observed = seats.keys.associateWith { false },
            idle,
            supply,
        )

    /**
     * One [FairShareAllocator.schedule] over children carrying [seats] plus unseated [newborns],
     * each child in [observed] last seen demanding or not. [idle] want nothing.
     */
    private fun schedule(
        seats: Map<AttachmentRecord, Gauge>,
        newborns: List<AttachmentRecord>,
        observed: Map<AttachmentRecord, Boolean>,
        idle: Set<AttachmentRecord>,
        supply: Long,
    ): Run {
        val children = seats.keys.toList() + newborns
        var l = shapedLedger(root, children)
        l = l.piece(EntitlementLedger.of(gauges = seats.mapKeys { it.key.id }))
        l = l.piece(l.mint(MintId("m"), root, LOCAL, supply).delta)
        val cell = CountingCell(l, children)
        val allocator = FairShareAllocator(
            LOCAL,
            PolicyConfig(quantum = 1L),
            AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = observed.mapKeys { it.key.id }),
        )
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { id -> if (idle.any { it.id == id }) Demand.NONE else wants }
        return Run(outcome, cell.granted, allocator.memory)
    }

    /**
     * S3: `a` at 100 and `b` at 0 both wake beside an idle bystander `c` at 10. The old rule took the
     * front over the idle children alone, 10, so `b` came back 90 ahead of where `a` stood and took
     * 95 of 100. Under UNIFIED the front is `a`, the furthest ahead, so `b` is clamped level with it.
     */
    @Test
    fun twoWakersBesideAnIdleBystanderStartLevelWithTheFurthestAhead() {
        val run = wake(mapOf(a to 1_600L, b to 0L, c to 160L), idle = setOf(c), supply = 100L)
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(100), run.outcome, "the rig: all 100 units went out") },
            { assertEquals(Int128.of(1_600L), run.memory.wakeOffsets[b.id], "b is clamped to a, not to the bystander") },
            { assertEquals(50, run.granted[a], "a takes half") },
            { assertEquals(50, run.granted[b], "and so does b") },
            { assertEquals(null, run.granted[c], "the idle bystander takes none") },
        )
    }

    /** S3b: the same two wakers with no bystander split evenly under either rule; UNIFIED keeps that. */
    @Test
    fun twoWakersAloneStillSplitEvenly() {
        val run = wake(mapOf(a to 1_600L, b to 0L), idle = emptySet(), supply = 100L)
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(100), run.outcome, "the rig: all 100 units went out") },
            { assertEquals(50, run.granted[a], "a takes half") },
            { assertEquals(50, run.granted[b], "and so does b") },
        )
    }

}
