package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.logging.ActiveTrace
import us.tractat.kuilt.otel.logging.TraceContextProvider

/**
 * A [TraceContextProvider] backed by the OpenTelemetry SDK's current context.
 *
 * Reads `Span.current().spanContext` on each call: a valid span context becomes
 * an [ActiveTrace] carrying the 16-byte trace id, 8-byte span id, and the
 * context's sampled flag; an invalid context (no active span) is `null`.
 */
public class OtelSdkTraceContextProvider : TraceContextProvider {
    override fun current(): ActiveTrace? = fromSpanContext(Span.current().spanContext)

    /** Map an OTel [SpanContext] to an [ActiveTrace], or `null` if invalid. Visible for testing. */
    internal fun fromSpanContext(context: SpanContext): ActiveTrace? {
        if (!context.isValid) return null
        return ActiveTrace(
            traceId = ByteString(*context.traceIdBytes),
            spanId = ByteString(*context.spanIdBytes),
            sampled = context.isSampled,
        )
    }
}
