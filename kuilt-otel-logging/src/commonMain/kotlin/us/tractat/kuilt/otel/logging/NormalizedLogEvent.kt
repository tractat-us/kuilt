package us.tractat.kuilt.otel.logging

/**
 * A log event normalized to a platform-independent shape.
 *
 * Each per-platform capture edge translates the platform's native log event into
 * this common form before handing it to [LogCapture]. The edge is the only thing
 * that differs across platforms; everything downstream of this type is shared
 * common code.
 */
public data class NormalizedLogEvent(
    /** The event's level. */
    public val level: LogLevel,
    /** The originating logger's name (e.g. a fully-qualified class name). */
    public val loggerName: String,
    /** The log message body, or `null` if the event carried no message. */
    public val message: String?,
    /**
     * Structured key/value pairs attached to the event (the MDC-equivalent
     * payload). Mapped onto the resulting `LogRecord`'s attributes by
     * [CaptureConfig.attributeMapper].
     */
    public val attributes: Map<String, String> = emptyMap(),
    /**
     * An optional event time supplied by the source, in epoch nanoseconds.
     * [LogCapture.resolveAtEdge] preserves it, or reads the injected clock when absent.
     * This alone does not resolve an event: queued capture requires [ResolvedLogEvent].
     */
    public val emittedEpochNanos: Long? = null,
)
