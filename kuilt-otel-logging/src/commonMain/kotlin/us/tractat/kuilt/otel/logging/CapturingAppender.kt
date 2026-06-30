package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * An oshai [Appender] that feeds each event into a [LogCapture] core while
 * delegating to a previously-installed appender (so console output is preserved).
 *
 * oshai's [log] callback is synchronous and may run on any thread, but
 * [LogCapture.capture] is `suspend`. A single dedicated drain coroutine bridges
 * the two: [log] hands events off to an unbounded [Channel], and the drain
 * coroutine consumes them in FIFO order. This is the legitimate single-writer
 * channel-drain pattern — it preserves per-producer insertion order without
 * relying on dispatcher confinement for mutual exclusion.
 */
internal class CapturingAppender(
    private val capture: LogCapture,
    private val delegate: Appender,
    scope: CoroutineScope,
) : Appender {
    private val events = Channel<NormalizedLogEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                // Best-effort: a failed export must never crash the app's logging
                // path, and must never re-log through this same appender (a capture
                // feedback loop), so a failure is dropped. runCatchingCancellable
                // still rethrows CancellationException for clean teardown.
                runCatchingCancellable { capture.capture(event) }
            }
        }
    }

    override fun log(loggingEvent: KLoggingEvent) {
        delegate.log(loggingEvent)
        val normalized = loggingEvent.normalize() ?: return
        events.trySend(normalized)
    }
}

private fun KLoggingEvent.normalize(): NormalizedLogEvent? {
    val mappedLevel = level.toLogLevel() ?: return null
    val attributes = buildMap {
        payload?.forEach { (key, value) -> if (value != null) put(key, value.toString()) }
        cause?.message?.let { put(EXCEPTION_MESSAGE_ATTRIBUTE, it) }
    }
    return NormalizedLogEvent(
        level = mappedLevel,
        loggerName = loggerName,
        message = message,
        attributes = attributes,
    )
}

private fun Level.toLogLevel(): LogLevel? = when (this) {
    Level.TRACE -> LogLevel.TRACE
    Level.DEBUG -> LogLevel.DEBUG
    Level.INFO -> LogLevel.INFO
    Level.WARN -> LogLevel.WARN
    Level.ERROR -> LogLevel.ERROR
    Level.OFF -> null
}
