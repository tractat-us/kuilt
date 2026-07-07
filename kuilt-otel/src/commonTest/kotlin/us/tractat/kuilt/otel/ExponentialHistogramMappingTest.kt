package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExponentialHistogramMappingTest {

    private val replica = ReplicaId("A")
    private val key = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)

    private fun sketchOf(scale: Int, values: List<Double>): DDSketch {
        var s = DDSketch.empty(relativeAccuracy = alphaForOtlpScale(scale))
        for (v in values) s = s.piece(s.add(replica, v).delta)
        return s
    }

    private fun DDSketch.point() = toExponentialHistogramPoint(key, startEpochNanos = 0L, timeEpochNanos = 5L)

    // ── α ↔ scale derivation ──────────────────────────────────────────────────

    @Test
    fun alphaForScaleMatchesAlgebraicGoldens() {
        // scale 0: γ = 2^(2^0) = 2 → α = (2−1)/(2+1) = 1/3.
        // scale 1: γ = 2^(2^−1) = √2 → α = (√2−1)/(√2+1) = 3 − 2√2.
        assertAll(
            { assertEquals(1.0 / 3.0, alphaForOtlpScale(0), absoluteTolerance = 1e-15) },
            { assertEquals(3.0 - 2.0 * sqrt(2.0), alphaForOtlpScale(1), absoluteTolerance = 1e-15) },
            // scale 5 (the exporter default) is ~1.08% — the closest aligned α to 1%.
            { assertEquals(0.01083, alphaForOtlpScale(5), absoluteTolerance = 5e-6) },
        )
    }

    @Test
    fun scaleDerivationRoundTripsAcrossTheFullOtlpRange() {
        for (scale in MIN_OTLP_HISTOGRAM_SCALE..MAX_OTLP_HISTOGRAM_SCALE) {
            assertEquals(scale, otlpScaleFor(alphaForOtlpScale(scale)), "scale $scale must round-trip")
        }
    }

    @Test
    fun freeFormAlphaHasNoIntegerScaleAndIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { otlpScaleFor(0.01) } },
            { assertFailsWith<IllegalArgumentException> { otlpScaleFor(DDSketch.DEFAULT_RELATIVE_ACCURACY) } },
            { assertFailsWith<IllegalArgumentException> { otlpScaleFor(0.5) } },
        )
    }

    @Test
    fun scaleOutsideOtlpRangeIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { alphaForOtlpScale(MAX_OTLP_HISTOGRAM_SCALE + 1) } },
            { assertFailsWith<IllegalArgumentException> { alphaForOtlpScale(MIN_OTLP_HISTOGRAM_SCALE - 1) } },
        )
    }

    // ── Field translation ─────────────────────────────────────────────────────

    @Test
    fun sketchStateTranslatesToOtlpFields() {
        // scale 2 → γ = 2^0.25, α ≈ 8.64%: coarse buckets keep the point small.
        val sketch = sketchOf(2, listOf(1.0, 10.0, -4.0, 0.0, 1e-12))
        val p = sketch.point()
        assertAll(
            { assertEquals(2, p.scale) },
            { assertEquals(5L, p.count) },
            // The exact zero and the sub-threshold 1e-12 both land in the zero bucket.
            { assertEquals(2L, p.zeroCount) },
            { assertEquals(DDSketch.DEFAULT_MIN_INDEXED_VALUE, p.zeroThreshold) },
            { assertEquals(2L, p.positiveBucketCounts.sum()) },
            { assertEquals(1L, p.negativeBucketCounts.sum()) },
            // OTLP invariant: bucket counts plus zero_count equal count.
            { assertEquals(p.count, p.positiveBucketCounts.sum() + p.negativeBucketCounts.sum() + p.zeroCount) },
        )
    }

    @Test
    fun recordedValuesFallInsideTheirClaimedOtlpBucket() {
        // OTLP bucket j covers (base^j, base^(j+1)] — verify each recorded magnitude
        // lands in a bucket whose boundaries contain it, for both signs.
        // Values avoid exact powers of the base (bucket edges), where FP noise could
        // make this test's independently-computed index disagree with the sketch's.
        val scale = 3
        val values = listOf(0.02, 0.7, 1.0, 3.5, 42.0, 9_000.0, -0.55, -130.0)
        val sketch = sketchOf(scale, values)
        val p = sketch.point()
        val base = 2.0.pow(2.0.pow(-scale))
        for (v in values) {
            val magnitude = abs(v)
            val j = ceil(ln(magnitude) / ln(base)).toInt() - 1 // OTLP index = DDSketch index − 1
            val (offset, counts) = if (v > 0.0) {
                p.positiveOffset to p.positiveBucketCounts
            } else {
                p.negativeOffset to p.negativeBucketCounts
            }
            val count = counts[j - offset]
            assertAll(
                { assertTrue(count >= 1L, "bucket $j for $v must be populated") },
                { assertTrue(base.pow(j) < magnitude, "lower edge base^$j must be exclusive for $v") },
                { assertTrue(magnitude <= base.pow(j + 1) * (1 + 1e-12), "upper edge base^${j + 1} must contain $v") },
            )
        }
    }

    @Test
    fun emptySignHasNoBuckets() {
        val positivesOnly = sketchOf(2, listOf(1.0, 2.0)).point()
        assertAll(
            { assertTrue(positivesOnly.negativeBucketCounts.isEmpty()) },
            { assertEquals(0, positivesOnly.negativeOffset) },
            { assertTrue(positivesOnly.positiveBucketCounts.isNotEmpty()) },
        )
    }

    // ── Quantile fidelity ─────────────────────────────────────────────────────

    @Test
    fun reconstructedQuantilesMatchTheSketchAndTheExactValues() {
        // Log-spaced positives over ~3.5 decades, plus negatives and zeros: the OTLP
        // point must reproduce the sketch's quantiles exactly (same buckets, same
        // representatives) and therefore stay within α of the true quantiles.
        val scale = 5
        val alpha = alphaForOtlpScale(scale)
        val values = (1..400).map { 0.5 * 1.02.pow(it) } + (1..100).map { -it.toDouble() } + List(10) { 0.0 }
        val sketch = sketchOf(scale, values)
        val p = sketch.point()
        val sorted = values.sorted()
        for (q in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 1.0)) {
            val reconstructed = reconstructQuantile(p, q)
            val fromSketch = sketch.quantile(q)
            val exact = sorted[floor(q * (sorted.size - 1)).toInt()]
            assertAll(
                {
                    assertEquals(
                        fromSketch, reconstructed, absoluteTolerance = abs(fromSketch) * 1e-9 + 1e-12,
                        "q=$q: OTLP reconstruction must equal the sketch's own estimate",
                    )
                },
                {
                    assertTrue(
                        abs(reconstructed - exact) <= alpha * abs(exact) + 1e-12,
                        "q=$q: |$reconstructed − $exact| must be within α=$alpha relative",
                    )
                },
            )
        }
    }

    /**
     * Independently re-derive a quantile from the OTLP point alone: walk buckets in
     * ascending value order (negative descending index → zeros → positive ascending
     * index) and return the bucket representative `2·base^(j+1)/(base+1)` — the
     * midpoint (in relative-error terms) of OTLP bucket `(base^j, base^(j+1)]`.
     */
    private fun reconstructQuantile(p: MetricPoint.ExponentialHistogram, q: Double): Double {
        val base = 2.0.pow(2.0.pow(-p.scale))
        fun representative(j: Int): Double = 2.0 * base.pow(j + 1) / (base + 1.0)
        val rank = q * (p.count - 1)
        var cumulative = 0L
        for (i in p.negativeBucketCounts.indices.reversed()) {
            cumulative += p.negativeBucketCounts[i]
            if (cumulative > rank) return -representative(p.negativeOffset + i)
        }
        cumulative += p.zeroCount
        if (cumulative > rank) return 0.0
        for (i in p.positiveBucketCounts.indices) {
            cumulative += p.positiveBucketCounts[i]
            if (cumulative > rank) return representative(p.positiveOffset + i)
        }
        error("rank $rank not reached (count ${p.count})")
    }

    // ── Digest hash ───────────────────────────────────────────────────────────

    @Test
    fun valueHashTracksContentNotObservationTime() {
        val sketch = sketchOf(2, listOf(1.0, 2.0))
        val advanced = sketch.piece(sketch.add(replica, 3.0).delta)
        val at5 = sketch.toExponentialHistogramPoint(key, startEpochNanos = 0L, timeEpochNanos = 5L)
        val at9 = sketch.toExponentialHistogramPoint(key, startEpochNanos = 0L, timeEpochNanos = 9L)
        assertAll(
            { assertEquals(at5.valueHash(), at9.valueHash()) },
            { assertNotEquals(at5.valueHash(), advanced.point().valueHash()) },
        )
    }
}
