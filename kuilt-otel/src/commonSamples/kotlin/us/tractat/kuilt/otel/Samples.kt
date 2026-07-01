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
internal suspend fun sampleWarpOtlpBridge() {
    val telemetry = WarpTelemetry(
        replica = us.tractat.kuilt.crdt.ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )
    telemetry.recover()

    // Export spans while possibly offline — export() succeeds on durable local write.
    val span = SpanRecord(
        traceId = kotlinx.io.bytestring.ByteString(ByteArray(16) { it.toByte() }),
        spanId = kotlinx.io.bytestring.ByteString(ByteArray(8) { it.toByte() }),
        parentSpanId = null,
        name = "checkout",
        kind = SpanKind.CLIENT,
        startEpochNanos = 1_000_000_000L,
        endEpochNanos = 2_000_000_000L,
    )
    telemetry.spans.export(span)

    // When connectivity returns, drain to the backend. WarpOtlpBridge reconciles each
    // signal (spans, logs, metrics) by digest: only records the edge doesn't have are
    // sent, and a resend on reconnect cannot double-count. Spans also carry inferred
    // causal links (#846). The clock stamps metric observation time.
    val bridge = WarpOtlpBridge(telemetry, kotlin.time.Clock.System)

    // Wire your OtlpEdge implementation and call drain whenever connectivity returns.
    // DrainResult.Success(spansSent, logsSent, metricPointsSent) — 0s mean up to date.
    // DrainResult.Failure(cause)         — every attempted signal failed; retry later.
    //
    // val edge: OtlpEdge = MyKtorOtlpEdge(endpoint = "https://otel-collector.example.com")
    // val result: DrainResult = bridge.drain(edge)
    println("bridge ready: $bridge")
}

/** @suppress — sample only */
internal suspend fun sampleOtlpEdge() {
    // OtlpEdge is the interface you implement to point the bridge at your backend.
    // The bridge calls digest() to learn what the edge already has, then send() for the delta.
    //
    // Example skeleton — replace with real Ktor HTTP or gRPC:
    //
    // class KtorOtlpEdge(private val endpoint: String) : OtlpEdge {
    //     override suspend fun digest(): SpanDigest {
    //         val ids = httpClient.get("$endpoint/v1/traces/digest").body<Set<ByteString>>()
    //         return SpanDigest(ids)
    //     }
    //     override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
    //         httpClient.post("$endpoint/v1/traces") { setBody(spans.toOtlpJson(links)) }
    //     }
    // }
}

/** @suppress — sample only */
internal suspend fun sampleWarpMetricExporter() {
    val replica = us.tractat.kuilt.crdt.ReplicaId("device-uuid-abc123")
    val exporter = WarpMetricExporter(replica = replica, store = InMemoryDurableStore())

    // Recover persisted metrics from a previous session.
    exporter.recover()

    // Sum: count server requests — idempotent merge means no double-count under retry.
    val requests = MetricKey("server.requests", MetricKind.SUM, mapOf("handler" to "/api/v1"))
    exporter.incrementSum(requests, by = 1L)
    check(exporter.sumValue(requests) == 1L)

    // Gauge: snapshot the current CPU load — last writer (by timestamp) wins across replicas.
    val cpu = MetricKey("cpu.usage", MetricKind.GAUGE)
    exporter.setGauge(cpu, value = 0.72, timestamp = 1_000_000L)
    check(exporter.gaugeValue(cpu) == 0.72)

    // Cardinality: count distinct users — same user added twice is still 1.
    val users = MetricKey("unique.users", MetricKind.CARDINALITY)
    exporter.addCardinality(users, "user-abc")
    exporter.addCardinality(users, "user-abc") // idempotent — no double-count
    check(exporter.cardinalityEstimate(users) > 0L)
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

/** @suppress — sample only */
internal fun sampleInferCausalLinks() {
    val clock = WarpCausalClock(ReplicaId("device-uuid-abc123"))

    // One tick per span, in creation order — the clock chains the happens-before frontier.
    val checkout = clock.tick()   // root span of one trace
    val charge = clock.tick()     // caused by checkout, but in a *different* trace

    fun span(id: Byte, trace: Byte, parent: Byte?, stamp: CausalStamp) = SpanRecord(
        traceId = ByteString(ByteArray(16) { trace }),
        spanId = ByteString(ByteArray(8) { id }),
        parentSpanId = parent?.let { p -> ByteString(ByteArray(8) { p }) },
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000_000_000L,
        endEpochNanos = 2_000_000_000L,
        causalStamp = stamp,
    )

    val checkoutSpan = span(id = 1, trace = 1, parent = null, stamp = checkout)
    val chargeSpan = span(id = 2, trace = 2, parent = null, stamp = charge)

    // The cross-trace edge explicit context propagation would have lost.
    val links = inferCausalLinks(listOf(checkoutSpan, chargeSpan))
    check(links.size == 1)
    check(links.single().attributes["kuilt.causality"] == "potential")
}
