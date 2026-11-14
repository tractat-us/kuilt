package us.tractat.kuilt.otel.sdk

import io.opentelemetry.sdk.metrics.Aggregation
import io.opentelemetry.sdk.metrics.InstrumentSelector
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder
import io.opentelemetry.sdk.metrics.View
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.time.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.alphaForOtlpScale
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import java.time.Duration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Histograms through a real `SdkMeterProvider`: the reader asks the bridge for its default
 * aggregation and temporality, as a `PeriodicMetricReader` does, and each collection is exported
 * into the bridge (#3291). Every test asserts what the SDK actually handed over — the point's type,
 * scale and temporality — before asserting what the buffer made of it.
 */
class KuiltMetricExporterHistogramTest {

    private val replica = ReplicaId("dev")

    private fun buffer() = WarpMetricExporter(replica = replica, store = InMemoryDurableStore())

    private val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)

    private class Sdk(
        val bridge: KuiltMetricExporter,
        temporality: AggregationTemporalitySelector = bridge,
        configure: SdkMeterProviderBuilder.() -> Unit = {},
    ) {
        val clock: TestClock = TestClock.create()
        val reader: InMemoryMetricReader = InMemoryMetricReader.builder()
            .setAggregationTemporalitySelector(temporality)
            .setDefaultAggregationSelector(bridge)
            .build()
        val provider: SdkMeterProvider =
            SdkMeterProvider.builder().setClock(clock).registerMetricReader(reader).apply(configure).build()
        val meter = provider.get("kuilt-otel-sdk-histogram-test")
    }

    /** Collect once, export what the SDK handed over into the bridge, and return it for inspection. */
    private fun TestScope.collect(sdk: Sdk): Collection<MetricData> {
        sdk.clock.advance(Duration.ofSeconds(10))
        val metrics = sdk.reader.collectAllMetrics()
        val code = sdk.bridge.export(metrics)
        runCurrent()
        assertTrue(code.isSuccess, "export did not complete successfully")
        return metrics
    }

    private fun Collection<MetricData>.named(name: String): MetricData = single { it.name == name }

    /** The scale of the one exponential-histogram point, or null when the SDK sent none — an explicit histogram, say. */
    private fun MetricData.scale(): Int? = exponentialHistogramData.points.singleOrNull()?.scale

    private fun logUniform(rng: Random, n: Int, min: Double, max: Double): List<Double> =
        List(n) { exp(rng.nextDouble(ln(min), ln(max))) }

    // (a)
    @Test
    fun theDefaultAggregationDeliversExponentialHistogramsAtTheBufferScale() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val values = logUniform(Random(3291), n = 1_000, min = 1.0, max = 1_000.0)
        val histogram = sdk.meter.histogramBuilder("latency").build()
        values.forEach { histogram.record(it) }

        val delivered = collect(sdk).named("latency")

        val alpha = alphaForOtlpScale(exp.histogramScale)
        val sorted = values.sorted()
        val quantileChecks = listOf(0.0, 0.01, 0.25, 0.5, 0.9, 0.99, 1.0).map { q ->
            {
                val truth = sorted[floor(q * (sorted.size - 1)).toInt()]
                val estimate = exp.histogramQuantile(latency, q) ?: Double.NaN
                assertTrue(abs(estimate - truth) / truth <= alpha + 1e-9, "q=$q: $estimate vs $truth, α=$alpha")
            }
        }
        assertAll(
            { assertEquals(MetricDataType.EXPONENTIAL_HISTOGRAM, delivered.type, "precondition: the SDK took the default") },
            { assertEquals(exp.histogramScale, delivered.scale(), "precondition: at the buffer's scale") },
            { assertEquals(values.size.toLong(), exp.histogramSnapshot(latency).count, "count is exact") },
            *quantileChecks.toTypedArray(),
        )
    }

    // (b)
    @Test
    fun twoCollectionsAccumulate() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val rng = Random(17)
        // No negatives: the SDK's histogram drops a negative measurement before it reaches any
        // reader. The negative arm of the mapping is covered in :kuilt-otel's commonTest.
        val first = logUniform(rng, n = 40, min = 0.5, max = 50.0) + listOf(0.0)
        val second = logUniform(rng, n = 60, min = 2.0, max = 200.0) + listOf(0.0, 0.0)
        val histogram = sdk.meter.histogramBuilder("latency").build()

        first.forEach { histogram.record(it) }
        val firstScale = collect(sdk).named("latency").scale()
        val afterFirst = exp.histogramSnapshot(latency).count
        second.forEach { histogram.record(it) }
        val secondScale = collect(sdk).named("latency").scale()

        // Reference: every value recorded one at a time into a sketch of the same configuration.
        val reference = buffer()
        (first + second).forEach { reference.recordHistogram(latency, it) }

        assertAll(
            { assertEquals(exp.histogramScale, firstScale, "precondition: first interval at the buffer's scale") },
            { assertEquals(exp.histogramScale, secondScale, "precondition: second interval at the buffer's scale") },
            { assertEquals(first.size.toLong(), afterFirst, "precondition: the first interval landed") },
            { assertEquals(reference.histogramSnapshot(latency), exp.histogramSnapshot(latency), "the two deltas add") },
        )
    }

    // (c)
    @Test
    fun anExplicitBucketViewIsDroppedWhileTheRestLands() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope)) {
            registerView(
                InstrumentSelector.builder().setName("explicit").build(),
                View.builder().setAggregation(Aggregation.explicitBucketHistogram()).build(),
            )
        }
        sdk.meter.histogramBuilder("explicit").build().record(5.0)
        sdk.meter.histogramBuilder("latency").build().record(5.0)
        sdk.meter.counterBuilder("requests").build().add(3)

        val delivered = collect(sdk)

        assertAll(
            { assertEquals(MetricDataType.HISTOGRAM, delivered.named("explicit").type, "precondition: the View forced explicit buckets") },
            { assertEquals(0L, exp.histogramSnapshot(MetricKey("explicit", MetricKind.EXPONENTIAL_HISTOGRAM)).count, "explicit dropped") },
            { assertEquals(1L, exp.histogramSnapshot(latency).count, "the exponential histogram landed") },
            { assertEquals(3L, exp.sumValue(MetricKey("requests", MetricKind.SUM)), "the counter landed") },
            { assertEquals(2, exp.metricCount(), "nothing else was written") },
        )
    }

    // (d)
    @Test
    fun aSpreadWiderThanMaxBucketsIsRefusedWhileTheRestLands() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val histogram = sdk.meter.histogramBuilder("latency").build()
        // 1e-3 to 1e6 is ~30 doublings, 960 buckets at scale 5: more than 640, so the SDK must coarsen.
        histogram.record(1e-3)
        histogram.record(1e6)
        sdk.meter.counterBuilder("requests").build().add(2)

        val delivered = collect(sdk)

        assertAll(
            { assertTrue(delivered.named("latency").scale()?.let { it < exp.histogramScale } == true, "precondition: the SDK lowered the scale") },
            { assertEquals(0L, exp.histogramSnapshot(latency).count, "the coarse point was dropped") },
            { assertEquals(2L, exp.sumValue(MetricKey("requests", MetricKind.SUM)), "the counter in the same export landed") },
        )
    }

    // (e)
    @Test
    fun theIntervalAfterAWideOneReturnsToTheBufferScale() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val histogram = sdk.meter.histogramBuilder("latency").build()
        histogram.record(1e-3)
        histogram.record(1e6)
        val wideScale = collect(sdk).named("latency").scale()
        histogram.record(4.0)
        histogram.record(8.0)
        histogram.record(16.0)
        val narrowScale = collect(sdk).named("latency").scale()

        assertAll(
            { assertTrue(wideScale?.let { it < exp.histogramScale } == true, "precondition: the wide interval was coarsened, was $wideScale") },
            { assertEquals(exp.histogramScale, narrowScale, "delta restarts each interval at the top scale") },
            { assertEquals(3L, exp.histogramSnapshot(latency).count, "the narrow interval landed") },
        )
    }

    // (f)
    @Test
    fun aCumulativeReadersHistogramIsDropped() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val sdk = Sdk(bridge, temporality = AggregationTemporalitySelector.alwaysCumulative())
        sdk.meter.histogramBuilder("latency").build().record(5.0)
        sdk.meter.gaugeBuilder("temp").build().set(21.5)

        val delivered = collect(sdk).named("latency")

        assertAll(
            { assertEquals(MetricDataType.EXPONENTIAL_HISTOGRAM, delivered.type, "precondition: exponential") },
            { assertEquals(AggregationTemporality.CUMULATIVE, delivered.exponentialHistogramData.aggregationTemporality, "precondition: cumulative") },
            { assertEquals(0L, exp.histogramSnapshot(latency).count, "a running total is not added") },
            { assertEquals(21.5, exp.gaugeValue(MetricKey("temp", MetricKind.GAUGE)), "the gauge in the same export landed") },
        )
    }

    @Test
    fun maxBucketsDefaultsTo640AndTheSdkRefusesTooFew() {
        val scope = CoroutineScope(StandardTestDispatcher())
        assertAll(
            { assertEquals(640, KuiltMetricExporter(buffer(), scope).maxBuckets) },
            { assertFailsWith<IllegalArgumentException> { KuiltMetricExporter(buffer(), scope, maxBuckets = 1) } },
        )
    }
}
