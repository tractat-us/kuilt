package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.ReplicaId

/** @suppress — sample only */
internal suspend fun sampleWarpTelemetry() {
    val telemetry = WarpTelemetry(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )

    // Load any spans buffered during a previous session.
    telemetry.recover()

    // export() returns the moment the span is durably written locally —
    // not when it reaches a backend. Delivery is the fabric's job.
    val span = SpanRecord(
        traceId = "0af7651916cd43dd8448eb211c80319c",
        spanId = "b7ad6b7169203331",
        parentSpanId = null,
        name = "purchase",
        kind = SpanKind.SERVER,
        startEpochNanos = 1_000_000_000L,
        endEpochNanos = 1_500_000_000L,
        attributes = mapOf("item.id" to "widget-42"),
        status = SpanStatus.Ok,
    )

    val result = telemetry.spans.export(span)
    check(result == ExportResult.Success) { "export failed: $result" }
}

/** @suppress — sample only */
internal suspend fun sampleWarpSpanExporter() {
    val replica = ReplicaId("device-uuid-abc123")
    val store = InMemoryDurableStore()
    val exporter = WarpSpanExporter(replica = replica, store = store)

    // Recover persisted state from a previous session.
    exporter.recover()

    val span = SpanRecord(
        traceId = "0af7651916cd43dd8448eb211c80319c",
        spanId = "b7ad6b7169203331",
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
