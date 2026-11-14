package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.math.plusOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BoundMetrics.observedDeviation] at lifetime totals large enough that the old arithmetic
 * (`share × totalCommitted` as a `Long` rational) overflowed and threw on every later call (#3319).
 *
 * The first four rows are #3319's measured first-throw totals, split exactly 50/50 between two
 * demanding children of `root`, so the deviation is `|share₁ − ½| · T` rounded up. The expected values
 * were computed outside Kotlin with exact rationals (Python's `fractions.Fraction`), not by the
 * formula under test.
 *
 * The 128-bit order and gcd-first sum of #3313 already moved two of those thresholds: an exact
 * 50/50 split no longer throws at `2⁵¹` or `2⁵⁶`, but the same weights still threw at `2⁵⁴` and `2⁵⁷`,
 * where `share × T` itself leaves `Long`. Those two rows pin the overflow that was left. Both totals
 * are reachable: the pick's own ceiling (`committed / weight` in the gauge `delegate` writes) holds
 * at each of them, which the rig proves by delegating that service.
 *
 * Two rows carry a weight near `Long.MAX_VALUE`, where forming the share itself used to throw (#3347).
 * Their expected values come from `fractions` too.
 */
class BoundMetricsTest {

    @Test
    fun thousandthAgainstTheRestAtTwoToThe51() {
        assertEquals(1_123_648_107_028_939L, deviation(Weight.of(1L, 1000L), Weight.of(999L, 1000L), 1L shl 51))
    }

    @Test
    fun thousandthAgainstTheRestAtTwoToThe54() {
        assertEquals(8_989_184_856_231_511L, deviation(Weight.of(1L, 1000L), Weight.of(999L, 1000L), 1L shl 54))
    }

    @Test
    fun coprimeDenominatorsAtTwoToThe56() {
        assertEquals(1_549_625_678_235_010L, deviation(Weight.of(1L, 97L), Weight.of(1L, 89L), 1L shl 56))
    }

    @Test
    fun coprimeDenominatorsAtTwoToThe57() {
        assertEquals(3_099_251_356_470_019L, deviation(Weight.of(1L, 97L), Weight.of(1L, 89L), 1L shl 57))
    }

    @Test
    fun mixedFractionsAtTwoToThe60() {
        assertEquals(19_877_956_975_980_121L, deviation(Weight.of(3L, 7L), Weight.of(2L, 5L), 1L shl 60))
    }

    @Test
    fun threeToOneAtTwoToThe62() {
        assertEquals(1L shl 60, deviation(Weight.of(3L), Weight.ONE, 1L shl 62))
    }

    /**
     * A weight near `Long.MAX_VALUE` (#3347). The share `w₁ / W` is `(2⁶³−3)/2 ÷ (2⁶³−1)/2`, whose
     * straight-across product `(2⁶³−3) · 2` leaves `Long` although the share itself,
     * `(2⁶³−3)/(2⁶³−1)`, fits; `Rational.div` threw there on the first call, at any total.
     */
    @Test
    fun aWeightNearLongMaxAgainstOneAtTwoToThe62() {
        val heavy = Weight.of(Long.MAX_VALUE - 2L, 2L)
        assertEquals(2_305_843_009_213_693_951L, deviation(heavy, Weight.ONE, committed1 = 1L shl 61, committed2 = 1L shl 61))
    }

    /** The same share with three children, so the heavy child's quotient is not the only one formed. */
    @Test
    fun threeChildrenWithOneWeightNearLongMax() {
        val children = listOf(
            Child(Weight.of(Long.MAX_VALUE - 4L, 2L), 1L shl 60),
            Child(Weight.ONE, 1L shl 61),
            Child(Weight.ONE, 0L),
        )
        assertEquals(2_305_843_009_213_693_952L, observedDeviationOf(children))
    }

    /** On the weight ratio exactly, at the largest total two children can hold, the deviation is zero. */
    @Test
    fun onRatioAtTheLargestTotalIsZero() {
        val third = Long.MAX_VALUE / 3 // 3 · third ≤ Long.MAX_VALUE, so the root can fund both edges
        assertEquals(0L, deviation(Weight.of(2L), Weight.ONE, committed1 = 2 * third, committed2 = third))
    }

    /** Rounded up, never down: `1/3 · 1 − 0` is a third of a unit, which owes a whole one. */
    @Test
    fun aFractionalGapRoundsUp() {
        assertEquals(1L, deviation(Weight.ONE, Weight.of(2L), committed1 = 0L, committed2 = 1L))
    }

    /**
     * An over-served child is as far off its share as an under-served one. With two children the
     * gaps are equal and opposite, so only three can tell a magnitude from a signed gap: here they
     * are `+1, +1, −2`.
     */
    @Test
    fun anOverServedChildCountsByMagnitude() {
        val equal = listOf(0L, 0L, 3L).map { Child(Weight.ONE, it) }
        assertEquals(2L, observedDeviationOf(equal))
    }

    private fun deviation(w1: Weight, w2: Weight, total: Long): Long =
        deviation(w1, w2, committed1 = total / 2, committed2 = total - total / 2)

    private fun deviation(w1: Weight, w2: Weight, committed1: Long, committed2: Long): Long =
        observedDeviationOf(listOf(Child(w1, committed1), Child(w2, committed2)))
}

/** One child of `root` in an [observedDeviationOf] rig: its edge weight, committed service, and whether it advertises demand. */
internal data class Child(val weight: Weight, val committed: Long, val demanding: Boolean = true)

/**
 * [BoundMetrics.observedDeviation] at `root` over [children], with the ledger built only through
 * public mutators: mint at the root, then prepare, activate, seat and delegate each edge. Shared with
 * the JVM oracle, so both check the same rig.
 */
internal fun observedDeviationOf(children: List<Child>): Long {
    val root = GroupId("root")
    val alice = ReplicaId("alice")
    val edges = children.indices.map { AttachmentId("root->g$it") }
    val mint = children.fold(0L) { acc, c -> requireNotNull(acc.plusOrNull(c.committed)) { "rig mints past Long" } }
    var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to mint), nonce = "3319")
    children.forEachIndexed { i, c ->
        ledger = ledger.piece(requireNotNull(ledger.prepare(AttachmentRecord(edges[i], root, GroupId("g$i"), c.weight))))
        ledger = ledger.piece(requireNotNull(ledger.activate(edges[i])))
        ledger = ledger.piece(requireNotNull(ledger.seat(edges[i], Rational.ZERO)))
        if (c.committed > 0L) ledger = ledger.piece(requireNotNull(ledger.delegate(alice, edges[i], c.committed)))
    }
    // The rig: every edge really carries the committed service its child names.
    assertEquals(children.map { it.committed }, edges.map { ledger.edge(it)?.committedService })
    val demanding = edges.filterIndexed { i, _ -> children[i].demanding }.toSet()
    val config = HeddleConfig(policy = PolicyConfig(quantum = 1L), maxHoldingsPerPeer = Long.MAX_VALUE / 4)
    return BoundMetrics.at(ledger, root, setOf(alice), demanding, config).observedDeviation
}
