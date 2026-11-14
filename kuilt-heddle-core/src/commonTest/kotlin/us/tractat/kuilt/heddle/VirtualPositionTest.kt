package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid arithmetic at its edges (#3313): negative positions, an older peer's fat denominators,
 * and the rounding direction of each read. Each case is one the design review of the grid traced
 * by hand to a wrong answer or a throw.
 */
class VirtualPositionTest {

    /**
     * A position can be negative: an edge that has returned more than its base issuance, which
     * relocation credit allows, reads below its origin. The exact-rational policy picked such an
     * edge; the grid must too, rather than refuse the mean.
     */
    @Test
    fun aNegativePositionIsPickedLikeAnyOther() {
        val edge = PolicyEdge(
            record = AttachmentRecord(AttachmentId("r"), GroupId("p"), GroupId("r"), Weight.ONE),
            summary = EdgeSummary(AttachmentId("r"), issued = 5L, returned = 5L, spent = 0L),
            demand = Demand(targetOutstanding = 1L, maximumUsefulGrant = 1L),
            gauge = null,
            baseIssued = 0L,
        )
        assertAll(
            { assertEquals(Rational.of(-5L), HeddlePolicy.virtualService(edge)) },
            { assertEquals(Grant(AttachmentId("r"), 1L), HeddlePolicy.pick(listOf(edge), PolicyConfig(quantum = 1L), 1L)) },
            { assertEquals(Rational.of(-5L), HeddlePolicy.front(listOf(edge))) },
        )
    }

    /** `⌈−1/2⌉ = 0`: the remainder is taken against the floor quotient, not truncated toward zero. */
    @Test
    fun aNegativeSumRoundsUpToTheGrid() {
        val v = VirtualTime(Int128.of(-1L), 2L, 1L) // −1 tick over a total weight of 2
        assertAll(
            { assertEquals(0L, v.ticksFor(Weight.ONE)) },
            { assertEquals(-1L, VirtualTime(Int128.of(-3L), 2L, 1L).ticksFor(Weight.ONE)) },
            { assertEquals(Rational.of(-1L, 32L), v.toRational()) },
        )
    }

    /**
     * An older peer's floor can carry a denominator whose product with a weight's leaves `Long`
     * although the position it stands for is small. The old peer reads it; so must a new one.
     */
    @Test
    fun aFatDenominatorFromAnOlderPeerIsReadWhereverItsPositionFits() {
        val fat = Rational.of(1L, (1L shl 61) - 1L)
        val wide = Weight.of(1_073_741_825L, 1_073_741_823L)
        assertAll(
            // ⌈(1 / (2⁶¹ − 1)) · (3/7) · 16⌉ = 1, though (2⁶¹ − 1) · 7 leaves Long.
            { assertEquals(1L, ticksAt(fat, Weight.of(3L, 7L))) },
            // An on-grid value at a weight whose numerator and denominator multiply past 2⁵⁹.
            { assertEquals(1L, ticksAt(ticksToVirtual(1L, wide), wide)) },
            { assertEquals(0L, ticksAt(fat, Weight.of(3L, 7L), Rounding.FLOOR)) },
        )
    }

    /**
     * The public [HeddlePolicy.wakeOffset] takes rationals a caller may hand it off the grid. The
     * front rounds up and the waker's own position down, so the clamped waker can only land at or
     * past the front — never short of it, which would be the credit §10.5 forbids.
     */
    @Test
    fun aPublicWakeOffsetNeverLeavesTheWakerShortOfTheFront() {
        val front = Rational.ONE
        val vRaw = Rational.of(1L, 32L) // half a tick at weight 1
        val offset = HeddlePolicy.wakeOffset(front, vRaw, Weight.ONE, sleeperCredit = 0L)
        assertTrue(vRaw + offset >= front, "vRaw + offset = ${vRaw + offset} is short of the front $front")
    }

    /**
     * A checkpoint over a floor written off the grid rounds it up **once**, and every checkpoint
     * after it is exact: issuance moves a position by whole ticks. This is why rounding cannot
     * compound across checkpoints the way #1752 warned a fixed-point floor would.
     */
    @Test
    fun aCheckpointRoundsAnOffGridFloorOnceAndNeverAgain() {
        val offGrid = Gauge(Rational.of(1L, 3L), folded = 0L)
        val first = offGrid.grossVirtualServiceAt(2L, Weight.ONE) // what `delegate` writes at issuance 2
        val second = Gauge(first, folded = 2L).grossVirtualServiceAt(5L, Weight.ONE)
        assertAll(
            // The rig: the floor really is off the grid before the first checkpoint.
            { assertTrue(Rational.of(16L, 3L) != Rational.of(ticksAt(offGrid.floor, Weight.ONE))) },
            // ⌈16/3⌉ = 6 ticks, plus 2 units = 32 ticks: 38/16.
            { assertEquals(Rational.of(19L, 8L), first) },
            { assertEquals(first + Rational.of(3L), second, "the second checkpoint adds whole units exactly") },
            // Merge order cannot matter: the read is a function of the joined gauge, and the join is unchanged.
            {
                val stale = Gauge(Rational.of(1L, 3L), 0L)
                val fresh = Gauge(first, 2L)
                assertEquals(
                    stale.join(fresh).grossVirtualServiceAt(5L, Weight.ONE),
                    fresh.join(stale).grossVirtualServiceAt(5L, Weight.ONE),
                )
            },
        )
    }
}
