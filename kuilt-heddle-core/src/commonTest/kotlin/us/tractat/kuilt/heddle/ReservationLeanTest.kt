package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ReservationLeanRig.A
import us.tractat.kuilt.heddle.ReservationLeanRig.A1
import us.tractat.kuilt.heddle.ReservationLeanRig.A2
import us.tractat.kuilt.heddle.ReservationLeanRig.B
import us.tractat.kuilt.heddle.ReservationLeanRig.Estimate
import us.tractat.kuilt.heddle.ReservationLeanRig.Lane
import us.tractat.kuilt.heddle.ReservationLeanRig.ROOT
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The exact half of #3239's pin, in the default build on every target. "An open lease counts at its
 * reservation. A wrong estimate reorders picks and never changes a charge."
 *
 * The charge half: a leaf's spend is the costs its completions reported, whatever it reserved, on
 * three leases here and, inside [ReservationLeanRig.run], on every lease of every rig below.
 *
 * The rigs: one per estimate per level, ten in all, each pinned to its integer slot times exactly.
 * The rig draws nothing at random and walks no hash order, and the engine orders its outputs by id,
 * so every target must run the same trajectory to the tick. Each estimate's rig is the one of its
 * ensemble where that estimate moved share most (the honest arms reuse the quarter arm's rig), so a
 * change in how an estimate steers picks reaches these numbers. These values were pinned on the JVM
 * and match macosArm64.
 *
 * What a wrong estimate costs in share is a statistic over 48 and 54 rigs, and lives in
 * [ReservationLeanBenchmark], which runs on demand only (#3724).
 */
class ReservationLeanTest {

    @Test
    fun aLeafIsChargedTheCostItReportedWhateverItReserved() {
        val h = engineOver(ReservationLeanRig.oneLevel(), ROOT, supply = 1_000L)
        val edge = AttachmentId("root>a")
        fun spent(): Long = checkNotNull(h.snapshot().ledger.edge(edge)).spent
        val waiting = setOf(A)
        val over = h.lease(waiting, reservation = 32L)
        val overDone = h.complete(over.id, 8L, waiting)
        val afterOver = spent()
        val under = h.lease(waiting, reservation = 2L)
        val underDone = h.complete(under.id, 8L, waiting)
        val afterUnder = spent()
        val extended = h.lease(waiting, reservation = 1L)
        h.extend(extended.id, 7L, waiting)
        h.complete(extended.id, 8L, waiting)
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 8L, overrun = 0L, unrecorded = 0L), overDone, "four times the cost") },
            { assertEquals(8L, afterOver, "four times the cost, spent") },
            { assertEquals(LeaseOutcome.Done(charged = 8L, overrun = 6L, unrecorded = 0L), underDone, "a quarter of the cost") },
            { assertEquals(16L, afterUnder, "a quarter of the cost, spent") },
            { assertEquals(24L, spent(), "1, extended to the cost") },
        )
    }

    /** `a` against an always-backlogged `b`: slot times of `a` and `b`. */
    @Test
    fun oneLevelRigsRunTheSameSlotTimesOnEveryTarget() = assertAll(
        oneLevelIs(Estimate.HONEST, think = 3L, burst = 2, listOf(2664L, 5336L)),
        oneLevelIs(Estimate.QUARTER, think = 3L, burst = 2, listOf(3005L, 4995L)),
        oneLevelIs(Estimate.FOUR_TIMES, think = 6L, burst = 4, listOf(2640L, 5360L)),
        oneLevelIs(Estimate.FOUR_TIMES_ON_LAST, think = 3L, burst = 8, listOf(3562L, 4438L)),
        oneLevelIs(Estimate.ONE_THEN_EXTEND, think = 3L, burst = 2, listOf(3194L, 4806L)),
    )

    /** `root → {g, b}`, `g → {a1, a2}`: slot times of `a1`, `a2` and `b`. */
    @Test
    fun twoLevelRigsRunTheSameSlotTimesOnEveryTarget() = assertAll(
        twoLevelIs(Estimate.HONEST, TwoLevelRig(4L, 4L, 0L, 2), listOf(2000L, 1995L, 4005L)),
        twoLevelIs(Estimate.QUARTER, TwoLevelRig(4L, 4L, 0L, 2), listOf(2650L, 1351L, 3999L)),
        twoLevelIs(Estimate.FOUR_TIMES, TwoLevelRig(4L, 4L, 0L, 2), listOf(2182L, 1646L, 4172L)),
        twoLevelIs(Estimate.FOUR_TIMES_ON_LAST, TwoLevelRig(4L, 4L, 0L, 2), listOf(2666L, 1320L, 4014L)),
        twoLevelIs(Estimate.ONE_THEN_EXTEND, TwoLevelRig(12L, 7L, 5L, 2), listOf(2201L, 1104L, 4695L)),
    )

    private fun oneLevelIs(estimate: Estimate, think: Long, burst: Int, slotTimes: List<Long>): () -> Unit = {
        val run = ReservationLeanRig.run(ReservationLeanRig.oneLevel(), listOf(Lane(A, estimate, think, burst)))
        assertEquals(slotTimes, listOf(A, B).map(run::slotTime), "one level, $estimate, think $think, burst $burst")
    }

    /** `a1` bursts 4 at think [t1] and takes the estimate; `a2` is honest, bursting [b2] at think [t2] from [s2]. */
    private class TwoLevelRig(val t1: Long, val t2: Long, val s2: Long, val b2: Int)

    private fun twoLevelIs(estimate: Estimate, rig: TwoLevelRig, slotTimes: List<Long>): () -> Unit = {
        val run = ReservationLeanRig.run(
            ReservationLeanRig.twoLevel(),
            listOf(Lane(A1, estimate, rig.t1, burst = 4), Lane(A2, Estimate.HONEST, rig.t2, burst = rig.b2, start = rig.s2)),
        )
        assertEquals(
            slotTimes,
            listOf(A1, A2, B).map(run::slotTime),
            "two levels, $estimate, a1 think ${rig.t1}, a2 think ${rig.t2} start ${rig.s2} burst ${rig.b2}",
        )
    }
}
