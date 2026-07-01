package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.Data
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuiltMetricExporterTest {

    private fun buffer() = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())

    // ---- fixtures ----
    //
    // The point/sum/gauge containers are the SDK's REAL immutable data types, not
    // hand-rolled interface fakes. Reason (confirmed against OTel 1.45.0): MetricData's
    // default `getDoubleSumData()` does `checkcast ImmutableSumData` (the concrete class),
    // not the `SumData` interface — so an interface-only fake fails the cast and the point
    // silently reads back empty. The real immutables satisfy every accessor's checkcast.
    // Only `metric()` stays an object wrapper: its `getData()` result is a real immutable,
    // so the accessor casts succeed.

    private fun longPoint(value: Long, attrs: Attributes = Attributes.empty(), epoch: Long = 1_000L): LongPointData =
        ImmutableLongPointData.create(0L, epoch, attrs, value)

    private fun doublePoint(value: Double, attrs: Attributes = Attributes.empty(), epoch: Long = 1_000L): DoublePointData =
        ImmutableDoublePointData.create(0L, epoch, attrs, value)

    private fun <T : io.opentelemetry.sdk.metrics.data.PointData> sum(monotonic: Boolean, points: Collection<T>) =
        ImmutableSumData.create(monotonic, AggregationTemporality.DELTA, points)

    private fun <T : io.opentelemetry.sdk.metrics.data.PointData> gauge(points: Collection<T>) =
        ImmutableGaugeData.create(points)

    private fun metric(name: String, type: MetricDataType, data: Data<*>) = object : MetricData {
        override fun getResource(): Resource = Resource.empty()
        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
        override fun getName() = name
        override fun getDescription() = ""
        override fun getUnit() = ""
        override fun getType() = type
        override fun getData(): Data<*> = data
    }

    // ---- tests ----

    @Test
    fun temporalityIsDelta() {
        val bridge = KuiltMetricExporter(buffer(), CoroutineScope(StandardTestDispatcher()))
        InstrumentType.values().forEach {
            assertEquals(AggregationTemporality.DELTA, bridge.getAggregationTemporality(it))
        }
    }

    @Test
    fun monotonicLongSumIncrementsGCounter() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("requests", MetricDataType.LONG_SUM, sum(monotonic = true, points = listOf(longPoint(3L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(3L, exp.sumValue(MetricKey("requests", MetricKind.SUM)))
    }

    @Test
    fun monotonicDoubleSumIncrementsGCounterDouble() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("cpu.seconds", MetricDataType.DOUBLE_SUM, sum(monotonic = true, points = listOf(doublePoint(0.75))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(0.75, exp.doubleSumValue(MetricKey("cpu.seconds", MetricKind.SUM)))
    }

    @Test
    fun doubleGaugeSetsLwwRegister() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("temp", MetricDataType.DOUBLE_GAUGE, gauge(listOf(doublePoint(21.5, epoch = 5L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(21.5, exp.gaugeValue(MetricKey("temp", MetricKind.GAUGE)))
    }

    @Test
    fun nonMonotonicSumBecomesGauge() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("queue.depth", MetricDataType.LONG_SUM, sum(monotonic = false, points = listOf(longPoint(7L, epoch = 9L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(7.0, exp.gaugeValue(MetricKey("queue.depth", MetricKind.GAUGE)))
    }

    @Test
    fun histogramIsDroppedNotFailed() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("latency", MetricDataType.HISTOGRAM, gauge<LongPointData>(emptyList()))
        val code = bridge.export(listOf(md))
        runCurrent()
        assertEquals(0, exp.metricCount()) // nothing written
        assertTrue(code.isSuccess) // dropped is not an export failure
    }

    @Test
    fun resultCodeSucceedsAfterDrain() = runTest {
        val bridge = KuiltMetricExporter(buffer(), backgroundScope)
        val md = metric("requests", MetricDataType.LONG_SUM, sum(monotonic = true, points = listOf(longPoint(1L))))
        val code = bridge.export(listOf(md))
        runCurrent()
        assertTrue(code.isSuccess)
    }

    @Test
    fun shutdownDrainsCleanly() = runTest {
        val bridge = KuiltMetricExporter(buffer(), backgroundScope)
        bridge.export(listOf(metric("r", MetricDataType.LONG_SUM, sum(true, listOf(longPoint(1L))))))
        val code = bridge.shutdown()
        runCurrent()
        assertTrue(code.isSuccess)
    }
}
