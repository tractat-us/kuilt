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

    // The temporality defaults to what the bridge asks the SDK for: delta for a monotonic
    // sum, cumulative for an UpDownCounter's.
    private fun <T : io.opentelemetry.sdk.metrics.data.PointData> sum(
        monotonic: Boolean,
        points: Collection<T>,
        temporality: AggregationTemporality =
            if (monotonic) AggregationTemporality.DELTA else AggregationTemporality.CUMULATIVE,
    ) = ImmutableSumData.create(monotonic, temporality, points)

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
    fun upDownCounterHoldsTheLevelAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val queue = sdk.meter.upDownCounterBuilder("queue.depth").build()
        val key = MetricKey("queue.depth", MetricKind.UP_DOWN_SUM)

        queue.add(10)
        collect(sdk)
        val afterFirst = exp.upDownSumValue(key)
        queue.add(2)
        collect(sdk)

        assertAll(
            { assertEquals(10L, afterFirst, "first collection (precondition)") },
            { assertEquals(12L, exp.upDownSumValue(key), "level after +10 then +2") },
        )
    }

    @Test
    fun doubleUpDownCounterHoldsTheLevelAcrossCollections() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        val balance = sdk.meter.upDownCounterBuilder("balance").ofDoubles().build()
        val key = MetricKey("balance", MetricKind.UP_DOWN_SUM)

        balance.add(1.5)
        collect(sdk)
        val afterFirst = exp.upDownSumDoubleValue(key)
        balance.add(-0.25)
        collect(sdk)

        assertAll(
            { assertEquals(1.5, afterFirst, "first collection (precondition)") },
            { assertEquals(1.25, exp.upDownSumDoubleValue(key), "level after +1.5 then -0.25") },
        )
    }

    @Test
    fun observableUpDownCounterHoldsTheObservedLevel() = runTest {
        val exp = buffer()
        val sdk = Sdk(KuiltMetricExporter(exp, backgroundScope))
        var observed = 10L
        sdk.meter.upDownCounterBuilder("connections").buildWithCallback { it.record(observed) }
        val key = MetricKey("connections", MetricKind.UP_DOWN_SUM)

        collect(sdk)
        val afterFirst = exp.upDownSumValue(key)
        observed = 7L
        collect(sdk)

        assertAll(
            { assertEquals(10L, afterFirst, "first collection (precondition)") },
            { assertEquals(7L, exp.upDownSumValue(key), "level after observing 10 then 7") },
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
    fun cumulativeNonMonotonicSumSetsThisReplicasShare() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("queue.depth", MetricDataType.LONG_SUM, sum(monotonic = false, points = listOf(longPoint(7L, epoch = 9L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(7L, exp.upDownSumSnapshot(MetricKey("queue.depth", MetricKind.UP_DOWN_SUM)).count(ReplicaId("dev")))
    }

    // ---- #3307: an UpDownCounter sums across replicas, and survives a restart ----

    /** Note test 11's twin: two replicas, each observing its own level, merge to the sum. */
    @Test
    fun twoReplicasUpDownCountersSumOnceMerged() = runTest {
        val onA = WarpMetricExporter(replica = ReplicaId("a"), store = InMemoryDurableStore()).also { it.recover() }
        val onB = WarpMetricExporter(replica = ReplicaId("b"), store = InMemoryDurableStore()).also { it.recover() }
        val sdkA = Sdk(KuiltMetricExporter(onA, backgroundScope))
        val sdkB = Sdk(KuiltMetricExporter(onB, backgroundScope))
        sdkA.meter.upDownCounterBuilder("http.in_flight").buildWithCallback { it.record(3L) }
        sdkB.meter.upDownCounterBuilder("http.in_flight").buildWithCallback { it.record(4L) }
        val key = MetricKey("http.in_flight", MetricKind.UP_DOWN_SUM)

        collect(sdkA)
        collect(sdkB)
        val levels = onA.upDownSumValue(key) to onB.upDownSumValue(key)
        onA.mergeUpDownSum(key, onB.upDownSumSnapshot(key))

        assertAll(
            { assertEquals(3L to 4L, levels, "each replica holds its own level (precondition)") },
            { assertEquals(7L, onA.upDownSumValue(key), "the fleet's figure is the sum of the levels") },
        )
    }

    /**
     * Note test 11: a restart is a second `SdkMeterProvider` over the same store and replica. The
     * new process observes the same 7 and reports it in full; the share already holds 7, so it moves
     * nothing. A delta mapping would read 14.
     */
    @Test
    fun aRestartedProcessReportingTheSameLevelDoesNotCountItTwice() = runTest {
        val store = InMemoryDurableStore()
        val key = MetricKey("connections", MetricKind.UP_DOWN_SUM)
        val first = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        val before = Sdk(KuiltMetricExporter(first, backgroundScope))
        before.meter.upDownCounterBuilder("connections").buildWithCallback { it.record(7L) }
        collect(before)
        before.provider.close()

        val restarted = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        val recovered = restarted.upDownSumValue(key)
        val after = Sdk(KuiltMetricExporter(restarted, backgroundScope))
        after.meter.upDownCounterBuilder("connections").buildWithCallback { it.record(7L) }
        collect(after)

        assertAll(
            { assertEquals(7L, recovered, "the restarted exporter recovered the share (precondition)") },
            { assertEquals(7L, restarted.upDownSumValue(key), "the same level after a restart reads once") },
        )
    }

    /**
     * Note test 12: an UpDownCounter written before #3307 lives under its name as a GAUGE. The batch
     * that writes its up-down sum retires that twin, and a real gauge is untouched.
     */
    @Test
    fun theGaugeTwinAnOlderBuildWroteIsRetiredByTheFirstExport() = runTest {
        val store = InMemoryDurableStore()
        val older = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        val twin = MetricKey("queue.depth", MetricKind.GAUGE)
        val realGauge = MetricKey("temperature", MetricKind.GAUGE)
        older.setGauge(twin, 5.0, 1L)
        older.setGauge(realGauge, 21.0, 1L)

        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        val recovered = exp.snapshotAll().gauges.keys
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("queue.depth", MetricDataType.LONG_SUM, sum(monotonic = false, points = listOf(longPoint(6L))))
        val code = bridge.export(listOf(md))
        runCurrent()
        val afterRestart = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }

        assertAll(
            { assertEquals(setOf(twin, realGauge), recovered, "the twin came back from the store (precondition)") },
            { assertTrue(code.isSuccess, "the export landed") },
            { assertEquals(setOf(realGauge), exp.snapshotAll().gauges.keys, "the twin is gone, the real gauge stays") },
            { assertEquals(setOf(realGauge), afterRestart.snapshotAll().gauges.keys, "and gone from disk too") },
            { assertEquals(6L, exp.upDownSumValue(MetricKey("queue.depth", MetricKind.UP_DOWN_SUM))) },
        )
    }

    /** A NaN or infinite double level is dropped with a WARN; the rest of the export lands. */
    @Test
    fun aNonFiniteUpDownLevelIsDroppedNotFailed() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val bad = metric("balance", MetricDataType.DOUBLE_SUM, sum(monotonic = false, points = listOf(doublePoint(Double.NaN))))
        val good = metric("requests", MetricDataType.LONG_SUM, sum(monotonic = true, points = listOf(longPoint(2L))))
        val code = bridge.export(listOf(bad, good))
        runCurrent()
        assertAll(
            { assertTrue(code.isSuccess, "a dropped point is not a failed export") },
            { assertEquals(2L, exp.sumValue(MetricKey("requests", MetricKind.SUM)), "the other point landed") },
            { assertTrue(exp.snapshotAll().doubleUpDownSums.isEmpty(), "nothing stored for the NaN level") },
        )
    }

    // A reader that does not consult the bridge's temporality (a custom MetricReader with its
    // own selector) can still hand it the other kind of sum. Each would be silently wrong:
    // a running total added to the GCounter on every collection, or a change stored as a level.

    @Test
    fun cumulativeMonotonicSumIsDroppedNotAdded() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val total = sum(monotonic = true, points = listOf(longPoint(3L)), temporality = AggregationTemporality.CUMULATIVE)
        val code = bridge.export(listOf(metric("requests", MetricDataType.LONG_SUM, total)))
        runCurrent()
        assertAll(
            { assertEquals(0, exp.metricCount(), "nothing written") },
            { assertTrue(code.isSuccess, "dropped is not an export failure") },
        )
    }

    @Test
    fun deltaNonMonotonicSumIsDroppedNotStoredAsLevel() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val change = sum(monotonic = false, points = listOf(doublePoint(2.0)), temporality = AggregationTemporality.DELTA)
        val code = bridge.export(listOf(metric("queue.depth", MetricDataType.DOUBLE_SUM, change)))
        runCurrent()
        assertAll(
            { assertEquals(0, exp.metricCount(), "nothing written") },
            { assertTrue(code.isSuccess, "dropped is not an export failure") },
        )
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
