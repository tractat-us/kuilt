package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An explicit-bucket histogram: you choose the bucket boundaries up front —
 * say `[10, 50, 100]` milliseconds — and each bucket counts how many recorded
 * values fell in its range. It answers "how are my measurements *distributed*?"
 * when you already know the ranges you care about (SLA thresholds, size
 * classes), the classic fixed-bucket histogram shape.
 *
 * **As a CRDT.** Each bucket's count is a [GCounter], so [piece] is a
 * pointwise `GCounter` join — idempotent, commutative, and associative. Many
 * peers can record values independently and merge histograms in any order,
 * with any duplication, and converge; the merge is **lossless** — merging two
 * replicas' histograms produces exactly the histogram of the combined stream.
 * Because each cell is per-replica-keyed, a re-delivered patch never
 * double-counts.
 *
 * **Bucketing.** [boundaries] is a strictly-increasing list of `N` upper
 * bounds defining `N + 1` buckets with OTLP's upper-inclusive convention:
 * bucket 0 is `(-∞, bounds[0]]`, bucket `i` is `(bounds[i−1], bounds[i]]`,
 * and the last bucket is `(bounds[N−1], +∞)`. An empty boundary list is the
 * degenerate single catch-all bucket. Choose boundaries to fit the expected
 * range — a value past the last boundary still counts, but all resolution
 * beyond it is lost (if the range is unknowable up front, prefer [DDSketch],
 * whose log buckets auto-cover any range at uniform relative precision).
 *
 * **Configuration is a cluster-wide constant.** Two histograms merge only if
 * their [boundaries] match exactly — the same must-match discipline as
 * [DDSketch]'s accuracy and [HyperLogLog]'s precision. Fix the boundaries once
 * per deployment; [piece] rejects mismatches.
 *
 * **Immutable.** [record] does not mutate the receiver; it returns a [Patch]
 * whose delta carries a single bucket cell (plus a sum cell) — the minimal
 * sparse fragment idiom shared by the zoo's sketches.
 *
 * **Sum, but no min/max.** [sum] carries the running total (for the mean) as a
 * pair of [GCounterDouble]s (positive and negative contributions), which keeps
 * it mergeable. OTLP's optional `min`/`max` are deliberately omitted: they are
 * not products of grow-only counters and would need separate min-/max-register
 * lattices — add those alongside if a consumer ever needs them.
 *
 * **OTel interop.** The state is structurally an OTLP `HistogramDataPoint`:
 * explicit `bounds` ([boundaries]) plus `bucket_counts` ([bucketCounts]),
 * `count`, and `sum`. The OTLP mapping itself lives with the metrics exporter,
 * not in this module.
 *
 * @sample us.tractat.kuilt.crdt.sampleHistogram
 * @sample us.tractat.kuilt.crdt.sampleHistogramMerge
 */
@Serializable
public class Histogram private constructor(
    /** The `N` strictly-increasing upper bounds defining `N + 1` buckets. Cluster-wide constant. */
    public val boundaries: List<Double>,
    private val buckets: Map<Int, GCounter>,
    private val positiveSum: GCounterDouble,
    private val negativeSum: GCounterDouble,
) : Quilted<Histogram> {

    /** Total number of recorded values. Always equals `bucketCounts.sum()`. */
    public val count: Long get() = buckets.values.sumOf { it.value }

    /** The sum of all recorded values (for the mean), computed replica-order-deterministically. */
    public val sum: Double get() = positiveSum.value - negativeSum.value

    /**
     * Per-bucket counts as a dense list of size `boundaries.size + 1` — bucket
     * `i` in the OTLP upper-inclusive convention (see class docs). This is the
     * `bucket_counts` array of an OTLP `HistogramDataPoint`.
     */
    public val bucketCounts: List<Long>
        get() = List(boundaries.size + 1) { index -> buckets[index]?.value ?: 0L }

    /**
     * Record [value] as observed by [replica]. Returns a [Patch] carrying the
     * minimal delta — one bucket cell plus a sum cell. The receiver is
     * unchanged; apply with [piece]:
     * `histogram = histogram.piece(histogram.record(replica, v))`.
     *
     * Because each cell is a [GCounter] slot owned by [replica], a re-delivered
     * patch is absorbed idempotently — counts never inflate under duplication.
     * As with [GCounter], two peers must never share a [ReplicaId].
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public fun record(replica: ReplicaId, value: Double): Patch<Histogram> {
        require(value.isFinite()) { "Histogram values must be finite, was $value" }
        val index = bucketIndex(value)
        return Patch(
            Histogram(
                boundaries,
                buckets = mapOf(index to (buckets[index] ?: GCounter.ZERO).inc(replica).delta),
                positiveSum = if (value > 0.0) positiveSum.inc(replica, value).delta else GCounterDouble.ZERO,
                negativeSum = if (value < 0.0) negativeSum.inc(replica, -value).delta else GCounterDouble.ZERO,
            ),
        )
    }

    /**
     * The join: a pointwise [GCounter] join of every bucket cell plus the sum
     * counters. Inherits the three lattice laws from [GCounter], and is
     * **lossless** — with distinct replicas, per-replica counts combine
     * exactly, so the merged histogram equals the histogram of the combined
     * stream.
     *
     * @throws IllegalArgumentException if the two histograms' [boundaries]
     *   differ — the bucket indices of differently-bucketed histograms are
     *   incomparable.
     */
    override fun piece(other: Histogram): Histogram {
        require(boundaries == other.boundaries) {
            "Cannot merge Histograms with different boundaries: $boundaries vs ${other.boundaries}"
        }
        return Histogram(
            boundaries,
            buckets.mergeValues(other.buckets) { mine, theirs -> mine.piece(theirs) },
            positiveSum.piece(other.positiveSum),
            negativeSum.piece(other.negativeSum),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is Histogram &&
            boundaries == other.boundaries &&
            buckets == other.buckets &&
            positiveSum == other.positiveSum &&
            negativeSum == other.negativeSum

    override fun hashCode(): Int {
        var result = boundaries.hashCode()
        result = 31 * result + buckets.hashCode()
        result = 31 * result + positiveSum.hashCode()
        result = 31 * result + negativeSum.hashCode()
        return result
    }

    override fun toString(): String = "Histogram(boundaries=$boundaries, counts=$bucketCounts, sum=$sum)"

    /** Bucket index for [value]: the first `i` with `value <= boundaries[i]`, else the overflow bucket. */
    private fun bucketIndex(value: Double): Int {
        val found = boundaries.binarySearch(value)
        return if (found >= 0) found else -(found + 1)
    }

    public companion object {
        /**
         * An empty histogram over the given bucket [boundaries].
         *
         * The boundaries are a cluster-wide constant: histograms merge only
         * when they match exactly.
         *
         * @param boundaries strictly-increasing, finite upper bounds; `N`
         *   boundaries define `N + 1` buckets (empty list ⇒ one catch-all
         *   bucket).
         */
        public fun empty(boundaries: List<Double>): Histogram {
            require(boundaries.all { it.isFinite() }) { "Histogram boundaries must be finite, were $boundaries" }
            require(boundaries.zipWithNext().all { (lower, upper) -> lower < upper }) {
                "Histogram boundaries must be strictly increasing, were $boundaries"
            }
            return Histogram(boundaries, emptyMap(), GCounterDouble.ZERO, GCounterDouble.ZERO)
        }
    }
}
