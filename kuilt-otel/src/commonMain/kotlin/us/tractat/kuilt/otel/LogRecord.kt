package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable

/**
 * A single log-record shaped to round-trip through OTLP.
 *
 * This is kuilt's KMP-native representation of an OpenTelemetry `LogRecord`.
 * Fields map 1-to-1 to OTLP's `LogRecord` proto message so a [WarpOtlpBridge]
 * can serialize them directly with no further transformation.
 *
 * ## Trace correlation
 *
 * [traceId] and [spanId] are optional: present when the log was emitted from
 * inside an active trace span; `null` for untraced log lines. Byte conventions
 * match [SpanRecord] exactly — 16 bytes for trace id, 8 bytes for span id.
 *
 * ## Identity and idempotency
 *
 * [recordId] is an 8-byte caller-assigned identifier unique per record per
 * producer. [WarpLogRecordExporter] uses it to detect re-exports: submitting a
 * record with the same [recordId] twice is a no-op (the second call returns
 * [ExportResult.Success] immediately without inserting a duplicate into the
 * [us.tractat.kuilt.crdt.Rga]).
 *
 * [ByteString] is used for all byte fields because it provides content-based
 * `equals`/`hashCode`, which [WarpLogRecordExporter]'s dedup map keys on.
 *
 * ## Honest limits
 *
 * - **Clock skew.** [observedEpochNanos] and [timestampEpochNanos] are the
 *   producer's local clock. Long-offline devices may have skewed clocks; HLC
 *   offset estimation on reconnect is not yet implemented.
 * - **Ordering.** The [us.tractat.kuilt.crdt.Rga] preserves per-producer
 *   insertion order. Cross-producer ordering is resolved by RGA's Lamport
 *   tiebreak — deterministic but not wall-clock accurate under clock skew.
 */
@Serializable
public data class LogRecord(
    /**
     * 8-byte caller-assigned record identifier. Must be unique per record per
     * producer. Used by [WarpLogRecordExporter] to make re-export idempotent.
     */
    @Serializable(with = ByteStringSerializer::class)
    public val recordId: ByteString,
    /** OTLP severity number (1–24). `null` means unset (OTLP default: `SEVERITY_NUMBER_UNSPECIFIED`). */
    public val severityNumber: Int? = null,
    /** Human-readable severity text (e.g. "INFO", "WARN"). `null` means unset. */
    public val severityText: String? = null,
    /** The log body as a plain string. `null` means the body is absent. */
    public val body: String? = null,
    /** Key/value string attributes attached to this record. */
    public val attributes: Map<String, String> = emptyMap(),
    /**
     * Epoch-nanosecond timestamp at which the event occurred, from the producer's
     * local clock. `null` if the event time is unknown.
     */
    public val timestampEpochNanos: Long? = null,
    /**
     * Epoch-nanosecond timestamp at which the record was observed by the SDK,
     * from the producer's local clock.
     */
    public val observedEpochNanos: Long? = null,
    /**
     * Trace context — 16-byte trace id. Present only when this record was emitted
     * inside an active span. Matches OTLP proto3 `bytes trace_id`.
     */
    @Serializable(with = ByteStringSerializer::class)
    public val traceId: ByteString? = null,
    /**
     * Trace context — 8-byte span id. Present only when this record was emitted
     * inside an active span. Matches OTLP proto3 `bytes span_id`.
     */
    @Serializable(with = ByteStringSerializer::class)
    public val spanId: ByteString? = null,
) {
    init {
        require(recordId.size == 8) {
            "recordId must be 8 bytes (64-bit); got ${recordId.size}"
        }
        require(traceId == null || traceId.size == 16) {
            "traceId must be 16 bytes (128-bit) or null; got ${traceId?.size}"
        }
        require(spanId == null || spanId.size == 8) {
            "spanId must be 8 bytes (64-bit) or null; got ${spanId?.size}"
        }
        require((traceId == null) == (spanId == null)) {
            "traceId and spanId must both be present or both be null"
        }
    }
}
