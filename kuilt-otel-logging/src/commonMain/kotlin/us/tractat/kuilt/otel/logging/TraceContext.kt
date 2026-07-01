package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString

/**
 * The distributed-trace context active on the current call.
 *
 * When an app runs tracing, [LogCapture] consults a [TraceContextProvider] per
 * event: a sampled trace's [traceId]/[spanId] are stamped onto the captured
 * `LogRecord` so logs line up with spans. Byte sizes match OTLP / `SpanRecord`:
 * 16-byte trace id, 8-byte span id.
 */
public data class ActiveTrace(
    /** 16-byte (128-bit) trace id. */
    public val traceId: ByteString,
    /** 8-byte (64-bit) span id. */
    public val spanId: ByteString,
    /** Whether the trace's sampler kept this trace. */
    public val sampled: Boolean,
) {
    init {
        require(traceId.size == 16) { "traceId must be 16 bytes; got ${traceId.size}" }
        require(spanId.size == 8) { "spanId must be 8 bytes; got ${spanId.size}" }
    }
}

/** Supplies the trace context active on the current call, or `null` when none. */
public fun interface TraceContextProvider {
    /** The [ActiveTrace] active on the current call, or `null` if untraced. */
    public fun current(): ActiveTrace?
}

/** What to do with a log emitted outside any active trace. */
public enum class UntracedPolicy {
    /** Keep untraced logs (the always-on default). */
    CAPTURE,

    /** Drop untraced logs — capture only what a sampled trace covers. */
    DROP,
}
