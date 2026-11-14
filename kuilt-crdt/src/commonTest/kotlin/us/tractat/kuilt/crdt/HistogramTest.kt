package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistogramTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    private val boundaries = listOf(10.0, 50.0, 100.0)

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun boundariesMustBeStrictlyIncreasing() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { Histogram.empty(listOf(50.0, 10.0)) } },
            { assertFailsWith<IllegalArgumentException> { Histogram.empty(listOf(10.0, 10.0)) } },
        )
    }

    @Test
    fun boundariesMustBeFinite() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { Histogram.empty(listOf(Double.NaN)) } },
            { assertFailsWith<IllegalArgumentException> { Histogram.empty(listOf(1.0, Double.POSITIVE_INFINITY)) } },
        )
    }

    @Test
    fun emptyHistogramHasZeroCounts() {
        val histogram = Histogram.empty(boundaries)
        assertAll(
            { assertEquals(0L, histogram.count) },
            { assertEquals(0.0, histogram.sum) },
            { assertEquals(listOf(0L, 0L, 0L, 0L), histogram.bucketCounts) },
        )
    }

    @Test
    fun emptyBoundariesGiveASingleCatchAllBucket() {
        val histogram = Histogram.empty(emptyList()).recording(r1, -5.0, 0.0, 1e9)
        assertAll(
            { assertEquals(3L, histogram.count) },
            { assertEquals(listOf(3L), histogram.bucketCounts) },
        )
    }

    // ── Bucketing (OTLP upper-inclusive bounds) ───────────────────────────────

    @Test
    fun valuesLandInTheRightBucket() {
        // Buckets: (-inf, 10], (10, 50], (50, 100], (100, +inf)
        val histogram = Histogram.empty(boundaries)
            .recording(r1, -3.0, 5.0) // bucket 0
            .recording(r1, 10.0) // upper-inclusive: exactly 10 → bucket 0
            .recording(r1, 10.5, 50.0) // bucket 1 (50 is upper-inclusive in bucket 1)
            .recording(r1, 99.0) // bucket 2
            .recording(r1, 100.5, 1e6) // bucket 3
        assertEquals(listOf(3L, 2L, 1L, 2L), histogram.bucketCounts)
    }

    @Test
    fun nonFiniteValuesAreRejected() {
        val histogram = Histogram.empty(boundaries)
        assertAll(
            { assertFailsWith<IllegalArgumentException> { histogram.record(r1, Double.NaN) } },
            { assertFailsWith<IllegalArgumentException> { histogram.record(r1, Double.POSITIVE_INFINITY) } },
            { assertFailsWith<IllegalArgumentException> { histogram.record(r1, Double.NEGATIVE_INFINITY) } },
        )
    }

    @Test
    fun countAndSumTrackRecordedValues() {
        val histogram = Histogram.empty(boundaries).recording(r1, 5.0, 20.0, -3.0, 0.0)
        assertAll(
            { assertEquals(4L, histogram.count) },
            { assertEquals(22.0, histogram.sum) },
        )
    }

    // ── CRDT laws ─────────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val histogram = seededHistogram(r1, seed = 1, n = 200)
        assertEquals(histogram, histogram.piece(histogram))
    }

    @Test
    fun pieceIsCommutative() {
        val a = seededHistogram(r1, seed = 1, n = 200)
        val b = seededHistogram(r2, seed = 2, n = 200)
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun pieceIsAssociative() {
        val a = seededHistogram(r1, seed = 1, n = 150)
        val b = seededHistogram(r2, seed = 2, n = 150)
        val c = seededHistogram(r3, seed = 3, n = 150)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    /** Re-delivering the same patch must not double-count (GCounter-backed buckets). */
    @Test
    fun redeliveredPatchIsIdempotent() {
        val base = seededHistogram(r1, seed = 5, n = 100)
        val patch = base.record(r1, 42.0)
        val once = base.piece(patch)
        val twice = once.piece(patch)
        assertAll(
            { assertEquals(once, twice) },
            { assertEquals(once.count, twice.count) },
            { assertEquals(once.sum, twice.sum) },
        )
    }

    /**
     * Merging two replicas' histograms is exactly the histogram of the union
     * stream: state equality, not just per-bucket count equality.
     */
    @Test
    fun mergeOfTwoReplicasEqualsHistogramOfUnion() {
        val rng = Random(21)
        val xs = List(300) { rng.nextDouble(0.0, 200.0) }
        val ys = List(300) { rng.nextDouble(0.0, 200.0) }
        val a = Histogram.empty(boundaries).recording(r1, xs)
        val b = Histogram.empty(boundaries).recording(r2, ys)
        val union = Histogram.empty(boundaries).recording(r1, xs).recording(r2, ys)
        assertEquals(union, a.piece(b))
    }

    /** Seeded convergence: shuffled merge orders over three replicas all agree. */
    @Test
    fun shuffledMergeOrdersConverge() {
        val perReplica = listOf(
            seededHistogram(r1, seed = 31, n = 250),
            seededHistogram(r2, seed = 32, n = 250),
            seededHistogram(r3, seed = 33, n = 250),
        )
        val orders = listOf(perReplica, perReplica.reversed(), perReplica.shuffled(Random(7)))
        val merged = orders.map { order -> order.reduce { acc, h -> acc.piece(h) } }
        assertAll(
            { assertEquals(750L, merged[0].count) },
            { assertEquals(merged[0], merged[1]) },
            { assertEquals(merged[0], merged[2]) },
        )
    }

    // ── Config discipline ─────────────────────────────────────────────────────

    @Test
    fun pieceMismatchedBoundariesThrows() {
        assertFailsWith<IllegalArgumentException> {
            Histogram.empty(listOf(10.0, 50.0)).piece(Histogram.empty(listOf(10.0, 100.0)))
        }
    }

    // ── Delta sparsity ────────────────────────────────────────────────────────

    @Test
    fun recordProducesSparseSingleBucketDelta() {
        val base = seededHistogram(r1, seed = 8, n = 500)
        val patch = base.record(r1, 77.7)
        assertAll(
            // A single touched cell — the GCounter delta carries the replica's new slot total.
            { assertEquals(1, patch.delta.bucketCounts.count { it > 0L }) },
            { assertEquals(2, patch.delta.bucketCounts.indexOfFirst { it > 0L }) }, // 77.7 → (50, 100]
        )
    }

    // ── Views (exporter surface) ──────────────────────────────────────────────

    @Test
    fun bucketCountsIsDenseAndSumsToCount() {
        val histogram = seededHistogram(r1, seed = 13, n = 400)
        assertAll(
            { assertEquals(boundaries.size + 1, histogram.bucketCounts.size) },
            { assertEquals(histogram.count, histogram.bucketCounts.sum()) },
            { assertTrue(histogram.bucketCounts.all { it >= 0L }) },
        )
    }

    // ── Counts past Long.MAX_VALUE (#3343) ────────────────────────────────────

    /**
     * Two buckets whose counts each fit and add up to one past [Long.MAX_VALUE]: the count is
     * refused rather than wrapped. Before the fix it read [Long.MIN_VALUE].
     */
    @Test
    fun aCountPastLongMaxValueAcrossBucketsIsRefusedNotWrapped() {
        val histogram = twoBuckets(Long.MAX_VALUE, 1L)
        assertAll(
            { assertEquals(listOf(Long.MAX_VALUE, 1L, 0L, 0L), histogram.bucketCounts, "the rig: each bucket fits") },
            { assertFailsWith<ArithmeticException> { histogram.count } },
            { assertTrue("counts=[9223372036854775807, 1, 0, 0]" in histogram.toString(), histogram.toString()) },
        )
    }

    /** The boundary on the other side: buckets adding up to exactly [Long.MAX_VALUE] are answered. */
    @Test
    fun aCountOfExactlyLongMaxValueAcrossBucketsIsAnswered() {
        assertEquals(Long.MAX_VALUE, twoBuckets(Long.MAX_VALUE - 1L, 1L).count)
    }

    /**
     * A histogram over [boundaries] whose first two buckets hold [first] and [second], both in r1's
     * slot. A replica reaches a count near [Long.MAX_VALUE] only one `record` at a time, so it is
     * built through the histogram's wire form instead, field for field.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun twoBuckets(first: Long, second: Long): Histogram {
        val wire = HistogramWire(
            boundaries = boundaries,
            buckets = mapOf(0 to GCounter.of(r1 to first), 1 to GCounter.of(r1 to second)),
            positiveSum = GCounterDouble.ZERO,
            negativeSum = GCounterDouble.ZERO,
        )
        return Cbor.decodeFromByteArray(Histogram.serializer(), Cbor.encodeToByteArray(HistogramWire.serializer(), wire))
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val histogram = seededHistogram(r1, seed = 17, n = 300).piece(seededHistogram(r2, seed = 18, n = 300))
        val encoded = Json.encodeToString(Histogram.serializer(), histogram)
        val decoded = Json.decodeFromString(Histogram.serializer(), encoded)
        assertAll(
            { assertEquals(histogram, decoded) },
            { assertEquals(histogram.bucketCounts, decoded.bucketCounts) },
            { assertEquals(histogram.sum, decoded.sum) },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun Histogram.recording(replica: ReplicaId, vararg values: Double): Histogram =
        values.fold(this) { acc, v -> acc.piece(acc.record(replica, v)) }

    private fun Histogram.recording(replica: ReplicaId, values: List<Double>): Histogram =
        values.fold(this) { acc, v -> acc.piece(acc.record(replica, v)) }

    private fun seededHistogram(replica: ReplicaId, seed: Int, n: Int): Histogram {
        val rng = Random(seed)
        return Histogram.empty(boundaries).recording(replica, List(n) { rng.nextDouble(-20.0, 220.0) })
    }
}

/** [Histogram]'s serialized fields, without its constructor's checks, to build a state no replica could record. */
@Serializable
private class HistogramWire(
    val boundaries: List<Double>,
    val buckets: Map<Int, GCounter>,
    val positiveSum: GCounterDouble,
    val negativeSum: GCounterDouble,
)
