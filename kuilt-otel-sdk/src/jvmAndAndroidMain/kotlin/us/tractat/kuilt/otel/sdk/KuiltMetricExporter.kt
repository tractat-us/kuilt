package us.tractat.kuilt.otel.sdk

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.Aggregation
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.MetricExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricSumOverflowException
import us.tractat.kuilt.otel.MetricUpdate
import us.tractat.kuilt.otel.WarpMetricExporter
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.sdk.KuiltMetricExporter")

/**
 * An OpenTelemetry SDK [MetricExporter] that funnels metric points into kuilt's durable
 * [WarpMetricExporter] buffer — the metric twin of `KuiltLogRecordExporter`.
 *
 * For apps **already** running the OTel SDK: register this on the metric reader and every
 * monotonic counter, gauge and histogram also lands in kuilt's offline-first buffer, extractable
 * via the metric tap — without adopting kuilt's own instrumentation. Purely additive.
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
 *   an unchanged gauge is not rewritten with a fresh timestamp each collection. A histogram's
 *   delta is exactly the bucket counts to add to the series. Delta also restarts the SDK's
 *   histogram at the top scale each collection, so one wide burst lowers the scale of its own
 *   interval only, where cumulative would lower it for the rest of the process.
 *
 * ## Histograms
 *
 * [getDefaultAggregation] asks the SDK for a base-2 **exponential** histogram for every
 * `HISTOGRAM` instrument, with at most [maxBuckets] buckets per sign and a scale no finer than
 * `WarpMetricExporter.histogramScale`, the scale the buffer keeps its sketches at. A reader
 * consults this only when it is built from the exporter, as a `PeriodicMetricReader` is; a View
 * that names another aggregation overrides it.
 *
 * The SDK lowers a point's scale when its values span more than [maxBuckets] buckets. A point that
 * arrives at the buffer's scale maps exactly. One that arrives coarser cannot be split back into
 * finer buckets without losing the sketch's accuracy, so that point is dropped with a WARN and its
 * interval's counts are lost. [DEFAULT_MAX_BUCKETS] covers twenty doublings of the value at the
 * default scale, against five for the SDK's own default, so that takes a wide spread.
 *
 * ## Non-blocking bridge
 *
 * `export`/`flush`/`shutdown` return a [CompletableResultCode]. [export] enqueues the raw
 * batch on an unbounded [Channel] and returns immediately; a single scope-bound drain
 * coroutine maps every point of the batch to a [MetricUpdate] and hands them to
 * `WarpMetricExporter.record` as **one** batch — one durable write per metric kind the export
 * touched, not one per point (#3284) — then completes the batch's code: success only once every
 * touched kind is durably written.
 *
 * ## Mapping
 * - monotonic `LONG_SUM` → `incrementSum`; monotonic `DOUBLE_SUM` → `incrementSumDouble`.
 * - non-monotonic sums (UpDownCounter) and gauges → `setGauge`. An UpDownCounter arrives
 *   cumulative (above), so the gauge it becomes holds its current level.
 * - delta exponential histograms → `MetricUpdate.AddExponentialHistogram`, which adds the
 *   interval's bucket counts to the series' `DDSketch`. The point's `sum`, `min`, `max` and
 *   exemplars are not kept; a sketch has no field for them.
 * - **dropped** (WARN), never a failure: an exponential histogram coarser than the buffer's
 *   scale (above), or one that arrived cumulative, whose counts would be added again each
 *   collection; an explicit-bucket histogram, which reaches the bridge only when a View asks for
 *   it, since no single value stands for a bucket within the sketch's accuracy; and a summary,
 *   whose quantiles do not merge.
 * - a delta of ≤ 0 for a monotonic sum is skipped (grow-only counters reject it), and so is a
 *   NaN delta (WARN). Either is one dropped point, never a failed export: the export is one
 *   batch, so a point it could not apply would otherwise take every other point with it.
 * - a monotonic sum whose count on this replica cannot grow past [Long.MAX_VALUE] is dropped from
 *   the export (WARN the first time) for the same reason: `record` refuses the whole batch for it, so the export
 *   is recorded again without that series, and every other point lands once.
 * - a sum whose own temporality is not the one asked for above — a cumulative counter, or a
 *   delta UpDownCounter, from a reader with its own temporality selector — is **dropped**
 *   (WARN). Added, the first would count its running total again each collection; stored,
 *   the second would be a change read as a level.
 *
 * @param exporter the durable kuilt buffer points are written into.
 * @param scope the drain coroutine's scope (required — never a real-dispatcher default).
 * @param maxBuckets the most buckets per sign the SDK keeps for one histogram series in one
 *   interval, [DEFAULT_MAX_BUCKETS] by default. It sets the SDK's memory per series, and how wide
 *   a spread fits at the buffer's scale before a point arrives too coarse and is dropped.
 * @throws IllegalArgumentException if the SDK refuses [maxBuckets] (it needs at least 2).
 */
