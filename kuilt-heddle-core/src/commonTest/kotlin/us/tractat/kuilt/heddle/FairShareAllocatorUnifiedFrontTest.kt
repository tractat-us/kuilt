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
        origin: Long = 0L,
    ): Run {
        val children = seats.keys.toList() + newborns
        var l = shapedLedger(root, children)
        l = l.piece(EntitlementLedger.of(gauges = seats.mapKeys { it.key.id }))
        if (origin > 0L) l = l.piece(EntitlementLedger.of(origins = mapOf(root to Int128.of(origin))))
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

    /**
     * S4b, flipped by origins (#3353, slice 7): a runner `b` at −100, a waker `a` at −100 and a
     * newborn `n`. `b` is a demanding non-joiner, so the front is its mean, −100. Positions now sit
     * below zero only relative to the parent's origin, here 100 over gauges at zero, and the seat is
     * absolute, so the newborn is seated at the origin plus the front: level with both, at −100.
     * Under the fold rebase the runner was below its own absolute zero and the seat could not follow
     * it there, so the newborn waited 100 behind them: 75/75/0.
     */
    @Test
    fun aNewbornBesideASubZeroRunnerAndAWakerStartsLevel() {
        val n = edgeOf(root, GroupId("p/n"))
        val run = schedule(
            seats = mapOf(b to gaugeAt(Int128.ZERO, folded = 0L), a to gaugeAt(Int128.ZERO, folded = 0L)),
            newborns = listOf(n),
            observed = mapOf(b to true, a to false),
            idle = emptySet(),
            supply = 150L,
            origin = 100L,
        )
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(150), run.outcome, "the rig: all 150 units went out") },
            { assertEquals(true, run.memory.demandingObserved[a.id], "the rig: a was observed waking") },
            { assertEquals(Int128.ZERO, run.memory.wakeOffsets[a.id] ?: Int128.ZERO, "a is clamped to the runner's −100, which moves it nothing") },
            { assertEquals(50, run.granted[a], "the waker comes level with the runner") },
            { assertEquals(50, run.granted[b], "and the runner keeps its share") },
            { assertEquals(50, run.granted[n], "and the newborn, seated level, takes its share too") },
        )
    }

    /**
     * S4, flipped by origins (#3353, slice 7): a runner `b` at −100 and a newborn `n`, no waker. The
     * newborn is seated at the runner, so the two split 150 evenly. Under the fold rebase the seat
     * could not go below absolute zero, so the newborn started 100 ahead and the split was 125/25.
     */
    @Test
    fun aNewbornBesideASubZeroRunnerStartsLevel() {
        val n = edgeOf(root, GroupId("p/n"))
        val run = schedule(
            seats = mapOf(b to gaugeAt(Int128.ZERO, folded = 0L)),
            newborns = listOf(n),
            observed = mapOf(b to true),
            idle = emptySet(),
            supply = 150L,
            origin = 100L,
        )
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(150), run.outcome, "the rig: all 150 units went out") },
            { assertEquals(75, run.granted[b], "the runner takes half") },
            { assertEquals(75, run.granted[n], "and so does the newborn") },
        )
    }
}
