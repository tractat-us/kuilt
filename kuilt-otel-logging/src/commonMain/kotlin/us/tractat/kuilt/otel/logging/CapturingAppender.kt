package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable

// Explicitly named so the Native self-capture exclusion works (#1003) — and, here,
// so the overflow report below is dropped by that same exclusion before it can be
// queued. See InternalLoggerNameGuardTest.
private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.logging.CapturingAppender")

/**
 * An oshai [Appender] that feeds each event into a [LogCapture] core while
 * delegating to a previously-installed appender (so console output is preserved).
 *
 * oshai's [log] callback is synchronous and may run on any thread, but
 * [LogCapture.capture] is `suspend`. A single dedicated drain coroutine bridges
 * the two: [log] hands events off to a [Channel], and the drain coroutine consumes
 * them in FIFO order. This is the legitimate single-writer channel-drain pattern —
 * it preserves per-producer insertion order without relying on dispatcher
 * confinement for mutual exclusion.
 *
 * ## The queue is bounded, and overflowing it drops the oldest (#2124)
 *
 * The producer is the application's own logging call, and [log] can neither
 * suspend nor fail, so an unbounded queue makes "the drain is slower than the
 * producer" mean **unbounded heap growth in the host application** — during normal
 * operation, not just at teardown. At the field's measured export cost a Debug
 * build on an A12 against a large store structurally cannot sustain one log line
 * per second (#1860), so this is reachable, not theoretical.
 *
 * The queue therefore holds [CAPTURE_QUEUE_CAPACITY] events and drops the **oldest**
 * beyond that:
 * - *Bounded*, because the host application must not pay heap for a slow telemetry
 *   store.
 * - *Drop* rather than suspend, because suspending would apply backpressure to the
 *   application's own logging call — a slow telemetry store must never slow down
 *   the app it is observing.
 * - *Oldest*, matching `BufferPolicy.DROP_OLDEST` in the exporter below it, so the
 *   two buffers on this path behave the same way, and because the newest events are
 *   the ones a post-hoc diagnosis wants.
 * - *Counted*, on [health], because a bound that loses events silently trades a
 *   visible failure for an invisible one.
 *
 * The count is exact: the channel invokes its `onUndeliveredElement` hook once per
 * element it evicts, from inside the same atomic overflow step that evicts it, so
 * there is no separate size estimate to drift.
 *
 * [close] is the teardown counterpart: it stops the appender accepting events and
 * closes the channel so the drain coroutine finishes. Without it, uninstalling by
 * cancelling the drain's scope alone would leave this appender wired into the
 * global logging config, [trySend]-ing into a channel nobody drains — every log
 * line then landing in a full queue and being counted as a drop forever.
 * [LogCaptureInstallation.close] calls this after restoring the previous appender.
 *
 * @param capacity queue depth. Defaults to [CAPTURE_QUEUE_CAPACITY]; overridden
 *   only by tests, which need to overflow a queue cheaply.
 */
internal class CapturingAppender(
    private val capture: LogCapture,
    private val delegate: Appender,
    scope: CoroutineScope,
    private val capacity: Int = CAPTURE_QUEUE_CAPACITY,
) : Appender {
    // A MutableStateFlow owns no CoroutineScope, so the health surface adds no
    // scope ownership to this type. `update {}` is an atomic CAS loop — a real
    // thread-safe primitive, not dispatcher confinement (repo policy). It has to
    // be: onBufferOverflow runs on whichever application thread logged.
    private val healthState = MutableStateFlow(CaptureHealth())

    /** Out-of-band health for this capture edge — see [CaptureHealth]. */
    val health: StateFlow<CaptureHealth> = healthState.asStateFlow()

    private val events = Channel<NormalizedLogEvent>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        // Invoked once per element the channel evicts on overflow, inside the
        // eviction itself — which is what makes droppedEvents exact rather than an
        // estimate derived from a separately-tracked size. Deliberately does NOT
        // log: it runs inside a channel operation, and re-entering the logging
        // facade from there would re-enter log() mid-trySend. reportOverflowOnce()
        // does the reporting afterwards instead.
        onUndeliveredElement = { healthState.update { it.copy(droppedEvents = it.droppedEvents + 1) } },
    )
    private val closed = atomic(false)
    private val overflowReported = atomic(false)

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
        // Resolve HERE — synchronously, on the caller that logged — and snapshot the
        // result onto the event. Both the ambient trace (#1034) and the configured
        // attributeMapper (#1630) depend on state that only exists on this caller
        // right now; by the time the drain coroutine runs capture() the ambient
        // context is gone and ambient app state may have moved on. Resolving
        // off-thread on the drain is the bug; this edge resolution is the fix.
        val resolved = capture.resolveAtEdge(normalized) ?: return
        // Never fails: a DROP_OLDEST channel makes room by evicting its head, and
        // the eviction is what increments health.droppedEvents.
        events.trySend(resolved)
        reportOverflowOnce()
    }

    /**
     * Log — at most once per installation — that the queue has started dropping.
     *
     * Says it out loud so the loss is not silent for a consumer who never reads
     * [health], and says it **once** because the condition that triggers it is the
     * application logging faster than the drain: a line per drop would amplify
     * exactly the overload being reported.
     *
     * Two things keep this from feeding back into capture. It is emitted under a
     * `us.tractat.kuilt.otel.*` logger, which [LogCapture]'s self-capture exclusion
     * drops before anything is queued — so the report can neither be recorded nor
     * consume a queue slot (pinned by `CapturingAppenderBoundedQueueTest`). And it
     * runs *after* [Channel.trySend] returns rather than inside the channel's
     * overflow hook, so the re-entrant [log] the report causes is not nested inside
     * a channel operation. The flag is claimed before the line is emitted, so the
     * re-entrant call returns here immediately even if the exclusion ever changed.
     */
    private fun reportOverflowOnce() {
        if (healthState.value.droppedEvents == 0L) return
        if (!overflowReported.compareAndSet(expect = false, update = true)) return
        logger.warn {
            "log capture queue overflowed (capacity=$capacity): the exporter is draining slower than this " +
                "application logs, so the oldest captured events are being dropped. Read " +
                "LogCaptureInstallation.health.droppedEvents for the running total."
        }
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
