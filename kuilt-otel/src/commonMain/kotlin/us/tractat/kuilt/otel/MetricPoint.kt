package us.tractat.kuilt.otel

/**
 * A single metric data point, rendered from a [WarpMetricExporter] CRDT (via
 * [MetricCatalog]) into the shape OTLP puts on the wire. The bridge produces these;
 * an [OtlpEdge] serializes them — the edge never reaches into the CRDTs directly.
 *
 * [valueHash] is over the **OTLP value fields only** (not the observation time), so a
 * re-render of an unchanged series produces the same hash and is skipped by
 * [MetricDigest] — while a value that advanced re-sends exactly once.
 *
 * The four variants mirror the four kinds a [MetricCatalog] holds: a long cumulative
 * sum ([Sum]), a double-precision cumulative sum ([DoubleSum]), a last-writer-wins
 * gauge ([Gauge]), and a distinct-count estimate ([Cardinality]).
 */
public sealed interface MetricPoint {
    /** The series this point belongs to. */
    public val key: MetricKey

    /** A stable hash of the OTLP value fields, used for by-digest dedup. */
    public fun valueHash(): Long

    /** A long cumulative monotonic total (OTLP Sum, cumulative temporality). */
    public data class Sum(
        override val key: MetricKey,
        public val value: Long,
        public val startEpochNanos: Long,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        // Hash the cumulative total only. start is fixed per series; time is the
        // observation instant and must not force a re-send when the total is stable.
        override fun valueHash(): Long = value
    }

    /** A double-precision cumulative monotonic total (OTLP Sum with `asDouble`). */
    public data class DoubleSum(
        override val key: MetricKey,
        public val value: Double,
        public val startEpochNanos: Long,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        override fun valueHash(): Long = value.toRawBits()
    }

    /**
     * A last-writer-wins snapshot (OTLP Gauge).
     *
     * ## Value-return caveat (#1053)
     *
     * [valueHash] is over the gauge value only, matching the LWW-snapshot semantics of
     * the backing [us.tractat.kuilt.crdt.LWWRegister]. A value that returns to a
     * previously-sent reading (e.g. `0.5 → 0.6 → 0.5`) hashes back to the earlier
     * digest entry, so the by-digest reconciliation **skips** the re-send and the
     * collector keeps the observation timestamp of the *first* `0.5`. This is
     * intentional: a gauge reports a current level, not a history, so re-observing the
     * same level carries no new value. Consumers that need the latest observation
     * instant for an unchanged value should read the gauge's own timestamp rather than
     * relying on a re-send.
     */
    public data class Gauge(
        override val key: MetricKey,
        public val value: Double,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        override fun valueHash(): Long = value.toRawBits()
    }

    /** A distinct-count estimate rendered as a Gauge-shaped OTLP point. */
    public data class Cardinality(
        override val key: MetricKey,
        public val estimate: Long,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        override fun valueHash(): Long = estimate
    }
}
