package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable

/**
 * A completed span record, shaped to round-trip through OTLP.
 *
 * This is kuilt's KMP-native representation of an OpenTelemetry span. It is
 * intentionally minimal: the fields map 1-to-1 to OTLP's `Span` proto message
 * so a [WarpOtlpBridge] can serialize them directly with no further transformation.
 *
 * `traceId` and `spanId` carry raw bytes matching the OTLP protobuf wire format:
 * - `traceId` — 16 bytes (128-bit), matches OTLP proto3 `bytes trace_id`.
 * - `spanId` — 8 bytes (64-bit), matches OTLP proto3 `bytes span_id`.
 * - `parentSpanId` — 8 bytes for child spans; `null` for root spans.
 *
 * [ByteString] is used rather than [ByteArray] because the [ORSet] inside
 * [WarpSpanExporter] keys by element equality — [ByteString] provides content-based
 * `equals`/`hashCode`, so re-exporting the same span is always a no-op set union.
 *
 * [SpanRecord] is the element type stored in the [ORSet][us.tractat.kuilt.crdt.ORSet]
 * inside [WarpSpanExporter]: keyed by [spanId], so re-exporting the same span
 * is a no-op (set union is idempotent).
 *
 * ## Honest limits
 * - **Clock skew.** [startEpochNanos] and [endEpochNanos] are the *producer's* local
 *   clock. Long-offline devices may produce timestamps far in the past. A hybrid
 *   logical clock (HLC) offset could be estimated on reconnect, but is not yet
 *   implemented — this is filed as a follow-up.
 * - **Late traces.** Spans that straddle an offline and an online producer only
 *   assemble into a complete trace when the offline half syncs. OTLP collectors
 *   accept late spans within an assembly window (typically 10 min–1 hr); spans
 *   that miss the window are indexed as orphaned roots.
 */
@Serializable
public data class SpanRecord(
    /** 128-bit trace id as raw bytes (16 bytes). Matches OTLP proto3 `bytes trace_id`. */
    @Serializable(with = ByteStringSerializer::class)
    public val traceId: ByteString,
    /** 64-bit span id as raw bytes (8 bytes). Matches OTLP proto3 `bytes span_id`. */
    @Serializable(with = ByteStringSerializer::class)
    public val spanId: ByteString,
    /** Parent span id (8 bytes), or `null` for root spans. */
    @Serializable(with = ByteStringSerializer::class)
    public val parentSpanId: ByteString?,
    /** Human-readable operation name. */
    public val name: String,
    /** Span kind: SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL. */
    public val kind: SpanKind,
    /** Epoch-nanosecond start timestamp from the producer's local clock. */
    public val startEpochNanos: Long,
    /** Epoch-nanosecond end timestamp from the producer's local clock. */
    public val endEpochNanos: Long,
    /** Key/value string attributes. */
    public val attributes: Map<String, String> = emptyMap(),
    /** Span status. */
    public val status: SpanStatus = SpanStatus.Unset,
) {
    init {
        require(traceId.size == 16) {
            "traceId must be 16 bytes (128-bit); got ${traceId.size}"
        }
        require(spanId.size == 8) {
            "spanId must be 8 bytes (64-bit); got ${spanId.size}"
        }
        require(parentSpanId == null || parentSpanId.size == 8) {
            "parentSpanId must be 8 bytes (64-bit) or null; got ${parentSpanId?.size}"
        }
    }
}

/** OTLP span kind values. */
@Serializable
public enum class SpanKind {
    INTERNAL, SERVER, CLIENT, PRODUCER, CONSUMER
}

/** OTLP span status. */
@Serializable
public sealed interface SpanStatus {
    /** No status set (default). */
    @Serializable
    public data object Unset : SpanStatus

    /** Span completed successfully. */
    @Serializable
    public data object Ok : SpanStatus

    /** Span completed with an error. */
    @Serializable
    public data class Error(public val message: String) : SpanStatus
}
