package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.Int128
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

/**
 * A DDSketch quantile estimator: a sketch that answers "what was the median
 * response time?" or "what's the 99th-percentile latency?" from a stream of
 * measurements, using a small amount of memory instead of keeping every value.
 *
 * Think of it as a histogram that sizes its own buckets: instead of you guessing
 * bucket boundaries up front, the buckets grow logarithmically so that *every*
 * bucket is accurate to the same **relative** precision. A sketch built with
 * `relativeAccuracy = 0.01` answers any quantile within 1% of the true value —
 * whether the true value is 2 milliseconds or 2 minutes.
 *
 * **As a CRDT.** Each bucket's count is a [GCounter], so [piece] is a pointwise
 * `GCounter` join — idempotent, commutative, and associative. Many peers can
 * record measurements independently and merge sketches in any order, with any
 * duplication, and converge. The merge is also **lossless**: merging two
 * replicas' sketches produces exactly the sketch of the combined stream, so a
 * merge adds *zero* error on top of the α bound. (This is what rules out
 * t-digest, whose merge is order-dependent and lossy.)
 *
 * **Accuracy guarantee.** With relative accuracy α, bucket `i` covers
 * `(γ^(i−1), γ^i]` where `γ = (1+α)/(1−α)`; a value `v` is indexed at
 * `⌈log_γ |v|⌉` and estimated by the bucket representative `2γ^i/(γ+1)`, which
 * is within relative error α of every value in the bucket. Hence every
 * [quantile] estimate is within α of the exact quantile, for values inside the
 * indexable range (below). Zeros are counted exactly in a dedicated zero
 * bucket; negative values go through a mirrored bucket store with the same
 * guarantee. Reference: Masson, Rim, Lee — *DDSketch: A Fast and Fully-Mergeable
 * Quantile Sketch with Relative-Error Guarantees*, PVLDB 12(12), 2019.
 *
 * **Bounded memory — insert-time clamping, not state collapse.** A lazy
 * "collapse the lowest buckets when the map grows" rule is not a lattice
 * operation (two replicas collapsing at different times would diverge), so the
 * memory bound is enforced where it stays merge-safe — at insert:
 *
 * - magnitudes below [minIndexedValue] count as zeros (the OTLP
 *   `zero_threshold` semantics);
 * - magnitudes above [maxIndexedValue] clamp into the top bucket **and** are
 *   counted in the mergeable [overflowCount], so the cap is observable, never
 *   silent — alert on `overflowCount > 0` if the range matters.
 *
 * The bucket maps are sparse (only touched buckets exist) and their key space
 * is bounded by `⌈ln(max/min)/ln γ⌉` per sign — ≈3110 buckets per sign at the
 * defaults (α = 0.01, range 1e−9…1e18). Only values at the clamped extremes
 * trade away the α guarantee; everything in range keeps it.
 *
 * **Configuration is a cluster-wide constant.** Two sketches merge only if
 * their `(relativeAccuracy, minIndexedValue, maxIndexedValue)` match exactly —
 * the same must-match discipline as [HyperLogLog]'s precision. Fix it once per
 * deployment; [piece] rejects mismatches.
 *
 * **Immutable.** [add] does not mutate the receiver; it returns a [Patch]
 * whose delta carries a single bucket cell (or a single zero/overflow counter
 * cell) — the minimal sparse fragment idiom shared by the zoo's sketches.
 *
 * **OTel interop.** The state is structurally an OTLP
 * `ExponentialHistogramDataPoint`: [zeroCount] plus positive/negative
 * log-bucket arrays ([positiveBuckets]/[negativeBuckets]). The OTLP mapping
 * itself lives with the metrics exporter, not in this module.
 *
 * @sample us.tractat.kuilt.crdt.sampleDDSketch
 * @sample us.tractat.kuilt.crdt.sampleDDSketchMerge
 */
