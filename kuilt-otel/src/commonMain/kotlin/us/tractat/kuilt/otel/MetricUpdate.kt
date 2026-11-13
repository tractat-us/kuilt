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
 * Each variant rejects at construction the arguments its CRDT would reject when applied, so a batch
 * that was built is a batch that applies: nothing in it can throw half-way through.
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
}
