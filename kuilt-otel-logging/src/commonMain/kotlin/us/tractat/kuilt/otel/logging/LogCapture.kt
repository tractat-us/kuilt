package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.runCatchingCancellable
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
 * the process. The durable exporter itself logs on its hot path (a buffer-cap
 * eviction warning, store-failure errors), so capturing those would feed a
 * captured eviction-warn back into export → evict again → warn again — a
 * self-sustaining loop that crowds out real application logs. To make that
 * impossible, any event whose `loggerName` is under the exporter's own package
 * (`us.tractat.kuilt.otel`) is dropped before a record is built. Both [capture]
 * and [captureAll] reach that decision through one private mapper, so batching a
 * run of events cannot weaken it into a per-run filter.
 *
 * The exclusion is scoped to **only** the exporter's own `us.tractat.kuilt.otel.*`
 * loggers — narrow enough to break the export feedback loop, but no broader. In
 * particular it does **not** exclude kuilt's *library* loggers
 * (`us.tractat.kuilt.session.*`, `...liveness.*`, `...raft.*`, `...nw.*`, …): a
 * consumer that uses kuilt as its networking library depends on those library
 * diagnostics being captured just like its own application logs, and any consumer
 * running under the `us.tractat.kuilt` package (but outside `.otel`) is captured
 * normally. This is a non-negotiable invariant, not a configurable filter, and
 * every capture edge inherits it through this one core.
 *
 * ## Injected dependencies
 *
 * Both time and randomness are dependencies, never reached for directly:
 * - [clock] supplies the record timestamps. A test injects a virtual clock; a
 *   production install passes `kotlin.time.Clock.System`.
 * - [random] supplies the fresh 8-byte `recordId` per record. A test injects a
 *   seeded [Random]; a production install passes `Random.Default`.
 *
 * ## Edge resolution (emit-time semantics)
 *
 * Anything that depends on *when and where the line was logged* is resolved at the
 * **synchronous capture edge**, on the caller, and carried on the queued
 * [NormalizedLogEvent] — never re-derived on the drain coroutine. That covers the
 * ambient trace context (#1034), the [CaptureConfig.attributeMapper] (#1630), the
 * scope-bound log context ([withLogContext], #1659) and the instant the line was
 * logged (#1993). A queueing edge calls [resolveAtEdge] once; [capture] then reads
 * the snapshot. Resolving any of them on the drain stamps records with whatever the
 * ambient state has become by then, which the consumer cannot detect or repair.
 *
 * The last two of those are the same defect at two scales, and the second is why
 * edge resolution alone was not enough. [CaptureConfig.attributeMapper] is installed
 * once per **process**, so a process holding several sessions at once has no mapper
 * that can be right for all of them: resolving it at the edge fixes *when* it is
 * asked, not *which session* it can see. [withLogContext] binds the attributes to a
 * scope so the question becomes per-emitter, and [withScopedContext] merges them
 * here, at the same edge (#1659).
 *
 * @param exporter the durable log buffer this capture writes into.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of the record timestamps (required — never the wall clock).
 *   Read twice per record on a queueing edge: once by [resolveAtEdge] for the event
 *   time, once by [capture] for the observed time.
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param traceContextProvider optional trace/sampling gate. When `null` (the M1
 *   default) capture is always-on and records carry no trace ids. When set, the
 *   trace is resolved at the synchronous capture edge via [resolveAtEdge], which
 *   also applies the gate there (#1745) and carries the trace on
 *   [NormalizedLogEvent.activeTrace] for [capture] to stamp from (see
 *   [resolveAtEdge] and [capture]).
 */
public class LogCapture(
    private val exporter: WarpLogRecordExporter,
    private val config: CaptureConfig,
    private val clock: Clock,
    private val random: Random,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    /**
     * Snapshot everything that must be sampled **at the moment the line was
     * logged** onto [event], for a queueing capture edge to hand to the drain.
     *
     * This is the one call a capture edge makes, and it MUST run synchronously on
     * the thread/coroutine that logged. It resolves all three emit-time-sensitive
     * inputs:
     * - the ambient trace ([resolveTrace] → [NormalizedLogEvent.activeTrace], #1034)
     *   — an ambient [TraceContextProvider] reads the caller's thread/coroutine-local
     *   context, which is gone by the time the drain runs [capture];
     * - the [CaptureConfig.attributeMapper] ([NormalizedLogEvent.resolvedAttributes],
     *   #1630) — a mapper that folds ambient state (the session in progress, a request
     *   id) into attributes must see the state the line was emitted under, not
     *   whatever it has become by drain time;
     * - the instant itself ([NormalizedLogEvent.emittedEpochNanos], #1993) — the event
     *   time is *now*, on this caller, and no later reading of the clock can recover
     *   it. A value already on the event is left alone, so an edge whose platform
     *   event carries its own emit time may supply it.
     *
     * Returns `null` when the event carries nothing to export — the edge should
     * then drop it rather than queue it. That is either because [capture] would
     * drop it anyway, or because the configured [CaptureConfig.attributeMapper]
     * threw. A throwing mapper drops just that record and never propagates into the
     * application's logging call, exactly as it did when the mapper still ran on the
     * drain behind the appender's best-effort guard.
     *
     * **Every drop [capture] would make is decided here, before the mapper runs**
     * (#1745): the cheap pre-record drops ([droppedBeforeRecord] — below
     * [CaptureConfig.minLevel], or one of the exporter's own loggers) and the
     * trace/sampling gate ([droppedByTraceGate] — an unsampled trace, or an untraced
     * event under [UntracedPolicy.DROP]). Both gates read exactly what [recordFor]
     * later reads, so an event that survives this call is one no gate can drop, and
     * a mapper is never paid for a record that will not exist. That matters most for
     * a consumer running `DROP`, who has by definition asked for most lines to be
     * discarded — the configuration in which the wasted mapping was largest.
     */
    public fun resolveAtEdge(event: NormalizedLogEvent): NormalizedLogEvent? {
        if (droppedBeforeRecord(event)) return null
        // Resolve the trace BEFORE the mapper: it is what decides whether this event
        // produces a record at all, and the mapper runs on the application's logging
        // thread. Both still resolve here, on the caller, so emit-time semantics are
        // unchanged for either (#1034, #1630).
        val trace = resolveTrace()
        if (droppedByTraceGate(trace)) return null
        val attributes = runCatchingCancellable { config.attributeMapper(event) }.getOrNull() ?: return null
        return event.copy(
            activeTrace = trace,
            resolvedAttributes = withScopedContext(attributes),
            emittedEpochNanos = event.emittedEpochNanos ?: nowEpochNanos(),
        )
    }

    /**
     * Resolve the trace active on the **current call**.
     *
     * Prefer [resolveAtEdge], which calls this and resolves the attribute mapper in
     * the same step; a capture edge that only calls this leaves
     * [NormalizedLogEvent.resolvedAttributes] unresolved and reintroduces #1630.
     *
     * Returns `null` when no provider is wired (the M1 always-on default) or when
     * the provider reports no active trace.
     */
    public fun resolveTrace(): ActiveTrace? = traceContextProvider?.current()

    /**
     * Map [event] to a [LogRecord] and export it.
     *
     * Returns the exporter's [ExportResult], or `null` if [event] was dropped
     * before any record was built — because its `loggerName` is one of the
     * exporter's own (`us.tractat.kuilt.otel`) loggers (the self-capture exclusion
     * above), because it was below [CaptureConfig.minLevel], or because the trace gate
     * dropped it: when a [TraceContextProvider] is wired, an active-but-unsampled
     * trace is dropped, and an untraced event is dropped when
     * [CaptureConfig.untracedPolicy] is [UntracedPolicy.DROP]. On a sampled
     * trace the record is stamped with the trace's `traceId`/`spanId`.
     *
     * The gate reads [event]'s pre-resolved [NormalizedLogEvent.activeTrace], and
     * the record's attributes come from its pre-resolved
     * [NormalizedLogEvent.resolvedAttributes] — both snapshotted at the synchronous
     * edge via [resolveAtEdge]. Neither the trace provider nor the attribute mapper
     * is consulted from this drain-side path, so ambient state that only exists on
     * the caller is honoured (#1034, #1630).
     *
     * The one exception is a caller that invokes this directly from its own log
     * site without going through an edge: `resolvedAttributes` is then `null` and
     * the mapper is applied here, which for such a caller *is* emit time. That
     * caller is also the only one for whom the gate here can still fire — an
     * edge-resolved event has already been through the same [droppedByTraceGate] on
     * the same trace, so for it this gate is a no-op that only stamps (#1745).
     *
     * See [captureAll] for the batched counterpart — same mapping, same gates, one
     * export for a whole run of events.
     */
    public suspend fun capture(event: NormalizedLogEvent): ExportResult? {
        val record = recordFor(event) ?: return null
        return exporter.export(record)
    }

    /**
     * Map a **run** of events to `LogRecord`s and export them as one write turn.
     *
     * The batched counterpart of [capture], and the drain's entry point since #2194:
     * the exporter's fixed per-turn cost — one CRDT append pass, one CBOR encode, one
     * segment write — is paid once for the whole run instead of once per line.
     *
     * Every per-event decision is unchanged and still per-event. The self-capture
     * exclusion, the [CaptureConfig.minLevel] gate and the trace/sampling gate each
     * drop their own events out of the run before any record is built; the survivors
     * are exported together. Durability is unchanged — nothing is held back waiting
     * for the run to grow (see `WarpLogRecordExporter.export`).
     *
     * Returns `null` when the run produced no records at all — either it was empty or
     * every event was dropped — so a caller can tell "nothing to do" from an export
     * result.
     */
    public suspend fun captureAll(events: List<NormalizedLogEvent>): ExportResult? {
        val records = events.mapNotNull { event -> recordFor(event) }
        if (records.isEmpty()) return null
        return exporter.export(records)
    }

    /**
     * The [LogRecord] [event] produces, or `null` if it is dropped before one is built.
     *
     * The single decision point shared by [capture] and [captureAll], so a per-event
     * gate cannot come to mean two different things on the two paths. Every gate here
     * reads the values [resolveAtEdge] snapshotted on the caller — never the ambient
     * provider or the mapper — so emit-time semantics survive the drain (#1034, #1630).
     */
    private fun recordFor(event: NormalizedLogEvent): LogRecord? {
        if (droppedBeforeRecord(event)) return null
        // Trace/sampling gate. A null provider is M1 always-on capture, no stamp.
        // The trace was resolved at the edge (resolveAtEdge) and rides on the event;
        // this drain-side path never re-consults the provider (#1034).
        var traceId: ByteString? = null
        var spanId: ByteString? = null
        if (traceContextProvider != null) {
            val trace = event.activeTrace
            if (droppedByTraceGate(trace)) return null
            if (trace != null) {
                traceId = trace.traceId
                spanId = trace.spanId
            }
        }
        val observedEpochNanos = nowEpochNanos()
        return LogRecord(
            recordId = ByteString(random.nextBytes(RECORD_ID_BYTES)),
            severityNumber = event.level.severityNumber,
            severityText = event.level.severityText,
            body = event.message,
            // Resolved at the synchronous edge (#1630). The fallback covers a caller
            // that drives capture() straight from its log site — there this call IS
            // the edge, so applying the mapper AND merging the scoped log context
            // here is still emit time. The merge is deliberately inside the fallback
            // only: for an edge-resolved event the scoped context already rode in on
            // resolvedAttributes, and re-reading the slot from the drain coroutine
            // would stamp records with whatever scope the drain happens to be in —
            // #1630's bug, reintroduced through #1659's feature.
            attributes = event.resolvedAttributes ?: withScopedContext(config.attributeMapper(event)),
            // OTLP's timeUnixNano: when the event occurred. Read at the synchronous
            // edge (#1993). The fallback covers a caller that drives capture()
            // straight from its log site — there this call IS the edge, so the two
            // instants are genuinely the same, not a collapsed one.
            timestampEpochNanos = event.emittedEpochNanos ?: observedEpochNanos,
            // OTLP's observedTimeUnixNano: when capture saw the event. This is the
            // drain instant, and it belongs on the drain.
            observedEpochNanos = observedEpochNanos,
            traceId = traceId,
            spanId = spanId,
        )
    }

    /**
     * [attributes] with the log context bound to the **current scope** merged over
     * it — the scope-bound half of #1659.
     *
     * Called only from a position that is genuinely the synchronous emit edge:
     * [resolveAtEdge], and [recordFor]'s no-edge fallback. The slot it reads is
     * execution-local, so reading it anywhere else — the drain coroutine above all —
     * answers about the wrong scope.
     *
     * The scoped attributes win on a key collision: "narrower scope wins" is the one
     * precedence rule, and a process-global [CaptureConfig.attributeMapper] is the
     * widest scope there is. See [withLogContext].
     */
    private fun withScopedContext(attributes: Map<String, String>): Map<String, String> {
        val scoped = currentLogContext()
        return if (scoped.isEmpty()) attributes else attributes + scoped
    }

    /**
     * The injected [clock] read as epoch nanoseconds — the one conversion, shared by
     * the edge-side event time and the drain-side observed time so the two fields
     * cannot come to be computed differently.
     */
    private fun nowEpochNanos(): Long = with(clock.now()) {
        epochSeconds * NANOS_PER_SECOND + nanosecondsOfSecond
    }

    /**
     * Whether [event] is dropped before any `LogRecord` is built — the exporter's
     * own loggers (the self-capture exclusion invariant) or below
     * [CaptureConfig.minLevel]. Shared by [recordFor] — and so by both [capture] and
     * [captureAll] — and by [resolveAtEdge], so the edge never pays the attribute
     * mapper for a line that produces no record.
     */
    private fun droppedBeforeRecord(event: NormalizedLogEvent): Boolean =
        event.loggerName.startsWith(KUILT_INTERNAL_LOGGER_PREFIX) ||
            event.level.ordinal < config.minLevel.ordinal

    /**
     * Whether the trace/sampling gate drops an event whose active trace is [trace] —
     * an active-but-unsampled trace, or an untraced event when
     * [CaptureConfig.untracedPolicy] is [UntracedPolicy.DROP]. With no
     * [traceContextProvider] wired this is M1 always-on capture and nothing is
     * dropped, whatever the policy says.
     *
     * **The single spelling of the gate, and deliberately so.** It is asked twice —
     * by [resolveAtEdge] on the resolved trace, so the [CaptureConfig.attributeMapper]
     * is not paid for a record that will not exist (#1745), and by [recordFor] on the
     * trace the event carries, which is the same value and is the live gate for a
     * caller that drives [capture] straight from its log site with no edge in front
     * of it. Two hand-written copies of a gate that must agree is a worse defect than
     * one gate in the wrong place, so there is only ever one.
     */
    private fun droppedByTraceGate(trace: ActiveTrace?): Boolean {
        if (traceContextProvider == null) return false
        return if (trace == null) config.untracedPolicy == UntracedPolicy.DROP else !trace.sampled
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
        private const val NANOS_PER_SECOND = 1_000_000_000L

        // Only the exporter's own loggers are excluded — see the self-capture
        // exclusion invariant in the class KDoc. This is the exporter package, NOT
        // kuilt's whole namespace: kuilt library loggers are captured for consumers.
        private const val KUILT_INTERNAL_LOGGER_PREFIX = "us.tractat.kuilt.otel"
    }
}
