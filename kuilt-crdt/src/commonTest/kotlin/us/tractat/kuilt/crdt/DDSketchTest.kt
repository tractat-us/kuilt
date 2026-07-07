package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DDSketchTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun relativeAccuracyMustBeInOpenUnitInterval() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { DDSketch.empty(relativeAccuracy = 0.0) } },
            { assertFailsWith<IllegalArgumentException> { DDSketch.empty(relativeAccuracy = 1.0) } },
            { assertFailsWith<IllegalArgumentException> { DDSketch.empty(relativeAccuracy = -0.1) } },
        )
    }

    @Test
    fun indexedRangeMustBePositiveAndOrdered() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { DDSketch.empty(minIndexedValue = 0.0) } },
            { assertFailsWith<IllegalArgumentException> { DDSketch.empty(minIndexedValue = -1.0) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    DDSketch.empty(minIndexedValue = 100.0, maxIndexedValue = 1.0)
                }
            },
        )
    }

    @Test
    fun emptySketchHasZeroCount() {
        val sketch = DDSketch.empty()
        assertAll(
            { assertEquals(0L, sketch.count) },
            { assertEquals(0L, sketch.zeroCount) },
            { assertEquals(0L, sketch.overflowCount) },
            { assertEquals(0, sketch.bucketCount) },
        )
    }

    @Test
    fun quantileOfEmptySketchThrows() {
        assertFailsWith<IllegalArgumentException> { DDSketch.empty().quantile(0.5) }
    }

    @Test
    fun quantileArgumentMustBeInUnitInterval() {
        val sketch = DDSketch.empty().adding(r1, 1.0)
        assertAll(
            { assertFailsWith<IllegalArgumentException> { sketch.quantile(-0.01) } },
            { assertFailsWith<IllegalArgumentException> { sketch.quantile(1.01) } },
        )
    }

    @Test
    fun nonFiniteValuesAreRejected() {
        val sketch = DDSketch.empty()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { sketch.add(r1, Double.NaN) } },
            { assertFailsWith<IllegalArgumentException> { sketch.add(r1, Double.POSITIVE_INFINITY) } },
            { assertFailsWith<IllegalArgumentException> { sketch.add(r1, Double.NEGATIVE_INFINITY) } },
        )
    }

    // ── Relative-error guarantee (the DDSketch contract) ─────────────────────

    @Test
    fun singleValueQuantileIsWithinRelativeAccuracy() {
        val alpha = 0.01
        val sketch = DDSketch.empty(relativeAccuracy = alpha).adding(r1, 42.0)
        for (q in listOf(0.0, 0.5, 1.0)) {
            val estimate = sketch.quantile(q)
            assertTrue(
                relativeError(estimate, 42.0) <= alpha + FP_SLACK,
                "q=$q: estimate $estimate not within α=$alpha of 42.0",
            )
        }
    }

    /**
     * The headline guarantee: for a stream spanning several orders of magnitude,
     * every quantile estimate is within relative error α of the exact quantile.
     * Seeded log-uniform input keeps the test deterministic while exercising the
     * full bucket range.
     */
    @Test
    fun quantilesOfLogUniformStreamAreWithinAlpha() {
        for (alpha in listOf(0.005, 0.01, 0.05)) {
            val rng = Random(42)
            val values = List(2_000) { logUniform(rng, 0.001, 1_000_000.0) }
            val sketch = DDSketch.empty(relativeAccuracy = alpha).adding(r1, values)
            val sorted = values.sorted()
            for (q in QUANTILES) {
                val estimate = sketch.quantile(q)
                val exact = exactQuantile(sorted, q)
                assertTrue(
                    relativeError(estimate, exact) <= alpha + FP_SLACK,
                    "α=$alpha q=$q: estimate $estimate vs exact $exact " +
                        "(relative error ${relativeError(estimate, exact)})",
                )
            }
        }
    }

    /** Negative values go through the mirrored store and keep the same α bound. */
    @Test
    fun negativeValueQuantilesAreWithinAlpha() {
        val alpha = 0.01
        val rng = Random(7)
        val values = List(1_000) { -logUniform(rng, 0.01, 10_000.0) }
        val sketch = DDSketch.empty(relativeAccuracy = alpha).adding(r1, values)
        val sorted = values.sorted()
        for (q in QUANTILES) {
            val estimate = sketch.quantile(q)
            val exact = exactQuantile(sorted, q)
            assertTrue(
                relativeError(estimate, exact) <= alpha + FP_SLACK,
                "q=$q: estimate $estimate vs exact $exact",
            )
        }
    }

    /** Mixed signs and zeros: the walk crosses negative buckets → zeros → positive buckets in value order. */
    @Test
    fun mixedSignQuantilesAreWithinAlpha() {
        val alpha = 0.02
        val rng = Random(11)
        val values = List(1_500) {
            when (it % 3) {
                0 -> logUniform(rng, 0.01, 1_000.0)
                1 -> -logUniform(rng, 0.01, 1_000.0)
                else -> 0.0
            }
        }
        val sketch = DDSketch.empty(relativeAccuracy = alpha).adding(r1, values)
        val sorted = values.sorted()
        for (q in QUANTILES) {
            val estimate = sketch.quantile(q)
            val exact = exactQuantile(sorted, q)
            if (exact == 0.0) {
                assertEquals(0.0, estimate, "q=$q: zeros are stored exactly")
            } else {
                assertTrue(
                    relativeError(estimate, exact) <= alpha + FP_SLACK,
                    "q=$q: estimate $estimate vs exact $exact",
                )
            }
        }
    }

    // ── CRDT laws ─────────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val sketch = seededSketch(r1, seed = 1, n = 200)
        assertEquals(sketch, sketch.piece(sketch))
    }

    @Test
    fun pieceIsCommutative() {
        val a = seededSketch(r1, seed = 1, n = 200)
        val b = seededSketch(r2, seed = 2, n = 200)
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun pieceIsAssociative() {
        val a = seededSketch(r1, seed = 1, n = 150)
        val b = seededSketch(r2, seed = 2, n = 150)
        val c = seededSketch(r3, seed = 3, n = 150)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    /** Re-delivering the same patch must not double-count (GCounter-backed buckets). */
    @Test
    fun redeliveredPatchIsIdempotent() {
        val base = seededSketch(r1, seed = 5, n = 100)
        val patch = base.add(r1, 123.45)
        val once = base.piece(patch)
        val twice = once.piece(patch)
        assertAll(
            { assertEquals(once, twice) },
            { assertEquals(once.count, twice.count) },
        )
    }

    /**
     * Merging two replicas' sketches is exactly the sketch of the union stream:
     * state equality, not just estimate proximity. This is the lossless-merge
     * property that distinguishes DDSketch from t-digest.
     */
    @Test
    fun mergeOfTwoReplicasEqualsSketchOfUnion() {
        val xs = List(300) { logUniform(Random(21), 0.01, 100_000.0) }
        val ys = List(300) { logUniform(Random(22), 0.01, 100_000.0) }
        val a = DDSketch.empty().adding(r1, xs)
        val b = DDSketch.empty().adding(r2, ys)
        val union = DDSketch.empty().adding(r1, xs).adding(r2, ys)
        assertEquals(union, a.piece(b))
    }

    /** Quantiles of the merged sketch are within α of the exact union quantiles. */
    @Test
    fun mergedQuantilesMatchUnionWithinAlpha() {
        val alpha = 0.01
        val xs = List(1_000) { logUniform(Random(31), 0.001, 1_000.0) }
        val ys = List(1_000) { logUniform(Random(32), 1.0, 1_000_000.0) }
        val merged = DDSketch.empty(relativeAccuracy = alpha).adding(r1, xs)
            .piece(DDSketch.empty(relativeAccuracy = alpha).adding(r2, ys))
        val sorted = (xs + ys).sorted()
        assertEquals(2_000L, merged.count)
        for (q in QUANTILES) {
            val estimate = merged.quantile(q)
            val exact = exactQuantile(sorted, q)
            assertTrue(
                relativeError(estimate, exact) <= alpha + FP_SLACK,
                "q=$q: merged estimate $estimate vs exact union quantile $exact",
            )
        }
    }

    // ── Config discipline ─────────────────────────────────────────────────────

    @Test
    fun pieceMismatchedConfigThrows() {
        assertAll(
            {
                assertFailsWith<IllegalArgumentException> {
                    DDSketch.empty(relativeAccuracy = 0.01).piece(DDSketch.empty(relativeAccuracy = 0.02))
                }
            },
            {
                assertFailsWith<IllegalArgumentException> {
                    DDSketch.empty(minIndexedValue = 1e-9).piece(DDSketch.empty(minIndexedValue = 1e-6))
                }
            },
            {
                assertFailsWith<IllegalArgumentException> {
                    DDSketch.empty(maxIndexedValue = 1e18).piece(DDSketch.empty(maxIndexedValue = 1e15))
                }
            },
        )
    }

    // ── Zero threshold and overflow clamp (bounded memory) ───────────────────

    @Test
    fun valuesBelowMinIndexedValueCountAsZeros() {
        val sketch = DDSketch.empty(minIndexedValue = 1e-9).adding(r1, 1e-12, 0.0, -0.0, -1e-15)
        assertAll(
            { assertEquals(4L, sketch.count) },
            { assertEquals(4L, sketch.zeroCount) },
            { assertEquals(0, sketch.bucketCount) },
            { assertEquals(0.0, sketch.quantile(0.5)) },
        )
    }

    @Test
    fun overflowValuesClampIntoTopBucketAndAreCounted() {
        val alpha = 0.01
        val max = 1e18
        val sketch = DDSketch.empty(relativeAccuracy = alpha, maxIndexedValue = max).adding(r1, 1e30)
        assertAll(
            { assertEquals(1L, sketch.count) },
            { assertEquals(1L, sketch.overflowCount) },
            { assertEquals(1, sketch.bucketCount) },
            {
                // The clamp caps the estimate near maxIndexedValue — bounded, not the true huge value.
                assertTrue(
                    relativeError(sketch.quantile(1.0), max) <= alpha + FP_SLACK,
                    "clamped estimate ${sketch.quantile(1.0)} should be within α of $max",
                )
            },
        )
    }

    @Test
    fun negativeOverflowClampsIntoMirroredTopBucket() {
        val sketch = DDSketch.empty(maxIndexedValue = 1e18).adding(r1, -1e30)
        assertAll(
            { assertEquals(1L, sketch.count) },
            { assertEquals(1L, sketch.overflowCount) },
            { assertTrue(sketch.quantile(0.0) < 0.0) },
        )
    }

    /** Memory is bounded by the indexable range, not by n or the data's spread. */
    @Test
    fun bucketCountIsBoundedByIndexedRange() {
        val alpha = 0.05
        val min = 1e-6
        val max = 1e9
        var sketch = DDSketch.empty(relativeAccuracy = alpha, minIndexedValue = min, maxIndexedValue = max)
        val rng = Random(99)
        repeat(5_000) {
            val v = logUniform(rng, 1e-12, 1e30) // deliberately wider than the indexable range
            sketch = sketch.piece(sketch.add(r1, if (it % 2 == 0) v else -v))
        }
        val gamma = (1 + alpha) / (1 - alpha)
        val perSignBound = ceil(ln(max / min) / ln(gamma)).toInt() + 2
        assertTrue(
            sketch.bucketCount <= 2 * perSignBound,
            "bucketCount ${sketch.bucketCount} exceeds bound ${2 * perSignBound}",
        )
    }

    // ── Delta sparsity ────────────────────────────────────────────────────────

    @Test
    fun addProducesSparseSingleBucketDelta() {
        val base = seededSketch(r1, seed = 8, n = 500)
        val patch = base.add(r1, 77.7)
        assertAll(
            { assertEquals(1, patch.delta.bucketCount) },
            { assertEquals(0L, patch.delta.zeroCount) },
        )
    }

    @Test
    fun zeroAddProducesBucketFreeDelta() {
        val base = seededSketch(r1, seed = 8, n = 100)
        val patch = base.add(r1, 0.0)
        assertAll(
            { assertEquals(0, patch.delta.bucketCount) },
            { assertTrue(patch.delta.zeroCount > 0L) },
        )
    }

    // ── Bucket views (exporter surface) ──────────────────────────────────────

    @Test
    fun bucketViewsExposeCountsByIndex() {
        val sketch = DDSketch.empty().adding(r1, 10.0, 10.0, -3.0)
        assertAll(
            { assertEquals(2L, sketch.positiveBuckets.values.sum()) },
            { assertEquals(1L, sketch.negativeBuckets.values.sum()) },
            { assertEquals(3L, sketch.count) },
        )
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val sketch = seededSketch(r1, seed = 13, n = 300).piece(seededSketch(r2, seed = 14, n = 300))
        val encoded = Json.encodeToString(DDSketch.serializer(), sketch)
        val decoded = Json.decodeFromString(DDSketch.serializer(), encoded)
        assertAll(
            { assertEquals(sketch, decoded) },
            { assertEquals(sketch.quantile(0.99), decoded.quantile(0.99)) },
            { assertEquals(sketch.count, decoded.count) },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun DDSketch.adding(replica: ReplicaId, vararg values: Double): DDSketch =
        values.fold(this) { acc, v -> acc.piece(acc.add(replica, v)) }

    private fun DDSketch.adding(replica: ReplicaId, values: List<Double>): DDSketch =
        values.fold(this) { acc, v -> acc.piece(acc.add(replica, v)) }

    private fun seededSketch(replica: ReplicaId, seed: Int, n: Int): DDSketch {
        val rng = Random(seed)
        return DDSketch.empty().adding(replica, List(n) { logUniform(rng, 0.01, 100_000.0) })
    }

    /** Exact quantile under the same rank rule the sketch uses: `sorted[⌊q·(n−1)⌋]`. */
    private fun exactQuantile(sorted: List<Double>, q: Double): Double =
        sorted[floor(q * (sorted.size - 1)).toInt()]

    private fun relativeError(estimate: Double, truth: Double): Double =
        abs(estimate - truth) / abs(truth)

    private fun logUniform(rng: Random, min: Double, max: Double): Double =
        exp(rng.nextDouble(ln(min), ln(max)))

    private companion object {
        val QUANTILES = listOf(0.0, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 1.0)

        /** Slack for floating-point noise at bucket boundaries; the analytic bound is exactly α. */
        const val FP_SLACK = 1e-9
    }
}
