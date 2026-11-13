package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.SdkMeterProvider
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
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import java.time.Duration
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
    fun temporalityIsCumulativeForUpDownCountersOnly() {
        val bridge = KuiltMetricExporter(buffer(), CoroutineScope(StandardTestDispatcher()))
        val cumulative = setOf(InstrumentType.UP_DOWN_COUNTER, InstrumentType.OBSERVABLE_UP_DOWN_COUNTER)
        val checks = InstrumentType.values().map { type ->
            {
                val expected = if (type in cumulative) AggregationTemporality.CUMULATIVE else AggregationTemporality.DELTA
                assertEquals(expected, bridge.getAggregationTemporality(type), "temporality for $type")
            }
        }
        assertAll(*checks.toTypedArray())
    }

    // ---- a real SDK, through more than one collection ----
    //
    // The hand-built fixtures above fix a point's temporality by construction, so they
    // cannot see what the SDK hands over once it has asked this exporter which temporality
    // to use. These drive a real SdkMeterProvider: the reader consults the bridge exactly
    // as a PeriodicMetricReader consults its exporter, and each collection is exported
    // into the bridge. Every test asserts the value after the FIRST collection too, so a
    // rig that never exported cannot pass on the second.

    private class Sdk(val bridge: KuiltMetricExporter) {
        val clock: TestClock = TestClock.create()
        val reader: InMemoryMetricReader =
            InMemoryMetricReader.builder().setAggregationTemporalitySelector(bridge).build()
        val provider: SdkMeterProvider =
            SdkMeterProvider.builder().setClock(clock).registerMetricReader(reader).build()
        val meter = provider.get("kuilt-otel-sdk-test")
    }

    private fun TestScope.collect(sdk: Sdk) {
        sdk.clock.advance(Duration.ofSeconds(10))
        val code = sdk.bridge.export(sdk.reader.collectAllMetrics())
        runCurrent()
        assertTrue(code.isSuccess, "export did not complete")
    }

    @Test
    fun upDownCounterGaugeHoldsTheLevelAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val queue = sdk.meter.upDownCounterBuilder("queue.depth").build()
        val key = MetricKey("queue.depth", MetricKind.GAUGE)

        queue.add(10)
        collect(sdk)
        val afterFirst = exp.gaugeValue(key)
        queue.add(2)
        collect(sdk)

        assertAll(
            { assertEquals(10.0, afterFirst, "first collection (precondition)") },
            { assertEquals(12.0, exp.gaugeValue(key), "level after +10 then +2") },
        )
    }

    @Test
    fun doubleUpDownCounterGaugeHoldsTheLevelAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val balance = sdk.meter.upDownCounterBuilder("balance").ofDoubles().build()
        val key = MetricKey("balance", MetricKind.GAUGE)

        balance.add(1.5)
        collect(sdk)
        val afterFirst = exp.gaugeValue(key)
        balance.add(-0.25)
        collect(sdk)

        assertAll(
            { assertEquals(1.5, afterFirst, "first collection (precondition)") },
            { assertEquals(1.25, exp.gaugeValue(key), "level after +1.5 then -0.25") },
        )
    }

    @Test
    fun observableUpDownCounterGaugeHoldsTheObservedLevel() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        var observed = 10L
        sdk.meter.upDownCounterBuilder("connections").buildWithCallback { it.record(observed) }
        val key = MetricKey("connections", MetricKind.GAUGE)

        collect(sdk)
        val afterFirst = exp.gaugeValue(key)
        observed = 7L
        collect(sdk)

        assertAll(
            { assertEquals(10.0, afterFirst, "first collection (precondition)") },
            { assertEquals(7.0, exp.gaugeValue(key), "level after observing 10 then 7") },
        )
    }

    // The two below are green before and after the fix: they pin the other half of the
    // choice, so a fix that turned every instrument CUMULATIVE would red here, adding a
    // running total to the GCounter on every collection.

    @Test
    fun counterAddsEachIntervalOnceAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val requests = sdk.meter.counterBuilder("requests").build()
        val key = MetricKey("requests", MetricKind.SUM)

        requests.add(3)
        collect(sdk)
        val afterFirst = exp.sumValue(key)
        requests.add(4)
        collect(sdk)

        assertAll(
            { assertEquals(3L, afterFirst, "first collection (precondition)") },
            { assertEquals(7L, exp.sumValue(key), "total after +3 then +4") },
        )
    }

    @Test
    fun observableCounterAddsOnlyTheGrowthAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        var observed = 5L
        sdk.meter.counterBuilder("bytes.sent").buildWithCallback { it.record(observed) }
        val key = MetricKey("bytes.sent", MetricKind.SUM)

        collect(sdk)
        val afterFirst = exp.sumValue(key)
        observed = 8L
        collect(sdk)

        assertAll(
            { assertEquals(5L, afterFirst, "first collection (precondition)") },
            { assertEquals(8L, exp.sumValue(key), "total after observing 5 then 8") },
        )
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
