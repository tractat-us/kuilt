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

/**
 * An oshai [Appender] that feeds each event into a [LogCapture] core while
 * delegating to a previously-installed appender (so console output is preserved).
 *
 * oshai's [log] callback is synchronous and may run on any thread, but
 * [LogCapture.captureAll] is `suspend`. A single dedicated drain coroutine bridges
 * the two: [log] hands events off to a [Channel], and the drain coroutine consumes
 * them in FIFO order. This is the legitimate single-writer channel-drain pattern —
 * it preserves per-producer insertion order without relying on dispatcher
 * confinement for mutual exclusion.
 *
 * ## The drain takes what is already queued, as one export turn (#2194)
 *
 * It blocks for the first event, then greedily takes whatever is **already
 * enqueued** behind it — up to [maxBatchSize] — and hands the run to
 * [LogCapture.captureAll], which exports it as a single write turn. The exporter's
 * fixed per-turn cost (one CRDT append pass, one CBOR encode, one segment write) is
 * then paid once for the whole run instead of once per line.
 *
 * **Opportunistic, not timed.** There is no flush interval and no clock: nothing is
 * ever held back in the hope more arrives, so a lone line on an idle app is exported
 * with exactly the latency and durability it had before, and a crash can lose nothing
 * a per-event drain would have kept. A batch forms only when the application is
 * outrunning the drain, which is precisely the case worth amortising — and it
 * self-equilibrates there, because a larger batch drains faster per line.
 *
 * ## The queue is bounded, and overflowing it drops the oldest (#2124)
 *
 * The producer is the application's own logging call, and [log] can neither
 * suspend nor fail, so an unbounded queue makes "the drain is slower than the
 * producer" mean **unbounded heap growth in the host application** — during normal
 * operation, not just at teardown. At the field's measured **per-record** export
 * cost — a ~9 ms floor on a Debug A12 that never amortised, plus a growing Θ(N)
 * term (#1860) — a burst structurally could not be drained, so this was reachable
 * at ordinary logging rates. Since #2194 the drain exports what is already queued
 * as one turn, so that fixed cost is divided by the batch and the bound is far
 * harder to reach. It is not gone: the queue is what absorbs a burst the drain
 * cannot swallow in one turn, and an overflow now means the application is
 * outrunning an *amortised* drain — a much stronger signal than it used to be, and
 * one worth acting on rather than tuning away.
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
 * @param maxBatchSize the most events one drain turn exports at once. Defaults to
 *   [CAPTURE_BATCH_MAX]; overridden only by tests, which need several turns cheaply.
 */
internal class CapturingAppender(
    private val capture: LogCapture,
    private val delegate: Appender,
    scope: CoroutineScope,
    private val capacity: Int = CAPTURE_QUEUE_CAPACITY,
    private val maxBatchSize: Int = CAPTURE_BATCH_MAX,
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
        require(maxBatchSize >= 1) { "maxBatchSize must be at least 1; got $maxBatchSize" }
        scope.launch { drain() }
    }

    /**
     * Consume the queue in runs: block for one event, take whatever is already
     * behind it, export the run as one turn, repeat until the channel closes.
     *
     * The greedy second loop is what makes this opportunistic rather than timed —
     * [Channel.tryReceive] never suspends, so it can only find company that was
     * *already* queued. Nothing waits for a batch to grow.
     */
    private suspend fun drain() {
        val batch = ArrayList<NormalizedLogEvent>(maxBatchSize)
        while (true) {
            // receiveCatching() rather than `for (event in events)` because the first
            // element and the rest are now taken by different means; a null here is
            // the closed channel, which is the same termination `for` gave.
            val first = events.receiveCatching().getOrNull() ?: break
            batch += first
            while (batch.size < maxBatchSize) {
                batch += events.tryReceive().getOrNull() ?: break
            }
            // Best-effort: a failed export must never crash the app's logging path,
            // and must never re-log through this same appender (a capture feedback
            // loop), so a failure is dropped. runCatchingCancellable still rethrows
            // CancellationException for clean teardown.
            //
            // `batch` is safe to reuse: captureAll maps it to records and returns
            // before this line does, so nothing downstream holds the list we clear.
            runCatchingCancellable { capture.captureAll(batch) }
            batch.clear()
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
     *
     * ## Why the logger is built here, and why nothing escapes
     *
     * [KotlinLogging.logger] resolves the configured factory **eagerly**, and that
     * resolution can fail — on the JVM the auto-detected default is the SLF4J
     * factory, which throws `NoClassDefFoundError` when no `slf4j-api` is on the
     * runtime classpath (kotlin-logging 8.x makes the binding optional). Held as a
     * file-level `val` it would run in the file facade's `<clinit>`, triggered by
     * the *first* [log] call — putting a throwing initializer on the appender's hot
     * path, and poisoning the facade class for the rest of the process once it
     * threw. Building it here instead means it is resolved at most once per
     * appender, only if the queue actually overflows, and only after
     * [installLogCapture] has configured the factory it intends.
     *
     * The `catch` is the structural half: this runs **inside the application's own
     * logging call**, where nothing kuilt does may propagate. That discipline is
     * already the module's rule — a throwing `CaptureConfig.attributeMapper` loses
     * its record rather than surfacing inside `log()` — and it applies with more
     * force here, because a diagnostic about dropped telemetry must never be the
     * thing that breaks the app's logging. A failure is not recorded anywhere: the
     * count on [health] still stands, and it — not this line — is the load-bearing
     * signal, read in-process rather than through the pipeline that is failing.
     */
    private fun reportOverflowOnce() {
        if (healthState.value.droppedEvents == 0L) return
        if (!overflowReported.compareAndSet(expect = false, update = true)) return
        try {
            // Explicitly named so the Native self-capture exclusion works (#1003)
            // and so this very line is excluded from capture. Never the empty-lambda
            // form — see InternalLoggerNameGuardTest.
            KotlinLogging.logger(OVERFLOW_LOGGER_NAME).warn {
                "log capture queue overflowed (capacity=$capacity): this application is logging faster than " +
                    "the exporter can drain, even batched, so the oldest captured events are being dropped. " +
                    "Read LogCaptureInstallation.health.value.droppedEvents for the running total."
            }
        } catch (ignoredReportFailure: Throwable) {
            // Deliberately swallowed, and deliberately NOT rethrowing cancellation:
            // log() is a synchronous framework callback on the application's own
            // thread, not a coroutine, so there is no cancellation of ours to honour
            // here — only a logging backend's failure, which must not reach the
            // caller. The once-flag is already claimed, so a broken backend costs
            // one failed attempt, not one per log line.
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

    private companion object {
        /**
         * The overflow report's logger name.
         *
         * Must stay under `LogCapture`'s `us.tractat.kuilt.otel` self-capture
         * exclusion prefix — that is what stops the report re-entering the queue it
         * is reporting on. Pinned by
         * `CapturingAppenderBoundedQueueTest.theOverflowReportCannotReEnterCapture`.
         */
        private const val OVERFLOW_LOGGER_NAME = "us.tractat.kuilt.otel.logging.CapturingAppender"
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
