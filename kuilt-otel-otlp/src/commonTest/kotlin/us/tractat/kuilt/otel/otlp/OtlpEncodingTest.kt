package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import kotlin.test.Test
import kotlin.test.assertTrue

class OtlpEncodingTest {
    private val json = Json { encodeDefaults = false }
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })

    @Test
    fun spanEncodesHexIdsInt64StringsAndLinks() {
        val span = SpanRecord(
            traceId = tId(0x0a), spanId = sId(0x0b), parentSpanId = null,
            name = "op", kind = SpanKind.SERVER, startEpochNanos = 5L, endEpochNanos = 9L,
        )
        val link = SpanLink(fromSpanId = sId(0x0b), linkedTraceId = tId(0x0c), linkedSpanId = sId(0x0d))
        val req = tracesRequestOf(setOf(span), listOf(link))
        val s = json.encodeToString(TracesRequest.serializer(), req)
        assertTrue(s.contains("\"traceId\":\"0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a\""), s)
        assertTrue(s.contains("\"spanId\":\"0b0b0b0b0b0b0b0b\""), s)
        assertTrue(s.contains("\"startTimeUnixNano\":\"5\""), s) // int64 as string
        assertTrue(s.contains("\"links\""), s)
        assertTrue(s.contains("\"0d0d0d0d0d0d0d0d\""), s) // linked span id hex
        assertTrue(s.contains("kuilt.causality"), s)
    }

    @Test
    fun logEncodesBodyAndEnvelope() {
        val rec = LogRecord(recordId = sId(1), body = "hello", severityNumber = 9, severityText = "INFO")
        val s = json.encodeToString(LogsRequest.serializer(), logsRequestOf(setOf(rec)))
        assertTrue(s.contains("\"resourceLogs\""), s)
        assertTrue(s.contains("hello"), s)
        assertTrue(s.contains("\"severityText\":\"INFO\""), s)
    }

    @Test
    fun sumEncodesAsCumulativeIntString() {
        val p = MetricPoint.Sum(MetricKey("req", MetricKind.SUM), value = 7L, startEpochNanos = 0L, timeEpochNanos = 5L)
        val s = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(p)))
        assertTrue(s.contains("\"resourceMetrics\""), s)
        assertTrue(s.contains("\"asInt\":\"7\""), s)
    }

    @Test
    fun doubleSumEncodesAsDouble() {
        val p = MetricPoint.DoubleSum(MetricKey("bytes", MetricKind.SUM), value = 2.5, startEpochNanos = 0L, timeEpochNanos = 5L)
        val s = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(p)))
        assertTrue(s.contains("\"asDouble\":2.5"), s)
        assertTrue(s.contains("\"sum\""), s)
    }

    @Test
    fun sumAlwaysEmitsTemporalityAndMonotonic() {
        // These two fields sit at their serialization defaults (CUMULATIVE=2, monotonic=true).
        // With encodeDefaults=false they would be omitted, and a collector reading the absent
        // enum as AGGREGATION_TEMPORALITY_UNSPECIFIED (0) drops/mis-aggregates the Sum — silent
        // metric loss. They MUST always be on the wire for a Sum.
        val longSum = MetricPoint.Sum(MetricKey("req", MetricKind.SUM), value = 7L, startEpochNanos = 0L, timeEpochNanos = 5L)
        val longJson = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(longSum)))
        assertTrue(longJson.contains("\"aggregationTemporality\":2"), longJson)
        assertTrue(longJson.contains("\"isMonotonic\":true"), longJson)

        val doubleSum = MetricPoint.DoubleSum(MetricKey("bytes", MetricKind.SUM), value = 2.5, startEpochNanos = 0L, timeEpochNanos = 5L)
        val doubleJson = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(doubleSum)))
        assertTrue(doubleJson.contains("\"aggregationTemporality\":2"), doubleJson)
        assertTrue(doubleJson.contains("\"isMonotonic\":true"), doubleJson)
    }

    @Test
    fun gaugeAndCardinalityEncode() {
        val g = MetricPoint.Gauge(MetricKey("cpu", MetricKind.GAUGE), value = 0.5, timeEpochNanos = 5L)
        val gs = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(g)))
        assertTrue(gs.contains("\"gauge\""), gs)
        assertTrue(gs.contains("\"asDouble\":0.5"), gs)

        val c = MetricPoint.Cardinality(MetricKey("users", MetricKind.CARDINALITY), estimate = 12L, timeEpochNanos = 5L)
        val cs = json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(setOf(c)))
        assertTrue(cs.contains("\"gauge\""), cs)
        assertTrue(cs.contains("\"asInt\":\"12\""), cs)
    }
}
