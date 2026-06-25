package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarpStatsTest {

    private val src1 = OpId("source.alpha")
    private val src2 = OpId("source.beta")
    private val src3 = OpId("source.gamma")

    // ── Unseen source ────────────────────────────────────────────────────────

    @Test
    fun unseenSourceReturnsZeroCardinality() {
        val stats = WarpStats.empty()
        assertAll(
            { assertEquals(0L, stats.estimatedCardinality(src1)) },
            { assertEquals(0L, stats.estimatedCardinality(src2)) },
        )
    }

    // ── Commutativity ────────────────────────────────────────────────────────

    @Test
    fun commutativityDisjointObservations() {
        val a = observeAll(WarpStats.empty(), src1, "a1", "a2", "a3")
        val b = observeAll(WarpStats.empty(), src1, "b1", "b2", "b3")

        val ab = a.piece(b)
        val ba = b.piece(a)

        assertEquals(ab, ba, "piece must be commutative")
    }

    @Test
    fun commutativityMultipleSources() {
        val a = observeAll(WarpStats.empty(), src1, "x1", "x2").let {
            observeAll(it, src2, "y1")
        }
        val b = observeAll(WarpStats.empty(), src2, "y2", "y3").let {
            observeAll(it, src3, "z1")
        }

        assertEquals(a.piece(b), b.piece(a), "commutativity holds across multiple sources")
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun idempotencyMergingTwiceEqualsOnce() {
        val a = observeAll(WarpStats.empty(), src1, "e1", "e2", "e3")
        val b = observeAll(WarpStats.empty(), src1, "e2", "e3", "e4")

        val merged = a.piece(b)
        val mergedTwice = merged.piece(b)

        assertEquals(merged, mergedTwice, "piece must be idempotent")
    }

    @Test
    fun selfPieceIsIdentity() {
        val stats = observeAll(WarpStats.empty(), src1, "hello", "world")
        assertEquals(stats, stats.piece(stats), "a.piece(a) == a")
    }

    // ── Associativity ────────────────────────────────────────────────────────

    @Test
    fun associativityThreeReplicas() {
        val a = observeAll(WarpStats.empty(), src1, "a1", "a2")
        val b = observeAll(WarpStats.empty(), src1, "b1").let { observeAll(it, src2, "b2") }
        val c = observeAll(WarpStats.empty(), src2, "c1", "c2")

        val leftAssoc = a.piece(b).piece(c)
        val rightAssoc = a.piece(b.piece(c))

        assertEquals(leftAssoc, rightAssoc, "(a.piece(b)).piece(c) == a.piece(b.piece(c))")
    }

    // ── Cardinality estimate accuracy ────────────────────────────────────────

    @Test
    fun cardinalityEstimateWithinErrorBand() {
        // 500 distinct deterministic elements; HLL precision=14 gives ~0.81% theoretical error.
        // Tolerance: ±5% (much wider than theory, to guarantee the test is never flaky).
        val n = 500
        val elements = (0 until n).map { "distinct_element_$it" }

        var stats = WarpStats.empty()
        for (element in elements) {
            stats = stats.piece(stats.observe(src1, element))
        }

        val estimate = stats.estimatedCardinality(src1)
        val tolerance = (n * 0.05).toLong()

        assertTrue(
            estimate in (n - tolerance)..(n + tolerance),
            "estimatedCardinality($n distinct elements) = $estimate, expected $n ± $tolerance",
        )
    }

    @Test
    fun duplicateElementsDoNotInflateEstimate() {
        // Observing the same element many times should not grow the cardinality.
        var stats = WarpStats.empty()
        repeat(100) { stats = stats.piece(stats.observe(src1, "same_element")) }

        assertTrue(
            stats.estimatedCardinality(src1) <= 5L,
            "repeated same element must not inflate estimate, got ${stats.estimatedCardinality(src1)}",
        )
    }

    // ── Per-source isolation ─────────────────────────────────────────────────

    @Test
    fun multipleSourcesTrackedIndependently() {
        var stats = WarpStats.empty()
        stats = stats.piece(stats.observe(src1, "only_in_src1"))
        stats = stats.piece(stats.observe(src2, "only_in_src2"))

        assertAll(
            { assertTrue(stats.estimatedCardinality(src1) >= 1L, "src1 must have non-zero estimate") },
            { assertTrue(stats.estimatedCardinality(src2) >= 1L, "src2 must have non-zero estimate") },
            { assertEquals(0L, stats.estimatedCardinality(src3), "unseen src3 must stay zero") },
        )
    }

    // ── Delta convergence ────────────────────────────────────────────────────

    @Test
    fun deltasConvergeToSameStateAsFullObservation() {
        // Two replicas observe the same elements independently, then merge.
        // The merged result must equal a single replica that observed everything.
        val elements = listOf("p", "q", "r", "s", "t")

        val full = observeAll(WarpStats.empty(), src1, *elements.toTypedArray())

        val replicaA = observeAll(WarpStats.empty(), src1, "p", "q", "r")
        val replicaB = observeAll(WarpStats.empty(), src1, "r", "s", "t")
        val merged = replicaA.piece(replicaB)

        assertEquals(
            full.estimatedCardinality(src1),
            merged.estimatedCardinality(src1),
            "merge of disjoint replicas must match full observation",
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun observeAll(stats: WarpStats, source: OpId, vararg elements: String): WarpStats =
        elements.fold(stats) { acc, e -> acc.piece(acc.observe(source, e)) }
}