public class KuiltMetricExporter @JvmOverloads constructor(
    private val exporter: WarpMetricExporter,
    scope: CoroutineScope,
    public val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
) : MetricExporter {

    /**
     * Built eagerly, so a [maxBuckets] the SDK refuses fails here rather than at the first instrument.
     *
     * The `(maxBuckets, maxScale)` overload is deprecated in favour of `Base2ExponentialHistogramOptions`,
     * but that class first shipped in OpenTelemetry SDK 1.60.0. The SDK is `compileOnly` here, so the
     * app's own version is the one on the classpath, and on 1.59 or older the options class would
     * throw `NoClassDefFoundError` from this constructor — taking counters and gauges down with the
     * histograms. The deprecated overload exists on every version this bridge supports.
     */
    @Suppress("DEPRECATION")
    private val histogramAggregation: Aggregation =
        Aggregation.base2ExponentialBucketHistogram(maxBuckets, exporter.histogramScale)

    private class Batch(val metrics: Collection<MetricData>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)

    /** The saturated sum series already dropped once; see [firstDrop]. Declared before [drain], which reads it. */
    private val dropped: MutableSet<MetricKey> = ConcurrentHashMap.newKeySet()

    private val drain = scope.launch {
        for (batch in queue) {
            val ok = runCatchingCancellable {
                val updates = ArrayList<MetricUpdate>()
                batch.metrics.forEach { collectUpdates(it, updates) }
                recordWithoutSaturatedSums(updates) is MetricExportResult.Success
            }.onFailure { failure ->
                // Unexpected, unlike a refused write (which record() returns and logs itself), so
                // the throwable is the diagnosis: keep it.
                logger.warn(failure) { "KuiltMetricExporter: export of ${batch.metrics.size} metrics failed" }
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
     * [WarpMetricExporter.record] the export, leaving out each sum series it refuses as saturated.
     *
     * `record` refuses a whole batch, applying nothing, when one increment would carry this
     * replica's count for its series past [Long.MAX_VALUE] (a [MetricSumOverflowException]). A
     * delta counter that has reached that point sends an increment in every export, so passing the
     * refusal on would drop every other point of every export from then on. Instead the refused
     * series' updates are taken out and the rest recorded again. A refused attempt applied
     * nothing, so the retry counts every other point exactly once, histogram updates included:
     * only long increments are taken out, so they pass through every retry untouched.
     *
     * Only the series' long increments leave: a double sum under the same name and attributes is a
     * different counter and is not refused. The loop ends: every refusal names a long increment
     * still in the batch, and it leaves with the retry, so there is at most one retry per distinct
     * series; a `check` pins that a refusal never names anything else. Any other result, a store refusal
     * included, is returned as it is.
     *
     * A saturated series is dropped from every export from then on, so the line saying so is at
     * WARN only the first time; see [firstDrop].
     */
    private suspend fun recordWithoutSaturatedSums(updates: List<MetricUpdate>): MetricExportResult {
        var remaining = updates
        while (true) {
            val result = exporter.record(remaining)
            val saturated = ((result as? MetricExportResult.Failure)?.cause as? MetricSumOverflowException)?.key
            if (saturated == null) return result
            // The refused key names an increment of the batch it was given, and that increment
            // leaves below, so every pass shrinks `remaining`. Were that untrue the loop would
            // spin; fail loudly instead.
            check(remaining.any { it is MetricUpdate.IncrementSum && it.key == saturated }) {
                "record refused sum series ${saturated.name} ${saturated.attributes} as saturated, " +
                    "but the batch carries no long increment to it"
            }
            val line = {
                "KuiltMetricExporter: dropping this export's increments to sum series ${saturated.name} " +
                    "${saturated.attributes}: this replica's count for it cannot grow past Long.MAX_VALUE"
            }
            if (firstDrop(saturated)) logger.warn(line) else logger.debug(line)
            remaining = remaining.filterNot { it is MetricUpdate.IncrementSum && it.key == saturated }
        }
    }

    /**
     * Whether [key] is being dropped for the first time, so its line goes out at WARN once and at
     * DEBUG after that. Under `installLogCapture` a WARN per export would itself be a durable log
     * record, shipped on the next drain, for as long as the series lives.
     *
     * Bounded by the live sum series: a series the exporter no longer holds, evicted or cleared,
     * is forgotten first. A concurrent set, not dispatcher confinement, although only the drain
     * coroutine calls this today.
     */
    private fun firstDrop(key: MetricKey): Boolean {
        dropped.removeIf { exporter.sumSnapshot(it) == GCounter.ZERO }
        return dropped.add(key)
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

    /**
     * An exponential histogram capped at the buffer's scale for `HISTOGRAM` instruments, so the
     * SDK hands over buckets that map onto the buffer's sketches exactly; the SDK's own default for
     * every other instrument. See the class KDoc.
     */
    override fun getDefaultAggregation(instrumentType: InstrumentType): Aggregation =
        if (instrumentType == InstrumentType.HISTOGRAM) histogramAggregation else Aggregation.defaultAggregation()

    /**
     * Append one metric's points to [into] as [MetricUpdate]s. A dropped metric — unsupported, or a
     * sum with the wrong temporality — adds none, which is not a failure.
     */
    private fun collectUpdates(md: MetricData, into: MutableList<MetricUpdate>) {
        val name = md.name
        when (md.type) {
            MetricDataType.LONG_SUM -> {
                val s = md.longSumData
                if (!sumTemporalityFits(name, s.isMonotonic, s.aggregationTemporality)) return
                s.points.forEach { p ->
                    if (s.isMonotonic) incLong(name, p, into) else gauge(name, p.attributes, p.value.toDouble(), p.epochNanos, into)
                }
            }
            MetricDataType.DOUBLE_SUM -> {
                val s = md.doubleSumData
                if (!sumTemporalityFits(name, s.isMonotonic, s.aggregationTemporality)) return
                s.points.forEach { p ->
                    if (s.isMonotonic) incDouble(name, p, into) else gauge(name, p.attributes, p.value, p.epochNanos, into)
                }
            }
            MetricDataType.LONG_GAUGE ->
                md.longGaugeData.points.forEach { gauge(name, it.attributes, it.value.toDouble(), it.epochNanos, into) }
            MetricDataType.DOUBLE_GAUGE ->
                md.doubleGaugeData.points.forEach { gauge(name, it.attributes, it.value, it.epochNanos, into) }
            MetricDataType.EXPONENTIAL_HISTOGRAM -> {
                val h = md.exponentialHistogramData
                if (h.aggregationTemporality != AggregationTemporality.DELTA) {
                    logger.warn {
                        "KuiltMetricExporter: dropping exponential histogram '$name' — it arrived " +
                            "${h.aggregationTemporality} and the bridge adds deltas; register it on a reader that asks the exporter"
                    }
                    return
                }
                h.points.forEach { histogram(name, it, into) }
            }
            MetricDataType.HISTOGRAM ->
                logger.warn {
                    "KuiltMetricExporter: dropping explicit-bucket histogram '$name' — no one value stands for a " +
                        "bucket within the sketch's accuracy; remove the View that asks for explicit buckets"
                }
            MetricDataType.SUMMARY ->
                logger.warn { "KuiltMetricExporter: dropping summary '$name' — quantiles do not merge" }
        }
    }

    /**
     * One delta point of an exponential histogram, as the bucket counts to add — unless it is
     * coarser than the buffer's scale, when it is dropped with a WARN rather than handed to
     * `WarpMetricExporter.record`, which would refuse the whole export for it.
     */
    private fun histogram(name: String, p: ExponentialHistogramPointData, into: MutableList<MetricUpdate>) {
        if (p.scale < exporter.histogramScale) {
            logger.warn {
                "KuiltMetricExporter: dropping ${p.count} values of histogram '$name' — the SDK lowered its scale to " +
                    "${p.scale}, below the buffer's ${exporter.histogramScale}, and its buckets cannot be split; " +
                    "raise maxBuckets (now $maxBuckets) if this spread is normal"
            }
            return
        }
        into += MetricUpdate.AddExponentialHistogram(
            key = MetricKey(name, MetricKind.EXPONENTIAL_HISTOGRAM, attrs(p.attributes)),
            scale = p.scale,
            zeroCount = p.zeroCount,
            positiveOffset = p.positiveBuckets.offset,
            positiveCounts = p.positiveBuckets.bucketCounts,
            negativeOffset = p.negativeBuckets.offset,
            negativeCounts = p.negativeBuckets.bucketCounts,
        )
    }

    /**
     * Whether a sum arrived with the temporality its mapping needs: delta for a counter's
     * increment, cumulative for an UpDownCounter's level. A reader that consults
     * [getAggregationTemporality] always delivers that; one with its own selector may not,
     * and either mismatch would be silently wrong, so the sum is dropped with a WARN instead.
     */
    private fun sumTemporalityFits(name: String, monotonic: Boolean, temporality: AggregationTemporality): Boolean {
        val needed = if (monotonic) AggregationTemporality.DELTA else AggregationTemporality.CUMULATIVE
        if (temporality == needed) return true
        logger.warn {
            "KuiltMetricExporter: dropping ${if (monotonic) "monotonic" else "non-monotonic"} sum '$name' — it " +
                "arrived $temporality and the bridge needs $needed; register it on a reader that asks the exporter"
        }
        return false
    }

    private fun incLong(name: String, p: LongPointData, into: MutableList<MetricUpdate>) {
        if (p.value <= 0L) return // grow-only rejects ≤0; a delta of 0/negative is a no-op
        into += MetricUpdate.IncrementSum(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value)
    }

    /**
     * Written `!(value > 0.0)` rather than `value <= 0.0` so a NaN is skipped too: the SDK lets one
     * through `DoubleCounter.add` and through an observable callback computing 0/0, a grow-only
     * counter cannot take it, and [MetricUpdate.IncrementSumDouble] would reject it while the
     * export is being collected — failing the whole export, every valid point with it.
     */
    private fun incDouble(name: String, p: DoublePointData, into: MutableList<MetricUpdate>) {
        if (!(p.value > 0.0)) {
            if (p.value.isNaN()) {
                logger.warn { "KuiltMetricExporter: dropping a NaN point of monotonic sum '$name' — a counter cannot add it" }
            }
            return
        }
        into += MetricUpdate.IncrementSumDouble(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value)
    }

    /**
     * A gauge takes any value, NaN included: `LWWRegister` holds it and [MetricUpdate.SetGauge]
     * does not reject it, so a NaN gauge point cannot take the rest of the export down. It is
     * stored as given, as it was before batching.
     */
    private fun gauge(name: String, attributes: Attributes, value: Double, epochNanos: Long, into: MutableList<MetricUpdate>) {
        into += MetricUpdate.SetGauge(MetricKey(name, MetricKind.GAUGE, attrs(attributes)), value, epochNanos)
    }

    private fun attrs(a: Attributes): Map<String, String> = buildMap { a.forEach { k, v -> put(k.key, v.toString()) } }

    public companion object {
        /**
         * The default [maxBuckets]: 640, twenty doublings of the value at the buffer's default scale
         * 5. The SDK's own default, 160, fits five, past which a point arrives too coarse and is
         * dropped.
         */
        public const val DEFAULT_MAX_BUCKETS: Int = 640
    }
}
