package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.DDSketch
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

// DDSketch → OTLP ExponentialHistogramDataPoint mapping (#1269).
//
// Both sides bucket by powers of a base with upper-inclusive edges, so when the bases
// match the mapping is a pure re-index — no re-bucketing, no added error:
//
//  - DDSketch bucket `i` covers `(γ^(i−1), γ^i]` with `γ = (1+α)/(1−α)`.
//  - OTLP bucket `j` covers `(base^j, base^(j+1)]` with `base = 2^(2^−scale)`.
//
// With `γ = base`, DDSketch bucket `i` **is** OTLP bucket `i − 1`. The one degree of
// freedom is α: OTLP scales are integers, DDSketch's α is free-form. kuilt resolves it
// deliberately (option (a) of #1269): consumers pick α via [alphaForOtlpScale], and the
// exporter rejects sketches whose α does not correspond to an integer scale — so the
// OTLP render is always an exact, lossless re-index.

/**
 * Smallest supported OTLP exponential-histogram scale (base `2^32`, `α ≈ 1 − 4.7·10⁻¹⁰`).
 * OTLP itself permits scales down to −10, but below −5 the equivalent DDSketch α rounds
 * to 1.0 in a `Double` (and at −10 the base `2^1024` overflows entirely), so no valid
 * sketch exists for those scales.
 */
public const val MIN_OTLP_HISTOGRAM_SCALE: Int = -5

/** Largest OTLP exponential-histogram scale (base `2^(2^−20)` — ~0.000033% relative accuracy). */
public const val MAX_OTLP_HISTOGRAM_SCALE: Int = 20

/**
 * The default OTLP scale for [WarpMetricExporter] histograms. Scale 5 gives
 * `α ≈ 1.083%` — the closest OTLP-aligned accuracy to [DDSketch.DEFAULT_RELATIVE_ACCURACY]
 * (1%), at ≈32 buckets per power of two.
 */
public const val DEFAULT_OTLP_HISTOGRAM_SCALE: Int = 5

/**
 * The [DDSketch] relative accuracy α that aligns exactly with the OTLP
 * exponential-histogram [scale]: `α = (γ−1)/(γ+1)` where `γ = 2^(2^−scale)`.
 *
 * A sketch built with `DDSketch.empty(relativeAccuracy = alphaForOtlpScale(s))` exports
 * to an OTLP `ExponentialHistogramDataPoint` of scale `s` losslessly — each DDSketch
 * bucket maps one-to-one onto an OTLP bucket (both use upper-inclusive edges; the OTLP
 * index is the DDSketch index minus one).
 *
 * Larger scales mean tighter accuracy: scale 0 is `α = 1/3`, scale 5 is `α ≈ 1.08%`,
 * scale 10 is `α ≈ 0.034%`.
 *
 * @param scale an integer OTLP scale in `[-10, 20]` (the range the OTLP data model permits).
 * @throws IllegalArgumentException if [scale] is outside that range.
 */
public fun alphaForOtlpScale(scale: Int): Double {
    require(scale in MIN_OTLP_HISTOGRAM_SCALE..MAX_OTLP_HISTOGRAM_SCALE) {
        "OTLP exponential-histogram scale must be in " +
            "[$MIN_OTLP_HISTOGRAM_SCALE, $MAX_OTLP_HISTOGRAM_SCALE], was $scale"
    }
    val gamma = 2.0.pow(2.0.pow(-scale))
    return (gamma - 1.0) / (gamma + 1.0)
}

/**
 * The integer OTLP scale whose base equals a [DDSketch]'s bucket growth factor
 * `γ = (1+relativeAccuracy)/(1−relativeAccuracy)` — the inverse of [alphaForOtlpScale].
 *
 * The derivation is `scale = −log₂(log₂ γ)`, rounded to the nearest integer and
 * verified: if [relativeAccuracy] was not produced by [alphaForOtlpScale] (within
 * floating-point tolerance), there is no integer scale and this throws — re-bucketing a
 * mismatched γ onto a power-of-two base would silently break the α guarantee, so kuilt
 * refuses instead.
 *
 * @throws IllegalArgumentException if [relativeAccuracy] does not correspond to an
 *   integer OTLP scale; build the sketch with `alphaForOtlpScale(scale)` instead.
 */
