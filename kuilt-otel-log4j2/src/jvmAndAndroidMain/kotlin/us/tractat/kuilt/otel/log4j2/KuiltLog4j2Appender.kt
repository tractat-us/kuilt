package us.tractat.kuilt.otel.log4j2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.spi.StandardLevel
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.logging.EXCEPTION_MESSAGE_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogCapture
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.otel.logging.NormalizedLogEvent

/**
 * A log4j2 [AbstractAppender] that captures raw-SLF4J/log4j2 output into a kuilt
 * [LogCapture] core — the log4j2 sibling of `KuiltLogbackAppender`.
 *
 * The uniform capture edge ([us.tractat.kuilt.otel.logging.installLogCapture])
 * routes `kotlin-logging` through its direct logger factory, which on the JVM
 * *stops* `kotlin-logging` from reaching SLF4J while capture is on. The app's own
 * log4j2 configuration and **other libraries' raw log4j2/SLF4J logs** (frameworks,
 * transitive deps) therefore go uncaptured. This appender is the **optional,
 * additive** JVM/Android add-on that recovers that "bonus scope": installed
 * alongside the uniform edge (it does not replace it), it feeds the *same*
 * [LogCapture] / `WarpLogRecordExporter` so log4j2-side records land in the same
 * buffer.
 *
 * ## Sync→suspend bridge
 *
 * log4j2's [append] runs synchronously on the logging thread, but
 * [LogCapture.capture] is `suspend`. A single dedicated drain coroutine bridges
 * the two: [append] hands each event off to an unbounded [Channel], and the drain
 * coroutine consumes them in FIFO order. This is the legitimate single-writer
 * channel-drain pattern — it preserves insertion order without relying on
 * dispatcher confinement for mutual exclusion.
 *
 * ## Teardown
 *
 * Stop this appender the standard log4j2 way — [stop] (after removing it from the
 * `LoggerConfig`). [stop] both halts the log path and **closes the channel** so the
 * drain coroutine completes and the `capture → exporter → store` graph is released.
 * Cancelling [scope] is only a backstop: cancelling it while the appender is still
 * attached and started would kill the drain while [append] keeps [Channel.trySend]-ing
 * into a channel nobody drains — an unbounded leak. Always [stop] (or remove) to tear
 * down; don't rely on scope-cancellation alone.
 *
 * @param name the appender name (log4j2 requires it at construction).
 * @param capture the shared capture core mapped events are exported through.
 * @param scope the [CoroutineScope] the drain coroutine runs on. A backstop on the
 *   appender's lifetime, not the primary teardown — see *Teardown* above. Inject a
 *   test scope in tests; an application-owned scope in production.
 */
public class KuiltLog4j2Appender(
    name: String,
    private val capture: LogCapture,
    scope: CoroutineScope,
) : AbstractAppender(name, null, null, /* ignoreExceptions = */ true, Property.EMPTY_ARRAY) {
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

    override fun append(event: LogEvent) {
        val normalized = event.normalize() ?: return
        events.trySend(normalized)
    }

    /**
     * Stop the appender: log4j2 flips the lifecycle to stopped (so [append] is no
     * longer invoked) and we close the event channel, completing the drain coroutine
     * and releasing the `capture → exporter → store` graph. Idempotent — closing an
     * already-closed channel is a no-op, and a [Channel.trySend] racing an in-flight
     * [append] after close returns a closed result and is dropped harmlessly.
     */
    override fun stop() {
        super.stop()
        events.close()
    }
}

private fun LogEvent.normalize(): NormalizedLogEvent? {
    val mappedLevel = level.toLogLevel() ?: return null
    val attributes = buildMap {
        // Thread context (log4j2's MDC): the per-thread diagnostic context active
        // when the event fired.
        contextData?.toMap()?.forEach { (key, value) -> if (value != null) put(key, value) }
        thrown?.message?.let { put(EXCEPTION_MESSAGE_ATTRIBUTE, it) }
    }
    return NormalizedLogEvent(
        level = mappedLevel,
        loggerName = loggerName.orEmpty(),
        message = message?.formattedMessage,
        attributes = attributes,
    )
}

private fun Level.toLogLevel(): LogLevel? = when (standardLevel) {
    // FATAL has no OTLP peer above ERROR — fold it into ERROR.
    StandardLevel.FATAL, StandardLevel.ERROR -> LogLevel.ERROR
    StandardLevel.WARN -> LogLevel.WARN
    StandardLevel.INFO -> LogLevel.INFO
    StandardLevel.DEBUG -> LogLevel.DEBUG
    StandardLevel.TRACE -> LogLevel.TRACE
    // OFF / ALL and any unknown level carry no OTLP severity — drop them.
    else -> null
}
