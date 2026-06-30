package us.tractat.kuilt.otel.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.logging.EXCEPTION_MESSAGE_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogCapture
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.otel.logging.NormalizedLogEvent

/**
 * A logback [AppenderBase] that captures raw-SLF4J output into a kuilt
 * [LogCapture] core.
 *
 * The uniform capture edge ([us.tractat.kuilt.otel.logging.installLogCapture])
 * routes `kotlin-logging` through its direct logger factory, which on the JVM
 * *stops* `kotlin-logging` from reaching SLF4J while capture is on. The app's own
 * logback/log4j configuration and **other libraries' raw SLF4J logs** (frameworks,
 * transitive deps) therefore go uncaptured. This appender is the **optional,
 * additive** JVM/Android add-on that recovers that "bonus scope": installed
 * alongside the uniform edge (it does not replace it), it feeds the *same*
 * [LogCapture] / `WarpLogRecordExporter` so SLF4J-side records land in the same
 * buffer.
 *
 * ## Sync→suspend bridge
 *
 * logback's [append] runs synchronously on the logging thread, but
 * [LogCapture.capture] is `suspend`. A single dedicated drain coroutine bridges
 * the two: [append] hands each event off to an unbounded [Channel], and the drain
 * coroutine consumes them in FIFO order. This is the legitimate single-writer
 * channel-drain pattern — it preserves insertion order without relying on
 * dispatcher confinement for mutual exclusion.
 *
 * @param capture the shared capture core mapped events are exported through.
 * @param scope the [CoroutineScope] the drain coroutine runs on. Its lifetime
 *   bounds capture: cancelling it stops capture. Inject a test scope in tests; an
 *   application-owned scope in production.
 */
public class KuiltLogbackAppender(
    private val capture: LogCapture,
    scope: CoroutineScope,
) : AppenderBase<ILoggingEvent>() {
    private val events = Channel<NormalizedLogEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                // Best-effort: a failed capture must never crash the app's logging
                // path, so a failure is dropped. runCatchingCancellable still
                // rethrows CancellationException for clean teardown.
                runCatchingCancellable { capture.capture(event) }
            }
        }
    }

    protected override fun append(eventObject: ILoggingEvent) {
        val normalized = eventObject.normalize() ?: return
        events.trySend(normalized)
    }
}

private fun ILoggingEvent.normalize(): NormalizedLogEvent? {
    val mappedLevel = level.toLogLevel() ?: return null
    val attributes = buildMap {
        // MDC context: the per-thread diagnostic context active when the event fired.
        mdcPropertyMap?.forEach { (key, value) -> if (value != null) put(key, value) }
        // SLF4J 2.0 structured key/value pairs (logger.atX().addKeyValue(...)).
        keyValuePairs?.forEach { pair -> pair.value?.let { put(pair.key, it.toString()) } }
        throwableProxy?.message?.let { put(EXCEPTION_MESSAGE_ATTRIBUTE, it) }
    }
    return NormalizedLogEvent(
        level = mappedLevel,
        loggerName = loggerName,
        message = formattedMessage,
        attributes = attributes,
    )
}

private fun Level.toLogLevel(): LogLevel? = when (toInt()) {
    Level.TRACE_INT -> LogLevel.TRACE
    Level.DEBUG_INT -> LogLevel.DEBUG
    Level.INFO_INT -> LogLevel.INFO
    Level.WARN_INT -> LogLevel.WARN
    Level.ERROR_INT -> LogLevel.ERROR
    // ALL / OFF and any unknown level carry no OTLP severity — drop them.
    else -> null
}