public fun otlpScaleFor(relativeAccuracy: Double): Int {
    require(relativeAccuracy > 0.0 && relativeAccuracy < 1.0) {
        "relativeAccuracy must be in (0, 1) exclusive, was $relativeAccuracy"
    }
    val gamma = (1.0 + relativeAccuracy) / (1.0 - relativeAccuracy)
    val scale = (-log2(log2(gamma))).roundToInt()
    require(
        scale in MIN_OTLP_HISTOGRAM_SCALE..MAX_OTLP_HISTOGRAM_SCALE &&
            abs(alphaForOtlpScale(scale) - relativeAccuracy) <= relativeAccuracy * ALIGNMENT_TOLERANCE,
    ) {
        "relativeAccuracy $relativeAccuracy does not correspond to an integer OTLP scale; " +
            "build the DDSketch with alphaForOtlpScale(scale) (e.g. " +
            "alphaForOtlpScale($DEFAULT_OTLP_HISTOGRAM_SCALE) ≈ " +
            "${alphaForOtlpScale(DEFAULT_OTLP_HISTOGRAM_SCALE)} for ~1% accuracy)"
    }
    return scale
}

/** Relative tolerance for α↔scale round-trips — generous for FP noise, far below the ~2× gap between adjacent scales. */
private const val ALIGNMENT_TOLERANCE: Double = 1e-9

/**
 * Render this sketch as an OTLP `ExponentialHistogramDataPoint`-shaped [MetricPoint].
 *
 * The translation, field by field:
 *  - `scale` — derived from α via [otlpScaleFor] (throws if not OTLP-aligned).
 *  - `zeroCount` / `zeroThreshold` — [DDSketch.zeroCount] / [DDSketch.minIndexedValue]
 *    (identical semantics: magnitudes below the threshold count as zeros).
 *  - `positive` / `negative` buckets — the sparse per-sign maps densified into
 *    `offset + counts[]`, with **OTLP index = DDSketch index − 1**: DDSketch bucket `i`
 *    covers `(γ^(i−1), γ^i]`, OTLP bucket `j` covers `(base^j, base^(j+1)]`, and with
 *    `γ = base` those coincide at `j = i − 1`. Both conventions are upper-inclusive, so
 *    the re-index is exact.
 *  - `count` — total recorded values including zeros (buckets + zeroCount sum to it).
 *  - `sum`/`min`/`max` — omitted; a DDSketch does not track them (optional in OTLP).
 */
internal fun DDSketch.toExponentialHistogramPoint(
    key: MetricKey,
    startEpochNanos: Long,
    timeEpochNanos: Long,
): MetricPoint.ExponentialHistogram {
    val (positiveOffset, positiveCounts) = denseOtlpBuckets(positiveBuckets)
    val (negativeOffset, negativeCounts) = denseOtlpBuckets(negativeBuckets)
    return MetricPoint.ExponentialHistogram(
        key = key,
        scale = otlpScaleFor(relativeAccuracy),
        count = count,
        zeroCount = zeroCount,
        zeroThreshold = minIndexedValue,
        positiveOffset = positiveOffset,
        positiveBucketCounts = positiveCounts,
        negativeOffset = negativeOffset,
        negativeBucketCounts = negativeCounts,
        startEpochNanos = startEpochNanos,
        timeEpochNanos = timeEpochNanos,
    )
}

/**
 * Densify a sparse DDSketch bucket map into the OTLP `offset + bucket_counts[]` layout.
 * Gaps between the lowest and highest touched bucket are zero-filled; the span is bounded
 * by the sketch's indexable range (≈3.1k buckets per sign at the defaults).
 */
private fun denseOtlpBuckets(sparse: Map<Int, Long>): Pair<Int, List<Long>> {
    if (sparse.isEmpty()) return 0 to emptyList()
    val minIndex = sparse.keys.min()
    val maxIndex = sparse.keys.max()
    val counts = LongArray(maxIndex - minIndex + 1)
    for ((index, c) in sparse) counts[index - minIndex] = c
    // OTLP index = DDSketch index − 1 (see the file header): the array starts at the
    // DDSketch minIndex, whose OTLP index is minIndex − 1.
    return (minIndex - 1) to counts.toList()
}
