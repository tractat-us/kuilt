package us.tractat.kuilt.otel.otlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

// OTLP/JSON request envelopes and messages — the encode-side subset kuilt emits.
// Byte fields (trace/span ids) are lowercase hex strings and 64-bit ints are strings;
// the mappers in OtlpEncoding.kt produce those String values, so the DTO fields are
// already String and need no custom serializers. A response body is never parsed.

// ── Traces ───────────────────────────────────────────────────────────────────

@Serializable
internal data class TracesRequest(val resourceSpans: List<ResourceSpans>)

@Serializable
internal data class ResourceSpans(val scopeSpans: List<ScopeSpans>)

@Serializable
internal data class ScopeSpans(val spans: List<OtlpSpan>)

@Serializable
internal data class OtlpSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val name: String,
    val kind: Int,
    val startTimeUnixNano: String,
    val endTimeUnixNano: String,
    val attributes: List<KeyValue> = emptyList(),
    val links: List<OtlpLink> = emptyList(),
)

@Serializable
internal data class OtlpLink(
    val traceId: String,
    val spanId: String,
    val attributes: List<KeyValue> = emptyList(),
)

@Serializable
internal data class KeyValue(val key: String, val value: AnyValue)

@Serializable
internal data class AnyValue(val stringValue: String)

// ── Logs ─────────────────────────────────────────────────────────────────────

@Serializable
internal data class LogsRequest(val resourceLogs: List<ResourceLogs>)

@Serializable
internal data class ResourceLogs(val scopeLogs: List<ScopeLogs>)

@Serializable
internal data class ScopeLogs(val logRecords: List<OtlpLogRecord>)

@Serializable
internal data class OtlpLogRecord(
    val timeUnixNano: String? = null,
    val observedTimeUnixNano: String? = null,
    val severityNumber: Int? = null,
    val severityText: String? = null,
    val body: AnyValue? = null,
    val attributes: List<KeyValue> = emptyList(),
    val traceId: String? = null,
    val spanId: String? = null,
)

// ── Metrics ──────────────────────────────────────────────────────────────────

@Serializable
internal data class MetricsRequest(val resourceMetrics: List<ResourceMetrics>)

@Serializable
internal data class ResourceMetrics(val scopeMetrics: List<ScopeMetrics>)

@Serializable
internal data class ScopeMetrics(val metrics: List<OtlpMetric>)

@Serializable
internal data class OtlpMetric(
    val name: String,
    val sum: OtlpSum? = null,
    val gauge: OtlpGauge? = null,
    val exponentialHistogram: OtlpExponentialHistogram? = null,
)

@Serializable
internal data class OtlpSum(
    val dataPoints: List<NumberDataPoint>,
    // @EncodeDefault(ALWAYS) forces these two onto the wire even under the encoder's
    // encodeDefaults = false. If omitted, a collector reads the absent enum as
    // AGGREGATION_TEMPORALITY_UNSPECIFIED (0), which makes an OTLP Sum malformed and is
    // dropped/mis-aggregated — silent metric loss. kuilt's counter stores are grow-only
    // cumulative (GCounter/GCounterDouble), so every emitted Sum is CUMULATIVE + monotonic.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val aggregationTemporality: Int = AGGREGATION_TEMPORALITY_CUMULATIVE,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val isMonotonic: Boolean = true,
)

@Serializable
internal data class OtlpGauge(val dataPoints: List<NumberDataPoint>)

@Serializable
internal data class NumberDataPoint(
    val attributes: List<KeyValue> = emptyList(),
    val startTimeUnixNano: String? = null,
    val timeUnixNano: String,
    val asInt: String? = null,
    val asDouble: Double? = null,
)

@Serializable
internal data class OtlpExponentialHistogram(
    val dataPoints: List<OtlpExponentialHistogramDataPoint>,
    // Always on the wire for the same reason as OtlpSum's temporality: an absent enum
    // reads as UNSPECIFIED (0) and the histogram is dropped/mis-aggregated. kuilt's
    // DDSketch store is grow-only, so every emitted histogram is CUMULATIVE.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val aggregationTemporality: Int = AGGREGATION_TEMPORALITY_CUMULATIVE,
)

// count/zeroCount are proto uint64 → strings in OTLP/JSON (same convention as
// NumberDataPoint.asInt); scale/offset are sint32 → plain numbers.
@Serializable
internal data class OtlpExponentialHistogramDataPoint(
    val attributes: List<KeyValue> = emptyList(),
    val startTimeUnixNano: String? = null,
    val timeUnixNano: String,
    val count: String,
    val scale: Int,
    val zeroCount: String,
    val positive: OtlpExponentialHistogramBuckets? = null,
    val negative: OtlpExponentialHistogramBuckets? = null,
    val zeroThreshold: Double,
)

@Serializable
internal data class OtlpExponentialHistogramBuckets(
    val offset: Int,
    val bucketCounts: List<String>,
)

/** OTLP `AGGREGATION_TEMPORALITY_CUMULATIVE`. */
internal const val AGGREGATION_TEMPORALITY_CUMULATIVE: Int = 2
