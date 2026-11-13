package us.tractat.kuilt.otel.bench

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.Data
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.sdk.KuiltMetricExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.coroutines.resume

/**
 * One row of the SDK bridge arm: one `KuiltMetricExporter.export` of a batch carrying one delta
 * point for each of [series] existing sum series — a collection interval in which every series
 * moved — timed until its result code completes.
 */
data class BridgeRow(val series: Int, val sample: Sample, val writesPerExport: Long, val storeNanosPerExport: Double)

/**
 * Seeds [series] sum series, then exports [rounds] batches through the SDK bridge, each a point of
 * `+1` for every series.
 *
 * Rig checks: the exporter recovers exactly [series] series and still holds exactly that many after
 * the run (so every point landed on an existing series rather than minting a new one); every export
 * completes successfully; the store takes one write per point; and the counters total
 * `series × (1 + exports)`.
 */
suspend fun bridgeExportArm(series: Int, rounds: Int, warmUpExports: Int): BridgeRow {
    val timing = TimingStore(InMemoryDurableStore())
    seedSums(timing, sumsMap(series))
    val exporter = WarpMetricExporter(replica = BENCH_REPLICA, store = timing, maxMetrics = series)
    exporter.recover()
    check(exporter.metricCount() == series) { "seeded $series series, recovered ${exporter.metricCount()}" }

    val batch = listOf(deltaSumOfEverySeries(series))
    val drainJob = Job(currentCoroutineContext()[Job])
    val bridge = KuiltMetricExporter(exporter, CoroutineScope(currentCoroutineContext() + drainJob))
    suspend fun exportOnce() {
        check(bridge.export(batch).await()) { "KuiltMetricExporter.export failed" }
    }

    repeat(warmUpExports) { exportOnce() }
    timing.reset()
    val sample = measureEach(rounds, setup = {}) { exportOnce() }
    bridge.shutdown().await()
    drainJob.cancel()

    val exports = warmUpExports.toLong() + sample.executed
    check(exporter.metricCount() == series) { "the bridge minted series: ${exporter.metricCount()} != $series" }
    check(timing.writes == series * sample.executed) {
        "store took ${timing.writes} writes for ${sample.executed} exports of $series points"
    }
    val total = (0 until series).sumOf { exporter.sumValue(sumKey(it)) }
    check(total == series * (1 + exports)) { "counters total $total, expected ${series * (1 + exports)}" }

    return BridgeRow(
        series = series,
        sample = sample,
        writesPerExport = timing.writes / sample.executed,
        storeNanosPerExport = timing.nanosInWrite.toDouble() / sample.executed,
    )
}

private val ROUTE = AttributeKey.stringKey("http.route")
private val STATUS = AttributeKey.stringKey("http.status_code")

/**
 * A monotonic delta `LONG_SUM` with one point of `1` per series, attributes matching [sumKey] so each
 * point lands on a seeded series. The containers are the SDK's real immutables: `MetricData`'s
 * default accessors cast to them (see `KuiltMetricExporterTest`).
 */
private fun deltaSumOfEverySeries(series: Int): MetricData {
    val points = (0 until series).map { i ->
        val attrs = sumAttributes(i)
        ImmutableLongPointData.create(
            0L,
            1_000L,
            Attributes.of(ROUTE, attrs.getValue("http.route"), STATUS, attrs.getValue("http.status_code")),
            1L,
        )
    }
    val data = ImmutableSumData.create(true, AggregationTemporality.DELTA, points)
    return object : MetricData {
        override fun getResource(): Resource = Resource.empty()
        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
        override fun getName(): String = SUM_NAME
        override fun getDescription(): String = ""
        override fun getUnit(): String = ""
        override fun getType(): MetricDataType = MetricDataType.LONG_SUM
        override fun getData(): Data<*> = data
    }
}

/** Suspends until the SDK result code completes; true on success. */
private suspend fun CompletableResultCode.await(): Boolean = suspendCancellableCoroutine { cont ->
    whenComplete { cont.resume(isSuccess) }
}
