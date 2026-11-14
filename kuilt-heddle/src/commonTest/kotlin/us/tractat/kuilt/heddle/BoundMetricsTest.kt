package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BoundMetrics.observedDeviation] at lifetime totals large enough that the old arithmetic
 * (`share × totalCommitted` as a `Long` rational) overflowed and threw on every later call (#3319).
 *
 * Each row is #3319's measured first-throw total, split exactly 50/50 between two demanding children
 * of `root`, so the deviation is `|share₁ − ½| · T` rounded up. The expected values were computed
 * outside Kotlin with exact rationals (Python's `fractions.Fraction`), not by the formula under test.
 */
class BoundMetricsTest {

    private val root = GroupId("root")
    private val e1 = AttachmentId("root->g1")
    private val e2 = AttachmentId("root->g2")
    private val alice = ReplicaId("alice")
    private val config = HeddleConfig(policy = PolicyConfig(quantum = 1L), maxHoldingsPerPeer = Long.MAX_VALUE / 4)

    @Test
    fun thousandthAgainstTheRestAtTwoToThe51() {
        assertEquals(1_123_648_107_028_939L, deviation(Weight.of(1L, 1000L), Weight.of(999L, 1000L), 1L shl 51))
    }

    @Test
    fun coprimeDenominatorsAtTwoToThe56() {
        assertEquals(1_549_625_678_235_010L, deviation(Weight.of(1L, 97L), Weight.of(1L, 89L), 1L shl 56))
    }

    @Test
    fun mixedFractionsAtTwoToThe60() {
        assertEquals(19_877_956_975_980_121L, deviation(Weight.of(3L, 7L), Weight.of(2L, 5L), 1L shl 60))
    }

    @Test
    fun threeToOneAtTwoToThe62() {
        assertEquals(1L shl 60, deviation(Weight.of(3L), Weight.ONE, 1L shl 62))
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

    private fun deviation(w1: Weight, w2: Weight, total: Long): Long =
        deviation(w1, w2, committed1 = total / 2, committed2 = total - total / 2)

    /** The ledger is built only through public mutators: mint at the root, prepare, activate, seat, delegate. */
    private fun deviation(w1: Weight, w2: Weight, committed1: Long, committed2: Long): Long {
        var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to committed1 + committed2), nonce = "3319")
        ledger = ledger.piece(requireNotNull(ledger.prepare(AttachmentRecord(e1, root, GroupId("g1"), w1))))
        ledger = ledger.piece(requireNotNull(ledger.prepare(AttachmentRecord(e2, root, GroupId("g2"), w2))))
        ledger = ledger.piece(requireNotNull(ledger.activate(e1)))
        ledger = ledger.piece(requireNotNull(ledger.activate(e2)))
        ledger = ledger.piece(requireNotNull(ledger.seat(e1, Rational.ZERO)))
        ledger = ledger.piece(requireNotNull(ledger.seat(e2, Rational.ZERO)))
        if (committed1 > 0L) ledger = ledger.piece(requireNotNull(ledger.delegate(alice, e1, committed1)))
        if (committed2 > 0L) ledger = ledger.piece(requireNotNull(ledger.delegate(alice, e2, committed2)))
        // The rig: both edges really carry the committed service the row names.
        assertEquals(listOf(committed1, committed2), listOf(e1, e2).map { ledger.edge(it)?.committedService })
        return BoundMetrics.at(ledger, root, setOf(alice), setOf(e1, e2), config).observedDeviation
    }
}
