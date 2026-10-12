package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Clock

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpOtlpBridge")

/**
 * Drains converged CRDTs — spans, logs, and metrics — to an OTLP-capable [OtlpEdge],
 * reconciling each signal by its own producer-local digest so a re-drain sends only
 * what the endpoint lacks.
 *
 * On each [drain] call, per signal, the bridge:
 * 1. Snapshots the local CRDT.
 * 2. Fetches a compact digest from the edge (what this producer already delivered).
 * 3. Computes the delta — records present locally but absent from the digest (for
 *    metrics: series whose value-hash advanced).
 * 4. Sends only the delta.
 *
 * Spans additionally carry inferred causal [SpanLink]s (#846): the bridge runs
 * [inferCausalLinks] over the **full** span snapshot (so a predecessor already
 * delivered on an earlier drain still resolves), filters the links to those whose
 * `fromSpanId` is in the delta, and threads them to [OtlpEdge.send] for emission on
 * the OTLP `Span.links` wire field.
 *
 * ## Why reconcile-by-digest?
 *
 * A naive replay would retransmit the entire buffer on every reconnect. A
 * digest-gated delta means the common case (edge already has the record) never
 * touches the wire, offline-then-reconnect sends only the gap, and the CRDT's
 * idempotent merge makes retries harmless.
 *
 * ## Best-effort, per-signal isolation
 *
 * Each signal is drained independently and best-effort: a failing signal never
 * aborts the others, and its CRDT is left intact for the next attempt. The result
 * says which signals failed and why — [DrainResult.Partial] when some attempted
 * signal succeeded, [DrainResult.Failure] when none did — so a signal that fails on
 * every drain is visible to the caller, not only in a debug log line.
 *
 * @param telemetry the [WarpTelemetry] whose span/log/metric exporters are drained.
 * @param clock observation time stamped onto metric points. Required — inject a fixed
 *   clock in tests; never a real-dispatcher/wall-clock default that decouples from
 *   virtual time.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpOtlpBridge
 */
