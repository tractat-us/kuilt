package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.ExportResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The shared, platform-independent capture core.
 *
 * Maps a [NormalizedLogEvent] to an OTLP-shaped [LogRecord] and exports it into
 * the durable buffer. Every per-platform capture edge funnels through this one
 * type, so the mapping — level, body, attributes, identity, timestamps — is
 * identical on every target.
 *
 * ## Self-capture exclusion (safety invariant)
 *
 * Capture hooks the process-global logging config, so it sees *every* event in
 * the process — including kuilt's own internal logging. The durable exporter
 * itself logs on its hot path (a buffer-cap eviction warning, store-failure
 * errors), so capturing those would feed a captured eviction-warn back into
 * export → evict again → warn again — a self-sustaining loop that crowds out real
 * application logs. To make that impossible, [capture] drops any event whose
 * `loggerName` is under kuilt's own package (`us.tractat.kuilt`) before building a
 * record. This is a non-negotiable invariant, not a configurable filter: a
 * consumer app is never under that package, so it only ever excludes kuilt
 * internals, and every capture edge inherits it through this one core.
 *
 * ## Injected dependencies
 *
 * Both time and randomness are dependencies, never reached for directly:
 * - [clock] supplies the event timestamps. A test injects a virtual clock; a
 *   production install passes `kotlin.time.Clock.System`.
 * - [random] supplies the fresh 8-byte `recordId` per record. A test injects a
 *   seeded [Random]; a production install passes `Random.Default`.
 *
 * @param exporter the durable log buffer this capture writes into.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of the event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param traceContextProvider optional trace/sampling gate. When `null` (the M1
 *   default) capture is always-on and records carry no trace ids. When set, the
 *   trace is resolved at the synchronous capture edge via [resolveTrace] and
 *   carried on [NormalizedLogEvent.activeTrace]; [capture] then gates on that
 *   snapshot (see [resolveTrace] and [capture]).
 */
public class LogCapture(
    private val exporter: WarpLogRecordExporter,
    private val config: CaptureConfig,
    private val clock: Clock,
    private val random: Random,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    /**
     * Resolve the trace active on the **current call**, for the capture edge to
     * snapshot onto [NormalizedLogEvent.activeTrace] before handing the event off
     * to the drain (#1034).
     *
     * This MUST be invoked synchronously on the thread/coroutine that logged — an
     * ambient [TraceContextProvider] (e.g. one backed by OTel's `Span.current()`)
     * reads the caller's thread/coroutine-local context, which is gone by the time
     * the drain coroutine runs [capture]. Resolving here, at the edge, is the whole
     * fix: [capture] never consults the provider off-thread.
     *
     * Returns `null` when no provider is wired (the M1 always-on default) or when
     * the provider reports no active trace.
     */
    public fun resolveTrace(): ActiveTrace? = traceContextProvider?.current()

    /**
     * Map [event] to a [LogRecord] and export it.
     *
     * Returns the exporter's [ExportResult], or `null` if [event] was dropped
     * before any record was built — because its `loggerName` is one of kuilt's
     * own (`us.tractat.kuilt`) loggers (the self-capture exclusion above),
     * because it was below [CaptureConfig.minLevel], or because the trace gate
     * dropped it: when a [TraceContextProvider] is wired, an active-but-unsampled
     * trace is dropped, and an untraced event is dropped when
     * [CaptureConfig.untracedPolicy] is [UntracedPolicy.DROP]. On a sampled
     * trace the record is stamped with the trace's `traceId`/`spanId`.
     *
     * The gate reads [event]'s pre-resolved [NormalizedLogEvent.activeTrace]
     * (snapshotted at the synchronous edge via [resolveTrace]) — it never calls the
     * provider from this drain-side path, so an ambient context that only exists on
     * the caller is honoured (#1034).
     */
    public suspend fun capture(event: NormalizedLogEvent): ExportResult? {
        if (event.loggerName.startsWith(KUILT_INTERNAL_LOGGER_PREFIX)) return null
        if (event.level.ordinal < config.minLevel.ordinal) return null
        // Trace/sampling gate. A null provider is M1 always-on capture, no stamp.
        // The trace was resolved at the edge (resolveTrace) and rides on the event;
        // this drain-side path never re-consults the provider (#1034).
        var traceId: ByteString? = null
        var spanId: ByteString? = null
        if (traceContextProvider != null) {
            when (val trace = event.activeTrace) {
                null -> if (config.untracedPolicy == UntracedPolicy.DROP) return null
                else -> if (trace.sampled) {
                    traceId = trace.traceId
                    spanId = trace.spanId
                } else {
                    return null // active but unsampled → drop before export
                }
            }
        }
        val now = clock.now()
        val epochNanos = now.epochSeconds * NANOS_PER_SECOND + now.nanosecondsOfSecond
        val record = LogRecord(
            recordId = ByteString(random.nextBytes(RECORD_ID_BYTES)),
            severityNumber = event.level.severityNumber,
            severityText = event.level.severityText,
            body = event.message,
            attributes = config.attributeMapper(event),
            timestampEpochNanos = epochNanos,
            observedEpochNanos = epochNanos,
            traceId = traceId,
            spanId = spanId,
        )
        return exporter.export(record)
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
        private const val NANOS_PER_SECOND = 1_000_000_000L

        // kuilt's own loggers are never captured — see the self-capture exclusion
        // invariant in the class KDoc. A consumer app is never under this package.
        private const val KUILT_INTERNAL_LOGGER_PREFIX = "us.tractat.kuilt"
    }
}
