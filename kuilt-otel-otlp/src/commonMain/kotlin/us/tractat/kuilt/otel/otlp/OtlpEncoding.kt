package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase-hex encoding of the raw bytes, as OTLP/JSON requires for trace/span ids. */
internal fun ByteString.toHex(): String = buildString(size * 2) {
    for (i in 0 until size) {
        val b = this@toHex[i].toInt() and 0xFF
        append(HEX_DIGITS[b ushr 4])
        append(HEX_DIGITS[b and 0x0F])
    }
}

/** Inverse of [toHex] — decode a lowercase-hex string back to a [ByteString]. */
internal fun String.hexToByteString(): ByteString {
    require(length % 2 == 0) { "hex string must have an even length; got $length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = HEX_DIGITS.indexOf(this[i * 2])
        val lo = HEX_DIGITS.indexOf(this[i * 2 + 1])
        require(hi >= 0 && lo >= 0) { "invalid hex char in '$this'" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return ByteString(out)
}

private fun SpanKind.toOtlp(): Int = when (this) {
    SpanKind.INTERNAL -> 1
    SpanKind.SERVER -> 2
    SpanKind.CLIENT -> 3
    SpanKind.PRODUCER -> 4
    SpanKind.CONSUMER -> 5
}

private fun attrs(m: Map<String, String>): List<KeyValue> =
    m.map { KeyValue(it.key, AnyValue(it.value)) }

/** Render spans (with their inferred causal links) into an OTLP/JSON traces request. */
internal fun tracesRequestOf(spans: Set<SpanRecord>, links: List<SpanLink>): TracesRequest {
    val linksByFrom = links.groupBy { it.fromSpanId }
    val otlpSpans = spans.map { s ->
        OtlpSpan(
            traceId = s.traceId.toHex(),
            spanId = s.spanId.toHex(),
            parentSpanId = s.parentSpanId?.toHex(),
            name = s.name,
            kind = s.kind.toOtlp(),
            startTimeUnixNano = s.startEpochNanos.toString(),
            endTimeUnixNano = s.endEpochNanos.toString(),
            attributes = attrs(s.attributes),
            links = (linksByFrom[s.spanId] ?: emptyList()).map {
                OtlpLink(it.linkedTraceId.toHex(), it.linkedSpanId.toHex(), attrs(it.attributes))
            },
        )
    }
    return TracesRequest(listOf(ResourceSpans(listOf(ScopeSpans(otlpSpans)))))
}

/** Render log records into an OTLP/JSON logs request. */
internal fun logsRequestOf(logs: Set<LogRecord>): LogsRequest {
    val recs = logs.map { r ->
        OtlpLogRecord(
            timeUnixNano = r.timestampEpochNanos?.toString(),
            observedTimeUnixNano = r.observedEpochNanos?.toString(),
            severityNumber = r.severityNumber,
            severityText = r.severityText,
            body = r.body?.let { AnyValue(it) },
            attributes = attrs(r.attributes),
            traceId = r.traceId?.toHex(),
            spanId = r.spanId?.toHex(),
        )
    }
    return LogsRequest(listOf(ResourceLogs(listOf(ScopeLogs(recs)))))
}

/** Render metric points into an OTLP/JSON metrics request (Sum for sums, Gauge for gauge/cardinality). */
internal fun metricsRequestOf(points: Set<MetricPoint>): MetricsRequest {
    val metrics = points.map { p ->
        when (p) {
            is MetricPoint.Sum -> OtlpMetric(
                name = p.key.name,
                sum = OtlpSum(
                    listOf(
                        NumberDataPoint(
                            attributes = attrs(p.key.attributes),
                            startTimeUnixNano = p.startEpochNanos.toString(),
                            timeUnixNano = p.timeEpochNanos.toString(),
                            asInt = p.value.toString(),
                        ),
                    ),
                ),
            )
            is MetricPoint.DoubleSum -> OtlpMetric(
                name = p.key.name,
                sum = OtlpSum(
                    listOf(
                        NumberDataPoint(
                            attributes = attrs(p.key.attributes),
                            startTimeUnixNano = p.startEpochNanos.toString(),
                            timeUnixNano = p.timeEpochNanos.toString(),
                            asDouble = p.value,
                        ),
                    ),
                ),
            )
            is MetricPoint.Gauge -> OtlpMetric(
                name = p.key.name,
                gauge = OtlpGauge(
                    listOf(
                        NumberDataPoint(
                            attributes = attrs(p.key.attributes),
                            timeUnixNano = p.timeEpochNanos.toString(),
                            asDouble = p.value,
                        ),
                    ),
                ),
            )
            is MetricPoint.Cardinality -> OtlpMetric(
                name = p.key.name,
                gauge = OtlpGauge(
                    listOf(
                        NumberDataPoint(
                            attributes = attrs(p.key.attributes),
                            timeUnixNano = p.timeEpochNanos.toString(),
                            asInt = p.estimate.toString(),
                        ),
                    ),
                ),
            )
        }
    }
    return MetricsRequest(listOf(ResourceMetrics(listOf(ScopeMetrics(metrics)))))
}
