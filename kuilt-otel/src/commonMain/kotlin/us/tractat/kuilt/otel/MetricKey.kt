package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable

/**
 * The identity of a single metric time series.
 *
 * Metrics with the same [name] and [kind] but different [attributes] are distinct
 * series — this is the standard OpenTelemetry label-set model. [MetricKey] is the
 * map key inside [WarpMetricExporter]; use the same key on every increment/set/add
 * to accumulate into the same series.
 *
 * @param name Human-readable metric name. Must not be blank.
 * @param kind The measurement kind: [MetricKind.SUM], [MetricKind.GAUGE], or
 *   [MetricKind.CARDINALITY].
 * @param attributes Key/value label set that differentiates series within the same
 *   metric name (e.g. `{"path" to "/api/v1"}`). Empty means no labels.
 */
@Serializable
public data class MetricKey(
    public val name: String,
    public val kind: MetricKind,
    public val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "MetricKey.name must not be blank" }
    }
}

/** The kind of measurement a metric tracks. */
@Serializable
public enum class MetricKind {
    /**
     * A monotonically increasing cumulative counter (e.g. request count).
     *
     * Backed by a [us.tractat.kuilt.crdt.GCounter]. The value only goes up;
     * merging is always safe — two replicas that each saw 5 events report
     * 10 total after merge.
     */
    SUM,

    /**
     * A last-writer-wins snapshot of a current value (e.g. CPU usage, temperature).
     *
     * Backed by a [us.tractat.kuilt.crdt.LWWRegister]`<Double>`. The most recent
     * write (by timestamp, with replica-id tiebreak) wins across all replicas.
     */
    GAUGE,

    /**
     * An approximate count of distinct elements (e.g. unique users, unique sessions).
     *
     * Backed by a [us.tractat.kuilt.crdt.HyperLogLog]. The estimate is accurate
     * to ~0.81% at the default precision; two replicas merge by element-wise max
     * of the register arrays, so duplicates across replicas are automatically
     * deduplicated.
     */
    CARDINALITY,
}
