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
     * The distributed-trace context resolved at the **synchronous capture edge**,
     * on the caller that logged — never on the drain coroutine (#1034). The edge
     * fills this by calling [LogCapture.resolveTrace] while it still sees the
     * caller's ambient context; [LogCapture.capture] then feeds the sampling gate
     * from this snapshot instead of re-consulting the provider off-thread.
     *
     * `null` means either no provider is wired (the M1 always-on default) or the
     * provider resolved to "untraced" — the two are disambiguated by whether a
     * [TraceContextProvider] was installed, which [LogCapture.capture] knows.
     */
    public val activeTrace: ActiveTrace? = null,
)
