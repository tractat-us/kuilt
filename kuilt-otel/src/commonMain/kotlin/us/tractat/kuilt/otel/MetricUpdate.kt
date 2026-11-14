package us.tractat.kuilt.otel

/**
 * One local change to a [WarpMetricExporter] series, as a value — the element of a batch handed to
 * [WarpMetricExporter.record].
 *
 * Each variant is one of the exporter's single-point mutators spelled as data: [IncrementSum] is
 * [WarpMetricExporter.incrementSum], [SetGauge] is [WarpMetricExporter.setGauge], and so on. Applying
 * a list of them through [WarpMetricExporter.record] leaves the exporter in the same state as calling
 * those mutators one by one, in order; what differs is how many durable writes that costs.
 *
 * Each variant rejects at construction the arguments its CRDT would reject when applied. What a
 * variant cannot know until it meets an exporter — [AddExponentialHistogram]'s scale against the
 * exporter's [WarpMetricExporter.histogramScale] — [WarpMetricExporter.record] checks for every
 * update before it applies any. Either way nothing in a batch can throw half-way through: a batch
 * is applied whole, or refused whole with nothing applied.
 *
 * Remote snapshot merges (`mergeSum`, `mergeGauge`, …) are not updates — they are joins of another
 * replica's state, and stay single calls.
 */
public sealed interface MetricUpdate {
    /** The series this update changes. */
    public val key: MetricKey

    /**
     * Add [by] to the integer sum for [key] — [WarpMetricExporter.incrementSum].
     *
     * @throws IllegalArgumentException if [by] is less than 1; a grow-only counter takes no zero or
     *   negative increment.
     */
    public data class IncrementSum(override val key: MetricKey, public val by: Long = 1L) : MetricUpdate {
        init {
            require(by >= 1L) { "IncrementSum.by must be positive, was $by" }
        }
    }

    /**
     * Add [by] to the exact-precision sum for [key] — [WarpMetricExporter.incrementSumDouble].
     *
     * @throws IllegalArgumentException if [by] is not greater than zero (NaN included).
     */
    public data class IncrementSumDouble(override val key: MetricKey, public val by: Double) : MetricUpdate {
        init {
            require(by > 0.0) { "IncrementSumDouble.by must be positive, was $by" }
        }
    }

    /** Set the gauge for [key] to [value] at [timestamp] — [WarpMetricExporter.setGauge]. */
    public data class SetGauge(
        override val key: MetricKey,
        public val value: Double,
        public val timestamp: Long,
    ) : MetricUpdate

    /** Add [element] to the distinct-count sketch for [key] — [WarpMetricExporter.addCardinality]. */
    public data class AddCardinality(override val key: MetricKey, public val element: String) : MetricUpdate

    /**
     * Record one measurement into the histogram for [key] — [WarpMetricExporter.recordHistogram].
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public data class RecordHistogram(override val key: MetricKey, public val value: Double) : MetricUpdate {
        init {
            require(value.isFinite()) { "RecordHistogram.value must be finite, was $value" }
        }
    }

    /**
     * Add one interval's **already-bucketed** measurements to the histogram for [key], in the shape
     * of an OTLP `ExponentialHistogramDataPoint` — what an OpenTelemetry SDK exports for an
     * exponential histogram under delta temporality.
     *
     * OTLP bucket `offset + i` at [scale] covers `(base^(offset+i), base^(offset+i+1)]` with
     * `base = 2^(2^−scale)`, and holds `counts[i]` values; [zeroCount] values were zero. Negative
     * buckets index the magnitude, mirrored. The counts are **added** to the series, the weighted
     * form of [RecordHistogram], so applying the same update twice counts it twice, as with
     * [IncrementSum].
     *
     * The exporter keeps one scale, [WarpMetricExporter.histogramScale]. A [scale] at or above it
     * maps exactly: equal re-indexes, finer merges `2^(scale − histogramScale)` buckets into one. A
     * [scale] below it cannot be split without losing the sketch's accuracy guarantee, so
     * [WarpMetricExporter.record] refuses the whole batch that holds one, applying nothing. That
     * check needs the exporter, so it is not made here.
     *
     * An OTLP point's `sum`, `min`, `max` and exemplars have no field here: a DDSketch does not
     * keep them.
     *
     * @throws IllegalArgumentException if [zeroCount] or any bucket count is negative, or [scale]
     *   is outside the OTLP data model's `[-10, 20]`.
     */
    public data class AddExponentialHistogram(
        override val key: MetricKey,
        public val scale: Int,
        public val zeroCount: Long,
        public val positiveOffset: Int,
        public val positiveCounts: List<Long>,
        public val negativeOffset: Int,
        public val negativeCounts: List<Long>,
    ) : MetricUpdate {
        init {
            require(scale in OTLP_DATA_MODEL_MIN_SCALE..MAX_OTLP_HISTOGRAM_SCALE) {
                "AddExponentialHistogram.scale must be in [$OTLP_DATA_MODEL_MIN_SCALE, $MAX_OTLP_HISTOGRAM_SCALE], was $scale"
            }
            require(zeroCount >= 0L) { "AddExponentialHistogram.zeroCount must not be negative, was $zeroCount" }
            require(positiveCounts.all { it >= 0L }) { "AddExponentialHistogram.positiveCounts must not be negative" }
            require(negativeCounts.all { it >= 0L }) { "AddExponentialHistogram.negativeCounts must not be negative" }
        }
    }
}

/** The lowest scale the OTLP data model permits; [MIN_OTLP_HISTOGRAM_SCALE] is the lowest kuilt can store. */
private const val OTLP_DATA_MODEL_MIN_SCALE: Int = -10
