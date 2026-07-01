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
 * aborts the others, and its CRDT is left intact for the next attempt. A drain is a
 * [DrainResult.Failure] only when every *attempted* signal failed; a partial success
 * reports the counts that got through.
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
     * @return [DrainResult.Success] with per-signal counts (partial successes report
     *   what got through), or [DrainResult.Failure] if every attempted signal failed.
     */
    public suspend fun drain(edge: OtlpEdge): DrainResult {
        var anyAttempted = false
        var anyFailed = false

        // ── Spans (+ inferred causal links) ──────────────────────────────────
        val spanSnapshot = telemetry.spans.snapshot().elements
        var spansSent = 0
        if (spanSnapshot.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.digest()
                val delta = spanSnapshot.filterTo(mutableSetOf()) { it.spanId !in digest.spanIds }
                if (delta.isNotEmpty()) {
                    val deltaIds = delta.mapTo(mutableSetOf()) { it.spanId }
                    val links = inferCausalLinks(spanSnapshot).filter { it.fromSpanId in deltaIds }
                    edge.send(delta, links)
                    spansSent = delta.size
                }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "WarpOtlpBridge: span drain failed" } }
        }

        // ── Logs ─────────────────────────────────────────────────────────────
        val logSnapshot = telemetry.logs.snapshot().toList()
        var logsSent = 0
        if (logSnapshot.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.logDigest()
                val delta = logSnapshot.filterTo(mutableSetOf()) { it.recordId !in digest.recordIds }
                if (delta.isNotEmpty()) { edge.sendLogs(delta); logsSent = delta.size }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "WarpOtlpBridge: log drain failed" } }
        }

        // ── Metrics ──────────────────────────────────────────────────────────
        val points = renderMetricPoints(telemetry.metrics.snapshotAll(), nowEpochNanos())
        var metricsSent = 0
        if (points.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.metricDigest()
                val delta = points.filterTo(mutableSetOf()) { digest.versions[it.key] != it.valueHash() }
                if (delta.isNotEmpty()) { edge.sendMetrics(delta); metricsSent = delta.size }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "WarpOtlpBridge: metric drain failed" } }
        }

        return if (anyAttempted && anyFailed && spansSent == 0 && logsSent == 0 && metricsSent == 0) {
            DrainResult.Failure(IllegalStateException("WarpOtlpBridge: all attempted signals failed to drain"))
        } else {
            DrainResult.Success(spansSent = spansSent, logsSent = logsSent, metricPointsSent = metricsSent)
        }
    }

    private fun nowEpochNanos(): Long = clock.now().toEpochMilliseconds() * NANOS_PER_MILLI

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
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
}
