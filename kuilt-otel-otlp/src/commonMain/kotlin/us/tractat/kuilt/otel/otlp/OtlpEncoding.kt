package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Inverse of `toHexString()`, whose default format is the lowercase hex OTLP/JSON requires for
 * trace and span ids — decode a lowercase-hex string back to a [ByteString].
 */
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

internal fun SpanKind.toOtlp(): Int = when (this) {
    SpanKind.INTERNAL -> 1
    SpanKind.SERVER -> 2
    SpanKind.CLIENT -> 3
    SpanKind.PRODUCER -> 4
    SpanKind.CONSUMER -> 5
}

private fun attrs(m: Map<String, String>): List<KeyValue> =
    m.map { KeyValue(it.key, AnyValue(it.value)) }

/** The envelope's resource as OTLP/JSON, or null (field omitted) when it has no attributes. */
private fun OtlpEnvelope.jsonResource(): OtlpResource? =
    if (resource.isEmpty()) null else OtlpResource(attrs(resource))

/** The envelope's scope as OTLP/JSON, or null (field omitted) when name and version are both empty. */
private fun OtlpEnvelope.jsonScope(): OtlpScope? =
    if (scopeIsEmpty) null else OtlpScope(scopeName, scopeVersion)

/** Render spans (with their inferred causal links) into an OTLP/JSON traces request. */
internal fun tracesRequestOf(
    spans: Set<SpanRecord>,
    links: List<SpanLink>,
    envelope: OtlpEnvelope,
): TracesRequest {
    val linksByFrom = links.groupBy { it.fromSpanId }
    val otlpSpans = spans.map { s ->
        OtlpSpan(
            traceId = s.traceId.toHexString(),
            spanId = s.spanId.toHexString(),
            parentSpanId = s.parentSpanId?.toHexString(),
            name = s.name,
            kind = s.kind.toOtlp(),
            startTimeUnixNano = s.startEpochNanos.toString(),
            endTimeUnixNano = s.endEpochNanos.toString(),
            attributes = attrs(s.attributes),
            links = (linksByFrom[s.spanId] ?: emptyList()).map {
                OtlpLink(it.linkedTraceId.toHexString(), it.linkedSpanId.toHexString(), attrs(it.attributes))
            },
        )
    }
    return TracesRequest(
        listOf(ResourceSpans(envelope.jsonResource(), listOf(ScopeSpans(envelope.jsonScope(), otlpSpans)))),
    )
}

/** Render log records into an OTLP/JSON logs request. */
internal fun logsRequestOf(logs: Set<LogRecord>, envelope: OtlpEnvelope): LogsRequest {
    val recs = logs.map { r ->
        OtlpLogRecord(
            timeUnixNano = r.timestampEpochNanos?.toString(),
            observedTimeUnixNano = r.observedEpochNanos?.toString(),
            severityNumber = r.severityNumber,
            severityText = r.severityText,
            body = r.body?.let { AnyValue(it) },
            attributes = attrs(r.attributes),
            traceId = r.traceId?.toHexString(),
            spanId = r.spanId?.toHexString(),
        )
    }
    return LogsRequest(listOf(ResourceLogs(envelope.jsonResource(), listOf(ScopeLogs(envelope.jsonScope(), recs)))))
}

/**
 * Render metric points into an OTLP/JSON metrics request (Sum for sums, non-monotonic Sum for
 * up-down sums, Gauge for gauge/cardinality, ExponentialHistogram for histograms).
 */
internal fun metricsRequestOf(points: Set<MetricPoint>, envelope: OtlpEnvelope): MetricsRequest {
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
            is MetricPoint.UpDownSum -> OtlpMetric(
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
                    isMonotonic = false,
                ),
            )
            is MetricPoint.DoubleUpDownSum -> OtlpMetric(
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
                    isMonotonic = false,
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
            is MetricPoint.ExponentialHistogram -> OtlpMetric(
                name = p.key.name,
                exponentialHistogram = OtlpExponentialHistogram(
                    listOf(
                        OtlpExponentialHistogramDataPoint(
                            attributes = attrs(p.key.attributes),
                            startTimeUnixNano = p.startEpochNanos.toString(),
                            timeUnixNano = p.timeEpochNanos.toString(),
                            count = p.count.toString(),
                            scale = p.scale,
                            zeroCount = p.zeroCount.toString(),
                            positive = jsonBuckets(p.positiveOffset, p.positiveBucketCounts),
                            negative = jsonBuckets(p.negativeOffset, p.negativeBucketCounts),
                            zeroThreshold = p.zeroThreshold,
                        ),
                    ),
                ),
            )
        }
    }
    return MetricsRequest(
        listOf(ResourceMetrics(envelope.jsonResource(), listOf(ScopeMetrics(envelope.jsonScope(), metrics)))),
    )
}

/** A sign's bucket array as OTLP/JSON, or null (field omitted) when no buckets are populated. */
private fun jsonBuckets(offset: Int, counts: List<Long>): OtlpExponentialHistogramBuckets? =
    if (counts.isEmpty()) null
    else OtlpExponentialHistogramBuckets(offset = offset, bucketCounts = counts.map { it.toString() })
