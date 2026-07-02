@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.protobuf.ProtoBuf
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OTLP-protobuf wire-fidelity — runs on **every shipped target** (JVM, Kotlin/Native,
 * wasmJs), because that cross-target proof is the whole risk of #1040. Two kinds of check:
 *
 *  1. **Golden byte-vector** — a minimal traces request is encoded and asserted equal to a
 *     hand-verified hex string. The bytes were verified field-by-field against the OTLP
 *     proto3 schema (field numbers, wire types, bytes ids, fixed64 nanos, nested lengths);
 *     see the comment on the constant. This pins the exact wire against accidental drift.
 *  2. **Round-trip + structural** — encode → decode with the same DTOs proves repeated
 *     fields, `oneof` branches, optional presence, and raw-`bytes` ids all survive.
 */
class OtlpProtoEncodingTest {
    private val proto = ProtoBuf
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })

    private fun ByteArray.toHex(): String = joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v ushr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    // Hand-verified against opentelemetry-proto trace.proto / common.proto. Layout:
    //   0a 38                    ExportTraceServiceRequest.resource_spans (f1, len 56)
    //     12 36                  ResourceSpans.scope_spans (f2, len 54)
    //       12 34                ScopeSpans.spans (f2, len 52)
    //         0a 10 <16×0a>      Span.trace_id  (f1, bytes[16])
    //         12 08 <8×0b>       Span.span_id   (f2, bytes[8])
    //         2a 02 6f 70        Span.name      (f5, "op")
    //         30 02              Span.kind      (f6, varint 2 = SERVER)
    //         39 <fixed64 5>     Span.start_time_unix_nano (f7, fixed64)
    //         41 <fixed64 9>     Span.end_time_unix_nano   (f8, fixed64)
    // parent_span_id (f4), attributes (f9), links (f13) are absent (root, no attrs/links).
    private val goldenSpanHex =
        "0a38" + "1236" + "1234" +
            "0a10" + "0a".repeat(16) +
            "1208" + "0b".repeat(8) +
            "2a02" + "6f70" +
            "3002" +
            "39" + "0500000000000000" +
            "41" + "0900000000000000"

    @Test
    fun minimalTracesRequestMatchesGoldenBytes() {
        val span = SpanRecord(
            traceId = tId(0x0a), spanId = sId(0x0b), parentSpanId = null,
            name = "op", kind = SpanKind.SERVER, startEpochNanos = 5L, endEpochNanos = 9L,
        )
        val bytes = proto.encodeToByteArray(ProtoTracesRequest.serializer(), tracesProtoOf(setOf(span), emptyList()))
        assertEquals(goldenSpanHex, bytes.toHex())
    }

    @Test
    fun spanWithLinkRoundTrips() {
        val span = SpanRecord(
            traceId = tId(0x0a), spanId = sId(0x0b), parentSpanId = sId(0x01),
            name = "op", kind = SpanKind.CLIENT, startEpochNanos = 5L, endEpochNanos = 9L,
            attributes = mapOf("http.method" to "GET"),
        )
        val link = SpanLink(fromSpanId = sId(0x0b), linkedTraceId = tId(0x0c), linkedSpanId = sId(0x0d))
        val req = tracesProtoOf(setOf(span), listOf(link))
        val decoded = proto.decodeFromByteArray(
            ProtoTracesRequest.serializer(),
            proto.encodeToByteArray(ProtoTracesRequest.serializer(), req),
        )
        val s = decoded.resourceSpans.single().scopeSpans.single().spans.single()
        assertAll(
            { assertEquals(tId(0x0a).let { b -> ByteArray(16) { b[it] } }.toList(), s.traceId.toList()) },
            { assertEquals(8, s.spanId.size) },
            { assertEquals(3, s.kind) }, // CLIENT
            { assertEquals(5L, s.startTimeUnixNano) },
            { assertEquals("http.method", s.attributes.single().key) },
            { assertEquals("GET", s.attributes.single().value.stringValue) },
            { assertEquals(sId(0x0d).let { b -> ByteArray(8) { b[it] } }.toList(), s.links.single().spanId.toList()) },
            { assertEquals("potential", s.links.single().attributes.single { it.key == "kuilt.causality" }.value.stringValue) },
        )
    }

    @Test
    fun logRecordRoundTripsWithTraceCorrelation() {
        val rec = LogRecord(
            recordId = sId(1), body = "hello", severityNumber = 9, severityText = "INFO",
            timestampEpochNanos = 42L, traceId = tId(0x0a), spanId = sId(0x0b),
        )
        val decoded = proto.decodeFromByteArray(
            ProtoLogsRequest.serializer(),
            proto.encodeToByteArray(ProtoLogsRequest.serializer(), logsProtoOf(setOf(rec))),
        )
        val r = decoded.resourceLogs.single().scopeLogs.single().logRecords.single()
        assertAll(
            { assertEquals("hello", r.body?.stringValue) },
            { assertEquals(9, r.severityNumber) },
            { assertEquals("INFO", r.severityText) },
            { assertEquals(42L, r.timeUnixNano) },
            { assertEquals(16, r.traceId?.size) },
            { assertEquals(8, r.spanId?.size) },
        )
    }

    @Test
    fun sumRoundTripsWithTemporalityAndOneofAsInt() {
        val p = MetricPoint.Sum(MetricKey("req", MetricKind.SUM), value = 7L, startEpochNanos = 0L, timeEpochNanos = 5L)
        val decoded = proto.decodeFromByteArray(
            ProtoMetricsRequest.serializer(),
            proto.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(setOf(p))),
        )
        val m = decoded.resourceMetrics.single().scopeMetrics.single().metrics.single()
        assertAll(
            { assertEquals("req", m.name) },
            { assertTrue(m.sum != null, "Sum data oneof branch must be set") },
            { assertTrue(m.gauge == null, "gauge oneof branch must be unset for a Sum") },
            { assertEquals(AGGREGATION_TEMPORALITY_CUMULATIVE, m.sum?.aggregationTemporality) },
            { assertEquals(true, m.sum?.isMonotonic) },
            { assertEquals(7L, m.sum?.dataPoints?.single()?.asInt) },
            { assertEquals(null, m.sum?.dataPoints?.single()?.asDouble) },
        )
    }

    @Test
    fun gaugeAndCardinalityRoundTripAsGaugeOneof() {
        val g = MetricPoint.Gauge(MetricKey("cpu", MetricKind.GAUGE), value = 0.5, timeEpochNanos = 5L)
        val gm = proto.decodeFromByteArray(
            ProtoMetricsRequest.serializer(),
            proto.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(setOf(g))),
        ).resourceMetrics.single().scopeMetrics.single().metrics.single()

        val c = MetricPoint.Cardinality(MetricKey("users", MetricKind.CARDINALITY), estimate = 12L, timeEpochNanos = 5L)
        val cm = proto.decodeFromByteArray(
            ProtoMetricsRequest.serializer(),
            proto.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(setOf(c))),
        ).resourceMetrics.single().scopeMetrics.single().metrics.single()

        assertAll(
            { assertTrue(gm.gauge != null && gm.sum == null, "gauge must use the gauge oneof branch") },
            { assertEquals(0.5, gm.gauge?.dataPoints?.single()?.asDouble) },
            { assertTrue(cm.gauge != null && cm.sum == null, "cardinality renders as a Gauge") },
            { assertEquals(12L, cm.gauge?.dataPoints?.single()?.asInt) },
        )
    }
}
