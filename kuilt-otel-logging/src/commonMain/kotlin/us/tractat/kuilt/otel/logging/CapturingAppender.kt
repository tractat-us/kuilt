package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.Level
import kotlinx.atomicfu.atomic
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
 *
 * [close] is the teardown counterpart: it stops the appender accepting events and
 * closes the channel so the drain coroutine finishes. Without it, uninstalling by
 * cancelling the drain's scope alone would leave this appender wired into the
 * global logging config, [trySend]-ing into an unbounded channel nobody drains —
 * an unbounded memory leak. [LogCaptureInstallation.close] calls this after
 * restoring the previous appender.
 */
internal class CapturingAppender(
    private val capture: LogCapture,
    private val delegate: Appender,
    scope: CoroutineScope,
) : Appender {
    private val events = Channel<NormalizedLogEvent>(Channel.UNLIMITED)
    private val closed = atomic(false)

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
        if (closed.value) return
        delegate.log(loggingEvent)
        val normalized = loggingEvent.normalize() ?: return
        // Resolve the trace HERE — synchronously, on the caller that logged — and
        // snapshot it onto the event. An ambient TraceContextProvider reads the
        // caller's thread/coroutine-local context, which is gone by the time the
        // drain coroutine runs capture(). Resolving off-thread on the drain is the
        // #1034 bug; this edge resolution is the fix.
        events.trySend(normalized.copy(activeTrace = capture.resolveTrace()))
    }

    /**
     * Stop capturing: make [log] a no-op and close the channel so the drain
     * coroutine completes. Idempotent — safe to call more than once.
     */
    fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            events.close()
        }
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