public class WarpOtlpBridge(
    private val telemetry: WarpTelemetry,
    private val clock: Clock,
) {

    /**
     * Drain everything the edge is missing, across all three signals.
     *
     * Safe to call on every reconnect — the per-signal digest comparison ensures only
     * new records move over the wire, and the CRDT merge makes a resend harmless.
     *
     * @param edge the OTLP-capable backend to drain into.
     * @return [DrainResult.Success] when no attempted signal failed,
     *   [DrainResult.Partial] when some succeeded and some failed, or
     *   [DrainResult.Failure] when every attempted signal failed. Failures carry the
     *   signal and the exception its edge call threw.
     */
    public suspend fun drain(edge: OtlpEdge): DrainResult {
        // ── Spans (+ inferred causal links) ──────────────────────────────────
        val spanSnapshot = telemetry.spans.snapshot().elements
        val spans = attempt(OtlpSignal.SPANS, buffered = spanSnapshot.isNotEmpty()) {
            val digest = edge.digest()
            val delta = spanSnapshot.filterTo(mutableSetOf()) { it.spanId !in digest.spanIds }
            if (delta.isNotEmpty()) {
                val deltaIds = delta.mapTo(mutableSetOf()) { it.spanId }
                val links = inferCausalLinks(spanSnapshot).filter { it.fromSpanId in deltaIds }
                edge.send(delta, links)
            }
            delta.size
        }

        // ── Logs ─────────────────────────────────────────────────────────────
        val logSnapshot = telemetry.logs.snapshot().toList()
        val logs = attempt(OtlpSignal.LOGS, buffered = logSnapshot.isNotEmpty()) {
            val digest = edge.logDigest()
            val delta = logSnapshot.filterTo(mutableSetOf()) { it.recordId !in digest.recordIds }
            if (delta.isNotEmpty()) edge.sendLogs(delta)
            delta.size
        }

        // ── Metrics ──────────────────────────────────────────────────────────
        val points = renderMetricPoints(telemetry.metrics.snapshotAll(), nowEpochNanos())
        val metrics = attempt(OtlpSignal.METRICS, buffered = points.isNotEmpty()) {
            val digest = edge.metricDigest()
            val delta = points.filterTo(mutableSetOf()) { digest.versions[it.key] != it.valueHash() }
            if (delta.isNotEmpty()) edge.sendMetrics(delta)
            delta.size
        }

        return classify(spans, logs, metrics)
    }

    private fun nowEpochNanos(): Long = clock.now().toEpochMilliseconds() * NANOS_PER_MILLI

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** One signal's leg of a drain: not attempted (nothing buffered), sent, or failed. */
private sealed interface SignalOutcome {
    data object Idle : SignalOutcome

    data class Sent(val count: Int) : SignalOutcome

    data class Failed(val failure: SignalFailure) : SignalOutcome
}

private val SignalOutcome.sent: Int
    get() = when (this) {
        is SignalOutcome.Sent -> count
        SignalOutcome.Idle, is SignalOutcome.Failed -> 0
    }

/**
 * Run one signal's [leg] when it has anything [buffered], catching a failure as that
 * signal's outcome so it never aborts the others. [leg] returns the number of records sent.
 */
private suspend fun attempt(signal: OtlpSignal, buffered: Boolean, leg: suspend () -> Int): SignalOutcome {
    if (!buffered) return SignalOutcome.Idle
    return runCatchingCancellable { leg() }.fold(
        onSuccess = { SignalOutcome.Sent(it) },
        onFailure = { cause ->
            // Interpolated rather than attached: an unreachable collector is this bridge's NORMAL
            // state, not an anomaly — the failure is a transport exception whose type and message
            // ("Connection refused", "Unresolved address") carry the whole diagnosis, while the
            // trace is the same HTTP-client frames every time. Attaching it also makes a routine
            // offline drain a candidate for the process's first symbolication, which on an Apple
            // target is a one-time cost that collapses under load (#1596; see docs/usage.md).
            logger.debug { "WarpOtlpBridge: ${signal.noun} drain failed: $cause" }
            SignalOutcome.Failed(SignalFailure(signal, cause))
        },
    )
}

private val OtlpSignal.noun: String
    get() = when (this) {
        OtlpSignal.SPANS -> "span"
        OtlpSignal.LOGS -> "log"
        OtlpSignal.METRICS -> "metric"
    }

/**
 * Classify by which attempted legs succeeded or failed — never by record counts, since an
 * up-to-date signal succeeds while sending nothing.
 */
private fun classify(spans: SignalOutcome, logs: SignalOutcome, metrics: SignalOutcome): DrainResult {
    val outcomes = listOf(spans, logs, metrics)
    val failures = outcomes.filterIsInstance<SignalOutcome.Failed>().map { it.failure }
    return when {
        failures.isEmpty() -> DrainResult.Success(spans.sent, logs.sent, metrics.sent)
        outcomes.any { it is SignalOutcome.Sent } -> DrainResult.Partial(spans.sent, logs.sent, metrics.sent, failures)
        else -> DrainResult.Failure(failures)
    }
}

/**
 * Render a [MetricCatalog] into OTLP [MetricPoint]s for egress, stamping
 * [nowEpochNanos] as each point's observation time. Sums use `startEpochNanos = 0`
 * (OTLP "unknown start" — acceptable for a cumulative total the collector tracks).
 * A gauge that has never been written (`value == null`) is skipped.
 */
internal fun renderMetricPoints(catalog: MetricCatalog, nowEpochNanos: Long): List<MetricPoint> = buildList {
    catalog.sums.forEach { (key, counter) ->
        add(MetricPoint.Sum(key, counter.value, startEpochNanos = 0L, timeEpochNanos = nowEpochNanos))
    }
    catalog.doubleSums.forEach { (key, counter) ->
        add(MetricPoint.DoubleSum(key, counter.value, startEpochNanos = 0L, timeEpochNanos = nowEpochNanos))
    }
    catalog.gauges.forEach { (key, register) ->
        val v = register.value ?: return@forEach
        add(MetricPoint.Gauge(key, v, timeEpochNanos = nowEpochNanos))
    }
    catalog.cardinalities.forEach { (key, hll) ->
        add(MetricPoint.Cardinality(key, hll.estimate(), timeEpochNanos = nowEpochNanos))
    }
    catalog.histograms.forEach { (key, sketch) ->
        add(sketch.toExponentialHistogramPoint(key, startEpochNanos = 0L, timeEpochNanos = nowEpochNanos))
    }
}
