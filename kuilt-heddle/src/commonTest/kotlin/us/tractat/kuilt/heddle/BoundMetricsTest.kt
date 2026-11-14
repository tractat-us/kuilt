package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.math.plusOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [BoundMetrics.observedDeviation] at lifetime totals large enough that the old arithmetic
 * (`share × totalCommitted` as a `Long` rational) overflowed and threw on every later call (#3319).
 *
 * **What the ledger can hold.** An edge that carries service has `16·n` and `16·c` inside `Long`
 * for its weight's numerator `n` and its committed total `c` (`ticksAt`'s scale in `seat` and
 * `delegate`, and `ticksOf`), and `(c / gcd(c, n))·d` inside `Long` for its weight's denominator `d`
 * (`ticksToVirtual`, the gauge `delegate` writes). An edge nobody has delegated to need never be
 * seated, so it takes any weight `prepare` accepts. Every row keeps `n ≤ 2⁵⁸` and `c·d ≤ 2⁵⁸` on an
 * edge that carries service, a margin under all three, so no row sits on a ceiling it is not testing.
 *
 * Every row but the last three reds on the old arithmetic with an `ArithmeticException` from
 * `Rational.times` inside `observedDeviation`. The expected values were computed outside Kotlin
 * with exact rationals (Python's `fractions.Fraction`), not by the formula under test.
 */
class BoundMetricsTest {

    /** A `31 : 1` split: the share `31/32` times a total of `2⁵⁹ − 1`, coprime to 32, passes `2⁶³`. */
    @Test
    fun skewedWeightsAtTheLargestTotal() {
        assertEquals(
            270_215_977_642_229_760L,
            deviation(Weight.of(31L), Weight.ONE, committed1 = 1L shl 58, committed2 = (1L shl 58) - 1L),
        )
    }

    /**
     * Fractional weights with coprime denominators: `64/3 : 1/5` is a share of `320/323`, each child
     * committed up to `c·d ≤ 2⁵⁸`.
     */
    @Test
    fun mixedFractionsWithCoprimeDenominators() {
        assertEquals(
            56_218_308_661_169_783L,
            deviation(Weight.of(64L, 3L), Weight.of(1L, 5L), committed1 = (1L shl 58) / 3L, committed2 = (1L shl 58) / 5L),
        )
    }

    /**
     * A weight near `Long.MAX_VALUE` (#3347). No edge can carry service at that weight, but a
     * demanding child that has not yet been served can hold it: `prepare` and `activate` accept it
     * and nothing seats it. Its share `(2⁶³−3)/(2⁶³−1)` times the other child's service passes `2⁶³`.
     */
    @Test
    fun anUnservedWeightNearLongMaxAgainstOne() {
        val heavy = Weight.of(Long.MAX_VALUE - 2L, 2L)
        assertEquals(1L shl 58, deviation(heavy, Weight.ONE, committed1 = 0L, committed2 = 1L shl 58))
    }

    /** The same with three children, so the unserved child's gap is not the only one formed. */
    @Test
    fun threeChildrenWithAnUnservedWeightNearLongMax() {
        val children = listOf(
            Child(Weight.of(Long.MAX_VALUE - 4L, 2L), 0L),
            Child(Weight.ONE, 1L shl 58),
            Child(Weight.ONE, 1L shl 57),
        )
        assertEquals((1L shl 58) + (1L shl 57), observedDeviationOf(children))
    }

    /**
     * The residual overflow, documented and pinned. Both weights can carry service: `2⁵⁷/3` and
     * `1/192`. Over the common denominator 192 their numerators sum to `2⁶³ + 1`, which leaves `Long`,
     * yet the reduced weight sum `W = ((2⁶³+1)/3) / 64` fits. The share `2⁵⁷/3 ÷ W = 2⁶³/(2⁶³+1)` is
     * already reduced and fits nowhere, so the metric throws. Returning from [metricsRigOf] at all
     * means `prepare`, `activate`, `seat` and `delegate` accepted both weights.
     */
    @Test
    fun aReducedShareThatDoesNotFitThrows() {
        val children = listOf(
            Child(Weight.of(1L shl 57, 3L), 1L),
            Child(Weight.of(1L, 192L), 1L),
        )
        val metric = metricsRigOf(children)
        assertFailsWith<ArithmeticException> { metric() }
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

    private fun deviation(w1: Weight, w2: Weight, committed1: Long, committed2: Long): Long =
        observedDeviationOf(listOf(Child(w1, committed1), Child(w2, committed2)))
}

/** One child of `root` in an [observedDeviationOf] rig: its edge weight, committed service, and whether it advertises demand. */
internal data class Child(val weight: Weight, val committed: Long, val demanding: Boolean = true)

/**
 * [BoundMetrics.observedDeviation] at `root` over [children], with the ledger built only through
 * public mutators: mint at the root, then prepare and activate each edge, and seat and delegate the
 * ones that carry service. Shared with the JVM oracle, so both check the same rig.
 */
internal fun observedDeviationOf(children: List<Child>): Long = metricsRigOf(children)()

/**
 * Builds [observedDeviationOf]'s rig and asserts it, returning the metric call unrun so a test can
 * tell setup from the call. An edge with no committed service is left unseated, as one the scheduler
 * has not yet picked is, so a row can give it a weight no edge could carry service at.
 */
internal fun metricsRigOf(children: List<Child>): () -> Long {
    val root = GroupId("root")
    val alice = ReplicaId("alice")
    val edges = children.indices.map { AttachmentId("root->g$it") }
    val mint = children.fold(0L) { acc, c -> requireNotNull(acc.plusOrNull(c.committed)) { "rig mints past Long" } }
    var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to mint), nonce = "3319")
    children.forEachIndexed { i, c ->
        ledger = ledger.piece(requireNotNull(ledger.prepare(AttachmentRecord(edges[i], root, GroupId("g$i"), c.weight))))
        ledger = ledger.piece(requireNotNull(ledger.activate(edges[i])))
        if (c.committed > 0L) {
            ledger = ledger.piece(requireNotNull(ledger.seat(edges[i], Rational.ZERO)))
            ledger = ledger.piece(requireNotNull(ledger.delegate(alice, edges[i], c.committed)))
        }
    }
    // The rig: every edge really carries the committed service its child names.
    assertEquals(children.map { it.committed }, edges.map { ledger.edge(it)?.committedService })
    val demanding = edges.filterIndexed { i, _ -> children[i].demanding }.toSet()
    val config = HeddleConfig(policy = PolicyConfig(quantum = 1L), maxHoldingsPerPeer = Long.MAX_VALUE / 4)
    return { BoundMetrics.at(ledger, root, setOf(alice), demanding, config).observedDeviation }
}
