package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore

/**
 * A CRDT-backed, offline-first telemetry surface for Kotlin Multiplatform.
 *
 * `WarpTelemetry` owns the three signal exporters: spans, metrics, and logs.
 * Each exporter writes to a [DurableStore] and holds its data as a CRDT — so
 * offline buffering, eventual delivery, and idempotent merge are structural
 * properties, not retry logic layered on top.
 *
 * ## Binding surface (option a — direct OTLP)
 *
 * kuilt-otel targets the OTLP wire format directly rather than wrapping the JVM
 * OTel SDK. This gives full Kotlin Multiplatform reach: the same exporter runs on
 * JVM, Android, iOS, macOS, and wasmJs. The JVM OTel SDK path (option b) would
 * strand Native and wasm — the platforms where a KMP exporter is most valuable.
 *
 * ## Usage
 *
 * ```kotlin
 * val telemetry = WarpTelemetry(
 *     replica = ReplicaId("device-uuid-here"),
 *     store   = InMemoryDurableStore(),   // or a platform WAL in production
 * )
 * telemetry.recover()               // load persisted state from the store
 * telemetry.spans.export(span)      // export() returns on durable write
 * telemetry.logs.export(logRecord)  // same guarantee for log records
 * ```
 *
 * A [WarpOtlpBridge] drains the converged CRDTs to a real OTLP endpoint whenever
 * the network is available. Wire it with an [OtlpEdge] implementation and call
 * [WarpOtlpBridge.drain] on each reconnect — it reconciles by digest and sends
 * only the spans the edge does not yet have.
 *
 * ## Honest limits
 *
 * - **Platform WALs** for iOS/macOS (#724) and wasmJs/IndexedDB (#725) are
 *   deferred; pass [InMemoryDurableStore] until those land.
 *
 * @param replica Stable, unique identity for this device/process (use a UUID).
 * @param store Durable persistence backend. [InMemoryDurableStore] in tests;
 *   a platform-specific WAL in production.
 * @param maxSpans Maximum number of spans buffered in memory.
 * @param maxLogRecords Maximum number of log records buffered in memory.
 * @param maxMetrics Maximum number of distinct metric series buffered in memory.
 * @param bufferPolicy Eviction strategy when [maxSpans] or [maxLogRecords] is exceeded.
 * @param metricBufferPolicy Eviction strategy when [maxMetrics] is exceeded.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpTelemetry
 */
public class WarpTelemetry(
    replica: ReplicaId,
    private val store: DurableStore,
    maxSpans: Int = DEFAULT_MAX_SPANS,
    maxLogRecords: Int = DEFAULT_MAX_LOG_RECORDS,
    bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    maxMetrics: Int = DEFAULT_MAX_METRICS,
    metricBufferPolicy: MetricBufferPolicy = MetricBufferPolicy.DROP_OLDEST,
) {
    // The causal clock is owned here and wired into `spans` so span export
    // auto-stamps causal context (#846) with no caller change. Recovered in
    // recover(); persisted by the span exporter on its durable export path.
    private val causalClock: WarpCausalClock = WarpCausalClock(replica)

    /**
     * The span exporter (A2). Export spans here; they are CRDT-merged on reconnect
     * and **auto-stamped** with causal context so cross-device happens-before links
     * form automatically (#846).
     */
    public val spans: WarpSpanExporter = WarpSpanExporter(
        replica = replica,
        store = store,
        maxSpans = maxSpans,
        bufferPolicy = bufferPolicy,
        causalClock = causalClock,
    )

    /**
     * The metric exporter (A3). Export cumulative sums, gauges, and cardinality estimates
     * here; they are CRDT-merged on reconnect with no double-counting.
     */
    public val metrics: WarpMetricExporter = WarpMetricExporter(
        replica = replica,
        store = store,
        maxMetrics = maxMetrics,
        bufferPolicy = metricBufferPolicy,
    )

    /** The log-record exporter (A4). Export log records here; they are CRDT-merged on reconnect. */
    public val logs: WarpLogRecordExporter = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxLogRecords,
        bufferPolicy = bufferPolicy,
    )

    /**
     * Load persisted CRDT state from the [DurableStore] for all exporters.
     *
     * Call once at startup, before any calls to [spans.export][WarpSpanExporter.export],
     * [metrics.incrementSum][WarpMetricExporter.incrementSum],
     * [logs.export][WarpLogRecordExporter.export] **or
     * [logs.merge][WarpLogRecordExporter.merge]**, or [WarpOtlpBridge.drain] — and never
     * concurrently with any of them.
     *
     * "Never concurrently" is not politeness. [WarpLogRecordExporter.recover] deliberately stays
     * outside that exporter's write mutex, because an un-recovered exporter's segment numbering
     * starts at its construction defaults: a [logs.merge][WarpLogRecordExporter.merge] racing this
     * allocates a segment number the persisted index already uses and overwrites a live key, in
     * either serialization order. A lock there would order the writes while suggesting a safety it
     * cannot deliver, so this contract is the safety.
     *
     * Idempotent: a second call simply re-reads and re-decodes the same bytes.
     */
    public suspend fun recover() {
        causalClock.recover(store)
        spans.recover()
        metrics.recover()
        logs.recover()
    }

    /**
     * Empty every signal's buffer and its persisted state — the supported reset (#2208).
     *
     * Callable on a live instance: no restart, no per-platform directory delete. The same
     * instance keeps exporting into the cleared store afterwards.
     *
     * **Best-effort across signals, and not atomic.** Every signal is attempted even if an
     * earlier one failed, and the first failure is returned — unlike [WarpOtlpBridge.drain],
     * which tolerates a partial success, because a half-cleared store is a result the caller
     * has to see rather than one to paper over.
     *
     * The three signals differ in what a clear guarantees against a peer, and the difference
     * is structural rather than an omission:
     *
     * - [logs] and [spans] **suppress** what they drop, so a peer holding the pre-clear ops
     *   cannot push them back through a merge.
     * - [metrics] can only forget **locally** — a monotonic join has no merge-safe forget, so a
     *   merge restores the old values. See [WarpMetricExporter.clear].
     *
     * The causal clock's frontier is emptied and its `seq` left alone — by
     * [WarpSpanExporter.clear], which owns the clock, so a caller reaching that exporter
     * directly gets the same treatment. This facade adds nothing there.
     *
     * @sample us.tractat.kuilt.otel.sampleWarpTelemetryClear
     */
    public suspend fun clear(): ExportResult {
        val logsResult = logs.clear()
        val spansResult = spans.clear()
        val metricsResult = metrics.clear()
        return listOf(logsResult, spansResult, metricsResult.asExportResult())
            .filterIsInstance<ExportResult.Failure>()
            .firstOrNull()
            ?: ExportResult.Success
    }

    /** Bridge the metric exporter's own result type into the one this facade reports. */
    private fun MetricExportResult.asExportResult(): ExportResult = when (this) {
        is MetricExportResult.Success -> ExportResult.Success
        is MetricExportResult.Failure -> ExportResult.Failure(cause)
    }
}
