package us.tractat.kuilt.otel.sdk

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.MetricExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.sdk.KuiltMetricExporter")

/**
 * An OpenTelemetry SDK [MetricExporter] that funnels metric points into kuilt's durable
 * [WarpMetricExporter] buffer — the metric twin of `KuiltLogRecordExporter`.
 *
 * For apps **already** running the OTel SDK: register this on the metric reader and every
 * monotonic counter and gauge also lands in kuilt's offline-first buffer, extractable via
 * the metric tap — without adopting kuilt's own instrumentation. Purely additive.
 *
 * ## Temporality (load-bearing)
 *
 * [getAggregationTemporality] picks, per instrument type, the temporality the mapping below
 * needs — the same split as the SDK's own `AggregationTemporalitySelector.deltaPreference()`:
 *
 * - **Counters** (`COUNTER`, `OBSERVABLE_COUNTER`) → [AggregationTemporality.DELTA].
 *   `WarpMetricExporter.incrementSum(by)` **adds** `by`; a cumulative point (running total)
 *   would be added again on every collection. A delta is exactly the increment to add, and
 *   it is additive, so two replicas' counters merge to the true total with no coordination.
 * - **UpDownCounters** (`UP_DOWN_COUNTER`, `OBSERVABLE_UP_DOWN_COUNTER`) →
 *   [AggregationTemporality.CUMULATIVE]. One becomes a gauge, which holds a level; under
 *   delta its point would be the change over the interval, and a queue going 10 → 12 would
 *   read 2. The cost: a cumulative series is re-sent on every collection whether or not it
 *   moved, so each one is a `setGauge` write per collection.
 * - **Gauges and histograms** → [AggregationTemporality.DELTA]. A gauge's point is a level
 *   either way; under delta the SDK sends it only when it was recorded in the interval, so
 *   an unchanged gauge is not rewritten with a fresh timestamp each collection. Histograms
 *   are dropped, so theirs only sets how much the SDK holds between collections.
 *
 * ## Non-blocking bridge
 *
 * `export`/`flush`/`shutdown` return a [CompletableResultCode]. [export] enqueues the raw
 * batch on an unbounded [Channel] and returns immediately; a single scope-bound drain
 * coroutine maps and applies each [MetricData], then completes the batch's code.
 *
 * ## Mapping
 * - monotonic `LONG_SUM` → `incrementSum`; monotonic `DOUBLE_SUM` → `incrementSumDouble`.
 * - non-monotonic sums (UpDownCounter) and gauges → `setGauge`. An UpDownCounter arrives
 *   cumulative (above), so the gauge it becomes holds its current level.
 * - histograms / exponential histograms / summaries → **dropped** (WARN), never a failure.
 *   `WarpMetricExporter` does keep histograms (a `DDSketch` per series), but it takes them one
 *   raw value at a time through `recordHistogram`; the SDK hands over buckets already counted,
 *   and `mergeHistogram` is a join, so adding one interval's buckets to the last has no entry point.
 * - a delta of ≤ 0 for a monotonic sum is skipped (grow-only counters reject it).
 *
 * @param exporter the durable kuilt buffer points are written into.
 * @param scope the drain coroutine's scope (required — never a real-dispatcher default).
 */
public class KuiltMetricExporter(
    private val exporter: WarpMetricExporter,
    scope: CoroutineScope,
) : MetricExporter {

    private class Batch(val metrics: Collection<MetricData>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)

    private val drain = scope.launch {
        for (batch in queue) {
            val ok = runCatchingCancellable {
                batch.metrics.map { applyMetric(it) }.all { it }
            }.getOrDefault(false)
            if (ok) batch.code.succeed() else batch.code.fail()
        }
    }

    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(metrics.toList(), code)).isFailure) code.fail()
        return code
    }

    override fun flush(): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(emptyList(), code)).isFailure) code.fail()
        return code
    }

    override fun shutdown(): CompletableResultCode {
        val code = CompletableResultCode()
        queue.close()
        drain.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) code.succeed() else code.fail()
        }
        return code
    }

    /**
     * Cumulative for an UpDownCounter, whose point becomes a gauge level; delta for everything
     * else, so a counter's point is the increment `incrementSum` adds. See the class KDoc.
     */
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        when (instrumentType) {
            InstrumentType.UP_DOWN_COUNTER, InstrumentType.OBSERVABLE_UP_DOWN_COUNTER -> AggregationTemporality.CUMULATIVE
            else -> AggregationTemporality.DELTA
        }

    /** Apply one metric's points; return whether every point-write succeeded (drop = success). */
    private suspend fun applyMetric(md: MetricData): Boolean {
        val name = md.name
        return when (md.type) {
            MetricDataType.LONG_SUM -> {
                val s = md.longSumData
                s.points.map { p ->
                    if (s.isMonotonic) incLong(name, p) else setGaugeOk(name, p.attributes, p.value.toDouble(), p.epochNanos)
                }.all { it }
            }
            MetricDataType.DOUBLE_SUM -> {
                val s = md.doubleSumData
                s.points.map { p ->
                    if (s.isMonotonic) incDouble(name, p) else setGaugeOk(name, p.attributes, p.value, p.epochNanos)
                }.all { it }
            }
            MetricDataType.LONG_GAUGE ->
                md.longGaugeData.points.map { setGaugeOk(name, it.attributes, it.value.toDouble(), it.epochNanos) }.all { it }
            MetricDataType.DOUBLE_GAUGE ->
                md.doubleGaugeData.points.map { setGaugeOk(name, it.attributes, it.value, it.epochNanos) }.all { it }
            MetricDataType.HISTOGRAM, MetricDataType.EXPONENTIAL_HISTOGRAM, MetricDataType.SUMMARY -> {
                logger.warn {
                    "KuiltMetricExporter: dropping unsupported metric '$name' (${md.type}) — the SDK sends " +
                        "pre-bucketed deltas, and WarpMetricExporter records histograms one value at a time"
                }
                true
            }
        }
    }

    private suspend fun incLong(name: String, p: LongPointData): Boolean {
        if (p.value <= 0L) return true // grow-only rejects ≤0; a delta of 0/negative is a no-op
        return exporter.incrementSum(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value) is MetricExportResult.Success
    }

    private suspend fun incDouble(name: String, p: DoublePointData): Boolean {
        if (p.value <= 0.0) return true
        return exporter.incrementSumDouble(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value) is MetricExportResult.Success
    }

    private suspend fun setGaugeOk(name: String, attributes: Attributes, value: Double, epochNanos: Long): Boolean =
        exporter.setGauge(MetricKey(name, MetricKind.GAUGE, attrs(attributes)), value, epochNanos) is MetricExportResult.Success

    private fun attrs(a: Attributes): Map<String, String> = buildMap { a.forEach { k, v -> put(k.key, v.toString()) } }
}
