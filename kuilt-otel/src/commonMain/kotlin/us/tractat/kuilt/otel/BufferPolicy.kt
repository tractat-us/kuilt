package us.tractat.kuilt.otel

/**
 * Controls what happens when the in-memory CRDT buffer reaches [maxSpans].
 *
 * ## Honest limits
 *
 * An offline-forever device cannot buffer forever. The degradation is intentionally
 * **asymmetric and accounted for** — never silent:
 * - Metrics compress losslessly (a counter is O(1) regardless of how many increments
 *   happen offline). Buffer caps only apply to spans and logs.
 * - When a cap is hit, the [BufferPolicy] decides what gives way, and the loss is
 *   always visible. **How** it is made visible differs by exporter, because the two
 *   run at very different rates:
 *     - [WarpSpanExporter] logs **each** drop, with the victim's [SpanRecord.traceId]
 *       and [SpanRecord.spanId], so an operator can correlate against their backend's
 *       orphan-span index.
 *     - [WarpLogRecordExporter] **counts** each drop exactly, on
 *       [ExporterHealth.dropped] / [ExporterHealth.refused], and emits one
 *       rate-limited summary line rather than one line per record. At
 *       [DEFAULT_MAX_LOG_RECORDS] its buffer is full permanently, so every exported
 *       record evicts one — a per-record line there narrates a ring buffer doing
 *       exactly what it is configured to do, on the export hot path (#2218). Per-record
 *       correlation was given up deliberately; the running total was not.
 *
 * The [DROP_OLDEST] strategy is usually right: oldest spans are already "done" and
 * are the least likely to complete a trace being actively sampled right now.
 */
public enum class BufferPolicy {
    /**
     * Drop the oldest buffered entry when the buffer is full.
     *
     * Accounted for either way, by the exporter's own means — [WarpSpanExporter] logs each
     * drop, [WarpLogRecordExporter] counts it on [ExporterHealth.dropped]. See the enum KDoc.
     */
    DROP_OLDEST,

    /**
     * Drop the newest entry when the buffer is full.
     *
     * Accounted for either way — [WarpSpanExporter] logs each drop,
     * [WarpLogRecordExporter] counts refusals on [ExporterHealth.refused]. See the enum KDoc.
     *
     * "Newest" is resolved per exporter, and the two shipped exporters resolve it
     * differently: [WarpSpanExporter] evicts the newest *buffered* span and admits the
     * arrival; [WarpLogRecordExporter] refuses the arrival, which at a full buffer is
     * the newest record there is. See each exporter's KDoc.
     *
     * **Neither policy is the thing that bounds the total, and choosing this one does not
     * exempt a replica from the other half.** Refusing the arrival means a saturated
     * [WarpLogRecordExporter] appends no op on the export path — but
     * [WarpLogRecordExporter.merge] folds a peer's log in wholesale whatever the policy, and
     * what a peer wrote cannot fold into this replica's compaction floor. So a gossiping
     * replica still accumulates a small suppression record per foreign record windowed away,
     * under either policy. See [WarpLogRecordExporter]'s KDoc for the two-arm shape.
     */
    DROP_NEWEST,
}

/** Maximum number of [SpanRecord]s buffered in memory before eviction. */
public const val DEFAULT_MAX_SPANS: Int = 10_000
