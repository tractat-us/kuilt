package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId

/** @suppress — sample only */
internal suspend fun sampleWarpLogRecordExporter() {
    val replica = ReplicaId("device-uuid-abc123")
    val store = InMemoryDurableStore()
    val exporter = WarpLogRecordExporter(replica = replica, store = store)

    // Recover persisted state from a previous session (rebuilds dedup map too).
    exporter.recover()

    // recordId is an 8-byte caller-assigned identifier unique per record.
    val record = LogRecord(
        recordId = ByteString(ByteArray(8) { it.toByte() }),
        body = "user checked out",
        severityNumber = 9, // INFO
        severityText = "INFO",
        observedEpochNanos = 1_000_000_000L,
        traceId = ByteString(ByteArray(16) { it.toByte() }),
        spanId = ByteString(ByteArray(8) { it.toByte() }),
        attributes = mapOf("user.id" to "42"),
    )

    // Idempotent: re-exporting the same recordId is a no-op (Rga set-union).
    exporter.export(record)
    val secondResult = exporter.export(record)
    check(secondResult == ExportResult.Success)
    check(exporter.snapshot().toList().size == 1) { "duplicate was stored" }
}

/** @suppress — sample only */
internal suspend fun sampleWarpTelemetry() {
    val telemetry = WarpTelemetry(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )

    // Load any spans and log records buffered during a previous session.
    telemetry.recover()

    // Span ids are raw bytes (OTLP wire format): 16 bytes for trace id, 8 for span id.
    val span = SpanRecord(
        traceId = ByteString(ByteArray(16) { it.toByte() }),
        spanId = ByteString(ByteArray(8) { it.toByte() }),
        parentSpanId = null,
        name = "purchase",
        kind = SpanKind.SERVER,
        startEpochNanos = 1_000_000_000L,
        endEpochNanos = 1_500_000_000L,
        attributes = mapOf("item.id" to "widget-42"),
        status = SpanStatus.Ok,
    )

    // export() returns the moment the data is durably written locally —
    // not when it reaches a backend. Delivery is the fabric's job.
    val spanResult = telemetry.spans.export(span)
    check(spanResult == ExportResult.Success) { "export failed: $spanResult" }

    val logRecord = LogRecord(
        recordId = ByteString(ByteArray(8) { it.toByte() }),
        body = "purchase completed",
        severityNumber = 9, // INFO
        traceId = ByteString(ByteArray(16) { it.toByte() }),
        spanId = ByteString(ByteArray(8) { it.toByte() }),
    )
    val logResult = telemetry.logs.export(logRecord)
    check(logResult == ExportResult.Success) { "log export failed: $logResult" }
}

/** @suppress — sample only */
internal suspend fun sampleWarpSpanExporter() {
    val replica = ReplicaId("device-uuid-abc123")
    val store = InMemoryDurableStore()
    val exporter = WarpSpanExporter(replica = replica, store = store)

    // Recover persisted state from a previous session.
    exporter.recover()

    // Span ids are raw bytes (OTLP wire format): 16 bytes for trace id, 8 for span id.
    val span = SpanRecord(
        traceId = ByteString(ByteArray(16) { it.toByte() }),
        spanId = ByteString(ByteArray(8) { it.toByte() }),
        parentSpanId = null,
        name = "checkout",
        kind = SpanKind.CLIENT,
        startEpochNanos = 1_000_000_000L,
        endEpochNanos = 2_000_000_000L,
    )

    // Idempotent: exporting the same span twice is a no-op (ORSet union).
    exporter.export(span)
    val secondResult = exporter.export(span)
    check(secondResult == ExportResult.Success)
    check(exporter.snapshot().elements.size == 1) { "duplicate was stored" }
}
