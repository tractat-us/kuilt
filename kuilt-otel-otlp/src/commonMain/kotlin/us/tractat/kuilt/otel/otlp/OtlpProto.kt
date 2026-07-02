@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.otlp

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType

// OTLP/protobuf request envelopes — the encode-side subset kuilt emits, mirroring the
// OtlpJson.kt DTOs but shaped for the *binary* wire. Two things differ fundamentally
// from JSON and are the whole point of this file:
//
//  1. Byte fields (trace/span ids) are raw `bytes` (ByteArray) — NOT lowercase-hex
//     strings. OTLP proto3 declares `bytes trace_id`/`bytes span_id`.
//  2. `*_time_unix_nano` fields are `fixed64` (@ProtoType FIXED), and `as_int` is
//     `sfixed64` (also FIXED — wire-identical to fixed64) — NOT decimal strings.
//
// Every field carries its OTLP proto field number via @ProtoNumber; those numbers are
// the compatibility contract with any collector and are pinned by golden byte-vectors
// in OtlpProtoEncodingTest. `oneof`s (AnyValue.value, Metric.data, NumberDataPoint.value)
// are modelled as nullable-field-per-branch: proto3 oneof is wire-identical to a set of
// optional fields where at most one is present, so setting exactly one nullable branch
// produces the canonical oneof bytes. `resource`/`scope`/`schema_url` fields are omitted
// (optional; a collector tolerates their absence).

// ── Common ─────────────────────────────────────────────────────────────────────

@Serializable
internal data class ProtoKeyValue(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: ProtoAnyValue,
)

/** OTLP `AnyValue`. kuilt only emits string-valued attributes/bodies → `string_value` (field 1). */
@Serializable
internal data class ProtoAnyValue(
    @ProtoNumber(1) val stringValue: String,
)

// ── Traces ───────────────────────────────────────────────────────────────────

@Serializable
internal data class ProtoTracesRequest(
    @ProtoNumber(1) val resourceSpans: List<ProtoResourceSpans>,
)

@Serializable
internal data class ProtoResourceSpans(
    @ProtoNumber(2) val scopeSpans: List<ProtoScopeSpans>,
)

@Serializable
internal data class ProtoScopeSpans(
    @ProtoNumber(2) val spans: List<ProtoSpan>,
)

@Serializable
internal data class ProtoSpan(
    @ProtoNumber(1) val traceId: ByteArray,
    @ProtoNumber(2) val spanId: ByteArray,
    @ProtoNumber(4) val parentSpanId: ByteArray? = null,
    @ProtoNumber(5) val name: String,
    @ProtoNumber(6) val kind: Int,
    @ProtoNumber(7) @ProtoType(ProtoIntegerType.FIXED) val startTimeUnixNano: Long,
    @ProtoNumber(8) @ProtoType(ProtoIntegerType.FIXED) val endTimeUnixNano: Long,
    @ProtoNumber(9) val attributes: List<ProtoKeyValue> = emptyList(),
    @ProtoNumber(13) val links: List<ProtoLink> = emptyList(),
)

@Serializable
internal data class ProtoLink(
    @ProtoNumber(1) val traceId: ByteArray,
    @ProtoNumber(2) val spanId: ByteArray,
    @ProtoNumber(4) val attributes: List<ProtoKeyValue> = emptyList(),
)

// ── Logs ─────────────────────────────────────────────────────────────────────

@Serializable
internal data class ProtoLogsRequest(
    @ProtoNumber(1) val resourceLogs: List<ProtoResourceLogs>,
)

@Serializable
internal data class ProtoResourceLogs(
    @ProtoNumber(2) val scopeLogs: List<ProtoScopeLogs>,
)

@Serializable
internal data class ProtoScopeLogs(
    @ProtoNumber(2) val logRecords: List<ProtoLogRecord>,
)

@Serializable
internal data class ProtoLogRecord(
    @ProtoNumber(1) @ProtoType(ProtoIntegerType.FIXED) val timeUnixNano: Long? = null,
    @ProtoNumber(11) @ProtoType(ProtoIntegerType.FIXED) val observedTimeUnixNano: Long? = null,
    @ProtoNumber(2) val severityNumber: Int? = null,
    @ProtoNumber(3) val severityText: String? = null,
    @ProtoNumber(5) val body: ProtoAnyValue? = null,
    @ProtoNumber(6) val attributes: List<ProtoKeyValue> = emptyList(),
    @ProtoNumber(9) val traceId: ByteArray? = null,
    @ProtoNumber(10) val spanId: ByteArray? = null,
)

// ── Metrics ──────────────────────────────────────────────────────────────────

@Serializable
internal data class ProtoMetricsRequest(
    @ProtoNumber(1) val resourceMetrics: List<ProtoResourceMetrics>,
)

@Serializable
internal data class ProtoResourceMetrics(
    @ProtoNumber(2) val scopeMetrics: List<ProtoScopeMetrics>,
)

@Serializable
internal data class ProtoScopeMetrics(
    @ProtoNumber(2) val metrics: List<ProtoMetric>,
)

@Serializable
internal data class ProtoMetric(
    @ProtoNumber(1) val name: String,
    // oneof data { Gauge gauge = 5; Sum sum = 7; }
    @ProtoNumber(7) val sum: ProtoSum? = null,
    @ProtoNumber(5) val gauge: ProtoGauge? = null,
)

@Serializable
internal data class ProtoSum(
    @ProtoNumber(1) val dataPoints: List<ProtoNumberDataPoint>,
    // Always on the wire (no default → always encoded): an absent aggregation_temporality
    // reads as UNSPECIFIED (0) and a collector drops/mis-aggregates the Sum. kuilt's counter
    // stores are grow-only cumulative + monotonic. Mirrors OtlpSum's @EncodeDefault(ALWAYS).
    @ProtoNumber(2) val aggregationTemporality: Int,
    @ProtoNumber(3) val isMonotonic: Boolean,
)

@Serializable
internal data class ProtoGauge(
    @ProtoNumber(1) val dataPoints: List<ProtoNumberDataPoint>,
)

@Serializable
internal data class ProtoNumberDataPoint(
    @ProtoNumber(7) val attributes: List<ProtoKeyValue> = emptyList(),
    @ProtoNumber(2) @ProtoType(ProtoIntegerType.FIXED) val startTimeUnixNano: Long? = null,
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.FIXED) val timeUnixNano: Long,
    // oneof value { double as_double = 4; sfixed64 as_int = 6; }
    @ProtoNumber(4) val asDouble: Double? = null,
    @ProtoNumber(6) @ProtoType(ProtoIntegerType.FIXED) val asInt: Long? = null,
)
