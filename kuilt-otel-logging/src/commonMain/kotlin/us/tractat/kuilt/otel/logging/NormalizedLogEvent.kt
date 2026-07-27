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
     * fills this by calling [LogCapture.resolveAtEdge] while it still sees the
     * caller's ambient context; [LogCapture.capture] then feeds the sampling gate
     * from this snapshot instead of re-consulting the provider off-thread.
     *
     * `null` means either no provider is wired (the M1 always-on default) or the
     * provider resolved to "untraced" — the two are disambiguated by whether a
     * [TraceContextProvider] was installed, which [LogCapture.capture] knows.
     */
    public val activeTrace: ActiveTrace? = null,
    /**
     * The `LogRecord` attributes produced by [CaptureConfig.attributeMapper],
     * applied at the **synchronous capture edge** on the caller that logged —
     * never on the drain coroutine (#1630). A queueing edge fills this by calling
     * [LogCapture.resolveAtEdge] while the caller's ambient state is still the
     * state the line was emitted under; [LogCapture.capture] then uses the
     * snapshot instead of re-running the mapper off-thread.
     *
     * `null` means the event was never edge-resolved. [LogCapture.capture] then
     * applies the mapper itself — correct for a caller that invokes `capture()`
     * directly from its own log site, because that call *is* the synchronous edge.
     * A **queueing** edge must always resolve: leaving this `null` there is
     * exactly the #1630 bug.
     */
    public val resolvedAttributes: Map<String, String>? = null,
)