@Serializable
public class DDSketch private constructor(
    /** The relative-accuracy target α: every in-range quantile estimate is within α of exact. */
    public val relativeAccuracy: Double,
    /** Magnitudes below this threshold count as zeros (OTLP `zero_threshold` semantics). */
    public val minIndexedValue: Double,
    /** Magnitudes above this clamp into the top bucket and increment [overflowCount]. */
    public val maxIndexedValue: Double,
    @Serializable(with = CanonicalMapSerializer::class)
    private val positive: Map<Int, GCounter>,
    @Serializable(with = CanonicalMapSerializer::class)
    private val negative: Map<Int, GCounter>,
    private val zeros: GCounter,
    private val overflows: GCounter,
) : Quilted<DDSketch> {

    /** The bucket-boundary growth factor `γ = (1+α)/(1−α)`. Derived; not serialized. */
    public val gamma: Double get() = (1.0 + relativeAccuracy) / (1.0 - relativeAccuracy)

    private val lnGamma: Double get() = ln(gamma)

    /**
     * Total number of recorded values (bucketed + zeros).
     *
     * @throws ArithmeticException if one bucket's count, summed across replicas, does not fit in
     *   a `Long` (#3318). The sum across buckets is not yet checked, tracked by #3343.
     */
    public val count: Long
        get() = positive.values.sumOf { it.value } +
            negative.values.sumOf { it.value } +
            zeros.value

    /**
     * Number of recorded values whose magnitude was below [minIndexedValue] (including exact zeros).
     *
     * @throws ArithmeticException if the count, summed across replicas, does not fit in a `Long` (#3318).
     */
    public val zeroCount: Long get() = zeros.value

    /**
     * Number of recorded values whose magnitude exceeded [maxIndexedValue] and
     * was clamped into the top bucket. These values are included in [count] and
     * in the top bucket; this counter makes the clamp observable — a non-zero
     * value means the configured range is too narrow for the data.
     *
     * @throws ArithmeticException if the count, summed across replicas, does not fit in a `Long` (#3318).
     */
    public val overflowCount: Long get() = overflows.value

    /** Number of distinct buckets currently held (positive + negative stores). */
    public val bucketCount: Int get() = positive.size + negative.size

    /**
     * Per-bucket counts for positive values, keyed by bucket index `⌈log_γ v⌉`.
     *
     * @throws ArithmeticException if a bucket's count, summed across replicas, does not fit in a `Long` (#3318).
     */
    public val positiveBuckets: Map<Int, Long> get() = positive.mapValues { (_, c) -> c.value }

    /**
     * Per-bucket counts for negative values, keyed by bucket index `⌈log_γ |v|⌉` (mirrored store).
     *
     * @throws ArithmeticException if a bucket's count, summed across replicas, does not fit in a `Long` (#3318).
     */
    public val negativeBuckets: Map<Int, Long> get() = negative.mapValues { (_, c) -> c.value }

    /**
     * Record [value] as observed by [replica]. Returns a [Patch] carrying the
     * minimal delta — one bucket cell (plus the overflow counter when the value
     * clamps). The receiver is unchanged; apply with [piece]:
     * `sketch = sketch.piece(sketch.add(replica, v))`.
     *
     * Because each cell is a [GCounter] slot owned by [replica], a re-delivered
     * patch is absorbed idempotently — counts never inflate under duplication.
     * As with [GCounter], two peers must never share a [ReplicaId].
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     * @throws ArithmeticException if [replica]'s slot in the bucket, or in the overflow counter,
     *   would pass [Long.MAX_VALUE] (#3318).
     */
    public fun add(replica: ReplicaId, value: Double): Patch<DDSketch> {
        require(value.isFinite()) { "DDSketch values must be finite, was $value" }
        val magnitude = abs(value)
        if (magnitude < minIndexedValue) {
            return Patch(delta(zeros = zeros.inc(replica).delta))
        }
        val overflowing = magnitude > maxIndexedValue
        val index = bucketIndex(if (overflowing) maxIndexedValue else magnitude)
        val store = if (value > 0.0) positive else negative
        val cell = mapOf(index to (store[index] ?: GCounter.ZERO).inc(replica).delta)
        return Patch(
            delta(
                positive = if (value > 0.0) cell else emptyMap(),
                negative = if (value > 0.0) emptyMap() else cell,
                overflows = if (overflowing) overflows.inc(replica).delta else GCounter.ZERO,
            ),
        )
    }

    /**
     * Record counts that were **already bucketed** elsewhere, as observed by [replica]: [positive]
     * and [negative] map a bucket index — in this sketch's own indices, the keys of
     * [positiveBuckets] and [negativeBuckets] — to how many values fell in it, and [zeroCount]
     * counts the values that were zero.
     *
     * This is the weighted twin of [add]. Adding `n` to a bucket here leaves the sketch exactly as
     * `n` calls to [add] with values from that bucket would: each touched cell is this replica's
     * [GCounter] slot, incremented by the count with `inc(by)`. So successive calls **add**, which
     * is what a stream of per-interval bucket deltas needs, where [piece] — a join — would keep only
     * the larger of two deltas. A re-delivered patch is still absorbed idempotently, as with [add].
     *
     * Indices outside the indexable range clamp the way [add] clamps a value, at bucket
     * granularity: a bucket below the one holding [minIndexedValue] counts as zeros, and a bucket
     * above the one holding [maxIndexedValue] lands in that top bucket and is counted in
     * [overflowCount]. The two edge buckets themselves are kept as given, since a bucket count
     * cannot say which side of the threshold its values were on.
     *
     * A count of zero is skipped, so it creates no empty cell; the patch for an all-zero call is
     * empty. The receiver is unchanged; apply with [piece].
     *
     * @throws IllegalArgumentException if any count, or [zeroCount], is negative.
     * @throws ArithmeticException if [replica]'s slot in a bucket, or in the zero or overflow
     *   counter, would pass [Long.MAX_VALUE] (#3318).
     */
    public fun addBuckets(
        replica: ReplicaId,
        positive: Map<Int, Long>,
        negative: Map<Int, Long>,
        zeroCount: Long,
    ): Patch<DDSketch> {
        require(zeroCount >= 0L) { "DDSketch zeroCount must not be negative, was $zeroCount" }
        positive.forEach { (index, n) -> require(n >= 0L) { "DDSketch positive bucket $index count must not be negative, was $n" } }
        negative.forEach { (index, n) -> require(n >= 0L) { "DDSketch negative bucket $index count must not be negative, was $n" } }
        val lowest = bucketIndex(minIndexedValue)
        val highest = bucketIndex(maxIndexedValue)
        var toZeros = zeroCount
        var toOverflow = 0L
        // Fold each sign's counts onto the indices `add` could have produced, then increment once per cell.
        fun clamped(buckets: Map<Int, Long>): Map<Int, Long> {
            val out = LinkedHashMap<Int, Long>()
            for ((index, n) in buckets) {
                if (n == 0L) continue
                when {
                    index < lowest -> toZeros += n
                    index > highest -> {
                        toOverflow += n
                        out[highest] = (out[highest] ?: 0L) + n
                    }
                    else -> out[index] = (out[index] ?: 0L) + n
                }
            }
            return out
        }
        fun cells(store: Map<Int, GCounter>, counts: Map<Int, Long>): Map<Int, GCounter> =
            counts.mapValues { (index, n) -> (store[index] ?: GCounter.ZERO).inc(replica, n).delta }
        val positiveCells = cells(this.positive, clamped(positive))
        val negativeCells = cells(this.negative, clamped(negative))
        return Patch(
            delta(
                positive = positiveCells,
                negative = negativeCells,
                zeros = if (toZeros > 0L) zeros.inc(replica, toZeros).delta else GCounter.ZERO,
                overflows = if (toOverflow > 0L) overflows.inc(replica, toOverflow).delta else GCounter.ZERO,
            ),
        )
    }

    /**
     * Estimate the [q]-quantile (q in `[0, 1]`) of all recorded values.
     *
     * Walks the buckets in ascending value order (negative store by descending
     * index, then zeros, then positive store by ascending index) to rank
     * `⌊q·(count−1)⌋` and returns that bucket's representative — which is
     * within relative error [relativeAccuracy] of the exact quantile, provided
     * the exact quantile's magnitude lies within the indexable range.
     * `quantile(0.0)` is the minimum estimate, `quantile(1.0)` the maximum.
     *
     * @throws IllegalArgumentException if [q] is outside `[0, 1]` or the sketch is empty.
     * @throws ArithmeticException if [count] throws, or a bucket's count does not fit in a `Long` (#3318).
     */
    public fun quantile(q: Double): Double {
        require(q in 0.0..1.0) { "quantile must be in [0, 1], was $q" }
        val n = count
        require(n > 0L) { "quantile of an empty DDSketch" }
        val rank = q * (n - 1)
        var cumulative = 0L
        for ((index, counter) in negative.entries.sortedByDescending { it.key }) {
            cumulative += counter.value
            if (cumulative > rank) return -representative(index)
        }
        cumulative += zeros.value
        if (cumulative > rank) return 0.0
        for ((index, counter) in positive.entries.sortedBy { it.key }) {
            cumulative += counter.value
            if (cumulative > rank) return representative(index)
        }
        error("unreachable: cumulative count $cumulative never exceeded rank $rank (count $n)")
    }

    /**
     * The join: a pointwise [GCounter] join of every bucket cell plus the zero
     * and overflow counters. Inherits the three lattice laws from [GCounter],
     * and is **lossless** — with distinct replicas, per-replica counts combine
     * exactly, so the merged sketch equals the sketch of the combined stream.
     *
     * @throws IllegalArgumentException if the two sketches' configuration
     *   (`relativeAccuracy`, `minIndexedValue`, `maxIndexedValue`) differs —
     *   the bucket indices of differently-configured sketches are incomparable.
     */
    override fun piece(other: DDSketch): DDSketch {
        require(
            relativeAccuracy == other.relativeAccuracy &&
                minIndexedValue == other.minIndexedValue &&
                maxIndexedValue == other.maxIndexedValue,
        ) {
            "Cannot merge DDSketches with different configuration: " +
                "(α=$relativeAccuracy, range=[$minIndexedValue, $maxIndexedValue]) vs " +
                "(α=${other.relativeAccuracy}, range=[${other.minIndexedValue}, ${other.maxIndexedValue}])"
        }
        return DDSketch(
            relativeAccuracy,
            minIndexedValue,
            maxIndexedValue,
            positive.mergeValues(other.positive) { mine, theirs -> mine.piece(theirs) },
            negative.mergeValues(other.negative) { mine, theirs -> mine.piece(theirs) },
            zeros.piece(other.zeros),
            overflows.piece(other.overflows),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is DDSketch &&
            relativeAccuracy == other.relativeAccuracy &&
            minIndexedValue == other.minIndexedValue &&
            maxIndexedValue == other.maxIndexedValue &&
            positive == other.positive &&
            negative == other.negative &&
            zeros == other.zeros &&
            overflows == other.overflows

    override fun hashCode(): Int {
        var result = relativeAccuracy.hashCode()
        result = 31 * result + minIndexedValue.hashCode()
        result = 31 * result + maxIndexedValue.hashCode()
        result = 31 * result + positive.hashCode()
        result = 31 * result + negative.hashCode()
        result = 31 * result + zeros.hashCode()
        result = 31 * result + overflows.hashCode()
        return result
    }

    // Exact counts, not [count]/[zeroCount]/[overflowCount]: a sketch whose counts do not fit in a
    // `Long` must still print.
    override fun toString(): String =
        "DDSketch(α=$relativeAccuracy, count=$exactCount, buckets=$bucketCount, " +
            "zeros=${zeros.exactValue}, overflows=${overflows.exactValue})"

    private val exactCount: Int128
        get() = (positive.values + negative.values + zeros).fold(Int128.ZERO) { sum, c -> sum + c.exactValue }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Bucket index for a magnitude within the indexable range: `⌈log_γ m⌉`. */
    private fun bucketIndex(magnitude: Double): Int = ceil(ln(magnitude) / lnGamma).toInt()

    /**
     * The representative value of bucket [index]: `2γ^i/(γ+1)`, the point that
     * minimizes the worst-case relative error over the bucket `(γ^(i−1), γ^i]`
     * — exactly α at both edges.
     */
    private fun representative(index: Int): Double = 2.0 * exp(index * lnGamma) / (gamma + 1.0)

    /** A minimal same-config fragment for [add] deltas. */
    private fun delta(
        positive: Map<Int, GCounter> = emptyMap(),
        negative: Map<Int, GCounter> = emptyMap(),
        zeros: GCounter = GCounter.ZERO,
        overflows: GCounter = GCounter.ZERO,
    ): DDSketch = DDSketch(relativeAccuracy, minIndexedValue, maxIndexedValue, positive, negative, zeros, overflows)

    public companion object {
        /** Default relative-accuracy target: 1%. */
        public const val DEFAULT_RELATIVE_ACCURACY: Double = 0.01

        /** Default zero threshold: magnitudes below 1e−9 count as zeros. */
        public const val DEFAULT_MIN_INDEXED_VALUE: Double = 1e-9

        /** Default top clamp: magnitudes above 1e18 clamp into the top bucket. */
        public const val DEFAULT_MAX_INDEXED_VALUE: Double = 1e18

        /**
         * An empty sketch.
         *
         * All three parameters are cluster-wide constants: sketches merge only
         * when they match exactly. The defaults (α = 0.01, indexable range
         * 1e−9…1e18) give 1% quantile accuracy over 27 orders of magnitude with
         * at most ≈3110 buckets per sign.
         *
         * @param relativeAccuracy α in (0, 1) exclusive. Smaller α → tighter
         *   quantile estimates and more (finer) buckets.
         * @param minIndexedValue smallest indexable magnitude (> 0); anything
         *   smaller counts as zero.
         * @param maxIndexedValue largest indexable magnitude (> [minIndexedValue]);
         *   anything larger clamps into the top bucket and increments
         *   [overflowCount].
         */
        public fun empty(
            relativeAccuracy: Double = DEFAULT_RELATIVE_ACCURACY,
            minIndexedValue: Double = DEFAULT_MIN_INDEXED_VALUE,
            maxIndexedValue: Double = DEFAULT_MAX_INDEXED_VALUE,
        ): DDSketch {
            require(relativeAccuracy > 0.0 && relativeAccuracy < 1.0) {
                "relativeAccuracy must be in (0, 1) exclusive, was $relativeAccuracy"
            }
            require(minIndexedValue > 0.0) { "minIndexedValue must be > 0, was $minIndexedValue" }
            require(maxIndexedValue > minIndexedValue) {
                "maxIndexedValue ($maxIndexedValue) must exceed minIndexedValue ($minIndexedValue)"
            }
            return DDSketch(
                relativeAccuracy,
                minIndexedValue,
                maxIndexedValue,
                positive = emptyMap(),
                negative = emptyMap(),
                zeros = GCounter.ZERO,
                overflows = GCounter.ZERO,
            )
        }
    }
}
