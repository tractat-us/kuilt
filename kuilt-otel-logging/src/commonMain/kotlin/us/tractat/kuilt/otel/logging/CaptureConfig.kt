package us.tractat.kuilt.otel.logging

/** Attribute key under which [defaultAttributeMapper] records the logger name. */
public const val LOGGER_NAME_ATTRIBUTE: String = "logger.name"

/** Attribute key under which a capture edge may record an exception's message. */
public const val EXCEPTION_MESSAGE_ATTRIBUTE: String = "exception.message"

/**
 * Policy for the capture core: which events to keep and how to shape their
 * attributes.
 *
 * M1 capture is **always-on**; a trace/sampling gate applies only when a `TraceContextProvider`
 * is wired into [LogCapture] (see [untracedPolicy]).
 */
public data class CaptureConfig(
    /**
     * The least severe level to capture. Events below this level are dropped
     * before any `LogRecord` is built or exported. Defaults to [LogLevel.TRACE]
     * (capture everything).
     */
    public val minLevel: LogLevel = LogLevel.TRACE,
    /**
     * What to do with a log emitted outside any active trace, when a
     * [TraceContextProvider] is configured on [LogCapture]. Ignored when no
     * provider is set (M1 always-on capture). Defaults to [UntracedPolicy.CAPTURE].
     */
    public val untracedPolicy: UntracedPolicy = UntracedPolicy.CAPTURE,
    /**
     * Maps a [NormalizedLogEvent] to the `LogRecord` attributes. Defaults to
     * [defaultAttributeMapper], which records the logger name plus the event's
     * own key/value pairs.
     *
     * **Applied at emit time, on the caller that logged** — synchronously inside the
     * `log()`/`append()` call, before the event is queued for the drain coroutine
     * (`LogCapture.resolveAtEdge`). A mapper may therefore fold *ambient* state (the
     * session or game currently in progress, a request id, the current screen) into
     * attributes and trust that a record carries the state the line was emitted
     * under, not whatever it has become by the time the record is drained (#1630).
     *
     * Two consequences:
     * - Keep it **cheap and non-blocking**. It runs on the application's logging
     *   thread, once per captured event, not once per drain batch. It is skipped for
     *   every event that produces no record (#1745): below [minLevel], one of the
     *   exporter's own loggers, or dropped by the trace/sampling gate — an unsampled
     *   trace, or an untraced event under [UntracedPolicy.DROP]. So wiring a
     *   `TraceContextProvider` with `DROP` costs a mapping only for the lines it
     *   keeps.
     * - It should not throw. A mapper that throws drops that one record; the failure
     *   is swallowed rather than propagated into the application's logging call.
     *
     * **This is a process-wide hook — for anything session-scoped, reach for
     * [withLogContext] instead.** One mapper is installed on the whole process's
     * capture edge, so it can only ever fold in whichever session/game/request is
     * *currently armed*. A process that holds two of them at once therefore stamps
     * the second one's lines with the first one's id, and nothing downstream can tell
     * (#1659). Edge resolution does not help: it fixes *when* the mapper is asked,
     * not *which* of the concurrent sessions it is able to see. Use this mapper for
     * facts that really are process-wide (the device id, the build, the logger name)
     * and [withLogContext] for anything bound to a unit of work. Where both set a
     * key, the scope wins.
     */
    public val attributeMapper: (NormalizedLogEvent) -> Map<String, String> = ::defaultAttributeMapper,
)

/**
 * The default [CaptureConfig.attributeMapper]: records the logger name under
 * [LOGGER_NAME_ATTRIBUTE], then the event's own attributes (which therefore win
 * on key collision).
 */
public fun defaultAttributeMapper(event: NormalizedLogEvent): Map<String, String> =
    buildMap {
        put(LOGGER_NAME_ATTRIBUTE, event.loggerName)
        putAll(event.attributes)
    }
