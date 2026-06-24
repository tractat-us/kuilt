package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.ReplicaId

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
 * the network is available.
 *
 * ## Honest limits
 *
 * - **Metrics (A3) exporter** is deferred to a follow-up PR.
 *   See issues filed against #723. Its slot is reserved here so the API shape
 *   is stable, but it is `TODO`-stubbed.
 * - **[WarpOtlpBridge] (A5)** is deferred; a follow-up PR wires the edge drain.
 * - **Platform WALs** for iOS/macOS (#724) and wasmJs/IndexedDB (#725) are
 *   deferred; pass [InMemoryDurableStore] until those land.
 *
 * @param replica Stable, unique identity for this device/process (use a UUID).
 * @param store Durable persistence backend. [InMemoryDurableStore] in tests;
 *   a platform-specific WAL in production.
 * @param maxSpans Maximum number of spans buffered in memory.
 * @param maxLogRecords Maximum number of log records buffered in memory.
 * @param bufferPolicy Eviction strategy when [maxSpans] or [maxLogRecords] is exceeded.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpTelemetry
 */
public class WarpTelemetry(
    replica: ReplicaId,
    store: DurableStore,
    maxSpans: Int = DEFAULT_MAX_SPANS,
    maxLogRecords: Int = DEFAULT_MAX_LOG_RECORDS,
    bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
) {
    /** The span exporter (A2). Export spans here; they are CRDT-merged on reconnect. */
    public val spans: WarpSpanExporter = WarpSpanExporter(
        replica = replica,
        store = store,
        maxSpans = maxSpans,
        bufferPolicy = bufferPolicy,
    )

    // A3: MetricExporter — deferred to follow-up PR.
    // Wire cumulative GCounter/PNCounter + HyperLogLog for unique-cardinality metrics.
    // See https://github.com/tractat-us/kuilt/issues/723 (sub-issue TBD).

    /** The log-record exporter (A4). Export log records here; they are CRDT-merged on reconnect. */
    public val logs: WarpLogRecordExporter = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxLogRecords,
        bufferPolicy = bufferPolicy,
    )

    /**
     * Load persisted CRDT state from the [DurableStore].
     *
     * Call once at startup, before any calls to [spans.export][WarpSpanExporter.export]
     * or [logs.export][WarpLogRecordExporter.export].
     * Idempotent: a second call simply re-reads and re-decodes the same bytes.
     */
    public suspend fun recover() {
        spans.recover()
        logs.recover()
    }
}
