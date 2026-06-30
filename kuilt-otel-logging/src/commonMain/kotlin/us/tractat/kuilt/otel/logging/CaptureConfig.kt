package us.tractat.kuilt.otel.logging

/** Attribute key under which [defaultAttributeMapper] records the logger name. */
public const val LOGGER_NAME_ATTRIBUTE: String = "logger.name"

/** Attribute key under which a capture edge may record an exception's message. */
public const val EXCEPTION_MESSAGE_ATTRIBUTE: String = "exception.message"

/**
 * Policy for the capture core: which events to keep and how to shape their
 * attributes.
 *
 * M1 capture is **always-on** — there is no trace/sampling gate here (that is a
 * later milestone). The only filter is [minLevel].
 */
public data class CaptureConfig(
    /**
     * The least severe level to capture. Events below this level are dropped
     * before any `LogRecord` is built or exported. Defaults to [LogLevel.TRACE]
     * (capture everything).
     */
    public val minLevel: LogLevel = LogLevel.TRACE,
    /**
     * Maps a [NormalizedLogEvent] to the `LogRecord` attributes. Defaults to
     * [defaultAttributeMapper], which records the logger name plus the event's
     * own key/value pairs.
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
