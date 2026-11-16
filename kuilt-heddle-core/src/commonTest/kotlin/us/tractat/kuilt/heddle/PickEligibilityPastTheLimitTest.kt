package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [HeddlePolicy.pick] is public, so a caller can hand it positions no engine's step let through
 * (#3500 review). Its eligibility is a comparison, not a landing: it must order exactly, past the
 * position limit too. `a`, at weight `2²⁰`, stands `2³⁰` units ahead of `b`, at weight 1, near
 * `2¹⁰¹` units, so the front on `a`'s grid is past `2¹²⁵` ticks. `a` is ahead of the front, so it is
 * not eligible, though its deadline is the earlier: `b` is picked.
 */
class PickEligibilityPastTheLimitTest {

    private val root = GroupId("root")
    private val quantum = LocalHeddle.MAX_RESERVATION

    private fun edge(id: String, weight: Weight, units: Int128): PolicyEdge {
        val attachment = AttachmentId(id)
        val ticks = assertNotNull(us.tractat.kuilt.math.mulDiv(units, 16L * weight.numerator, weight.denominator, us.tractat.kuilt.math.Rounding.FLOOR))
        return PolicyEdge(
            AttachmentRecord(attachment, root, GroupId(id), weight),
            EdgeSummary(attachment, 0L, 0L, 0L),
            Demand(targetOutstanding = 4L * quantum, maximumUsefulGrant = quantum),
            Gauge(folded = 0L, ticksHigh = ticks.high, ticksLow = ticks.low),
            0L,
        )
    }

    @Test
    fun aLaneAheadOfTheFrontIsNotEligibleWhereverTheFrontStands() {
        val base = Int128.fromTwosComplement(1L shl 37, 0L) // 2^101 units
        val a = edge("a", Weight.of(1L shl 20), base + Int128.of(1L shl 30))
        val b = edge("b", Weight.ONE, base)
        val front = assertNotNull(HeddlePolicy.frontTime(listOf(a, b)))
        val grant = HeddlePolicy.pick(listOf(a, b), PolicyConfig(quantum = quantum), localHoldings = quantum)
        assertAll(
            { assertEquals(Ticks.PastLimit, front.ticksForWide(a.record.weight), "the rig: the front on a's grid is past the limit") },
            { assertEquals(b.record.id, assertNotNull(grant).attachment, "b is picked: a stands ahead of the front") },
        )
    }
}
