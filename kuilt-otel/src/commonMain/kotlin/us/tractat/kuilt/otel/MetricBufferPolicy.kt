package us.tractat.kuilt.otel

/**
 * Controls what happens when the number of distinct metric series in
 * [WarpMetricExporter] reaches [maxMetrics].
 *
 * Unlike span buffers, a metric's accumulated value does not grow with the
 * number of events — a counter recording a million increments still takes O(1)
 * space. Buffer caps therefore apply to the number of **distinct metric series**
 * (unique [MetricKey]s), not to the number of events. A cap is only needed when
 * code creates an unbounded fan-out of label combinations (a label cardinality
 * explosion).
 *
 * Every eviction is **logged** with the evicted key, so an operator can tell
 * which series were dropped.
 */
public enum class MetricBufferPolicy {
    /**
     * Drop the metric series that was last written earliest (by insertion order).
     *
     * Prefer this in most cases: stale series are least likely to be actively
     * queried. **Logs each drop.**
     */
    DROP_OLDEST,

    /**
     * Drop the metric series that was last written most recently.
     *
     * Unusual but available for completeness. **Logs each drop.**
     */
    DROP_NEWEST,
}

/** Default maximum number of distinct metric series buffered in memory. */
public const val DEFAULT_MAX_METRICS: Int = 10_000
