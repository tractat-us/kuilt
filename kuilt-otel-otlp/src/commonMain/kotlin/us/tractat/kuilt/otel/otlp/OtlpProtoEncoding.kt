package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord

// Maps kuilt's domain records into the OTLP/protobuf DTOs (OtlpProto.kt). The shape
// mirrors OtlpEncoding.kt (the JSON path) one-to-one — same digest/bridge inputs, same
// nesting — but the leaf encodings differ: raw bytes for ids (not hex), Long nanos for
// fixed64 (not decimal strings). SpanKind → OTLP int is shared with the JSON path.

/** Raw bytes of a [ByteString], as OTLP/protobuf `bytes` fields require. */
private fun ByteString.toByteArray(): ByteArray = ByteArray(size) { this[it] }

private fun protoAttrs(m: Map<String, String>): List<ProtoKeyValue> =
    m.map { ProtoKeyValue(it.key, ProtoAnyValue(it.value)) }

/** Render spans (with their inferred causal links) into an OTLP/protobuf traces request. */
internal fun tracesProtoOf(spans: Set<SpanRecord>, links: List<SpanLink>): ProtoTracesRequest {
    val linksByFrom = links.groupBy { it.fromSpanId }
    val protoSpans = spans.map { s ->
        ProtoSpan(
            traceId = s.traceId.toByteArray(),
            spanId = s.spanId.toByteArray(),
            parentSpanId = s.parentSpanId?.toByteArray(),
            name = s.name,
            kind = s.kind.toOtlp(),
            startTimeUnixNano = s.startEpochNanos,
            endTimeUnixNano = s.endEpochNanos,
            attributes = protoAttrs(s.attributes),
            links = (linksByFrom[s.spanId] ?: emptyList()).map {
                ProtoLink(
                    it.linkedTraceId.toByteArray(),
                    it.linkedSpanId.toByteArray(),
                    protoAttrs(it.attributes),
                )
            },
        )
    }
    return ProtoTracesRequest(listOf(ProtoResourceSpans(listOf(ProtoScopeSpans(protoSpans)))))
}

/** Render log records into an OTLP/protobuf logs request. */
internal fun logsProtoOf(logs: Set<LogRecord>): ProtoLogsRequest {
    val recs = logs.map { r ->
        ProtoLogRecord(
            timeUnixNano = r.timestampEpochNanos,
            observedTimeUnixNano = r.observedEpochNanos,
            severityNumber = r.severityNumber,
            severityText = r.severityText,
            body = r.body?.let { ProtoAnyValue(it) },
            attributes = protoAttrs(r.attributes),
            traceId = r.traceId?.toByteArray(),
            spanId = r.spanId?.toByteArray(),
        )
    }
    return ProtoLogsRequest(listOf(ProtoResourceLogs(listOf(ProtoScopeLogs(recs)))))
}

/** Render metric points into an OTLP/protobuf metrics request (Sum for sums, Gauge otherwise). */
internal fun metricsProtoOf(points: Set<MetricPoint>): ProtoMetricsRequest {
    val metrics = points.map { p ->
        when (p) {
            is MetricPoint.Sum -> ProtoMetric(
                name = p.key.name,
                sum = ProtoSum(
                    dataPoints = listOf(
                        ProtoNumberDataPoint(
                            attributes = protoAttrs(p.key.attributes),
                            startTimeUnixNano = p.startEpochNanos,
                            timeUnixNano = p.timeEpochNanos,
                            asInt = p.value,
                        ),
                    ),
                    aggregationTemporality = AGGREGATION_TEMPORALITY_CUMULATIVE,
                    isMonotonic = true,
                ),
            )
            is MetricPoint.DoubleSum -> ProtoMetric(
                name = p.key.name,
                sum = ProtoSum(
                    dataPoints = listOf(
                        ProtoNumberDataPoint(
                            attributes = protoAttrs(p.key.attributes),
                            startTimeUnixNano = p.startEpochNanos,
                            timeUnixNano = p.timeEpochNanos,
                            asDouble = p.value,
                        ),
                    ),
                    aggregationTemporality = AGGREGATION_TEMPORALITY_CUMULATIVE,
                    isMonotonic = true,
                ),
            )
            is MetricPoint.Gauge -> ProtoMetric(
                name = p.key.name,
                gauge = ProtoGauge(
                    dataPoints = listOf(
                        ProtoNumberDataPoint(
                            attributes = protoAttrs(p.key.attributes),
                            timeUnixNano = p.timeEpochNanos,
                            asDouble = p.value,
                        ),
                    ),
                ),
            )
            is MetricPoint.Cardinality -> ProtoMetric(
                name = p.key.name,
                gauge = ProtoGauge(
                    dataPoints = listOf(
                        ProtoNumberDataPoint(
                            attributes = protoAttrs(p.key.attributes),
                            timeUnixNano = p.timeEpochNanos,
                            asInt = p.estimate,
                        ),
                    ),
                ),
            )
        }
    }
    return ProtoMetricsRequest(listOf(ProtoResourceMetrics(listOf(ProtoScopeMetrics(metrics)))))
}
