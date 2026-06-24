package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable

/**
 * A completed span record, shaped to round-trip through OTLP JSON.
 *
 * This is kuilt's KMP-native representation of an OpenTelemetry span. It is
 * intentionally minimal: the fields map 1-to-1 to OTLP's `Span` proto message
 * so a [WarpOtlpBridge] can serialize them directly with no further transformation.
 *
 * `traceId` and `spanId` are hex-encoded 32 / 16 character strings (128-bit /
 * 64-bit) matching the W3C `traceparent` encoding and OTLP JSON conventions.
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
    /** Hex-encoded 128-bit trace id (32 hex chars). */
    public val traceId: String,
    /** Hex-encoded 64-bit span id (16 hex chars). */
    public val spanId: String,
    /** Optional parent span id (null for root spans). */
    public val parentSpanId: String?,
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
)

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
