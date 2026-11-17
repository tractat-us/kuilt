package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.Data
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.data.PointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramBuckets
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.MetricHistogramOverflowException
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricSumOverflowException
import us.tractat.kuilt.otel.MetricUpDownSumOverflowException
import us.tractat.kuilt.otel.MetricUpdate
import us.tractat.kuilt.otel.MetricWriteResult
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One SDK export is one [WarpMetricExporter.record] batch: a durable write per metric kind the
 * export touches, not one per point (#3284).
 */
class KuiltMetricExporterBatchTest {

    /** Counts writes per store key; optionally refuses one key's writes, or another's reads. */
    private class CountingStore(private val refuse: String? = null, private val refuseReadOf: String? = null) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val counts = ConcurrentHashMap<String, Int>()
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val refusedReads = java.util.concurrent.atomic.AtomicInteger()

        fun writes(): Map<String, Int> = counts.toMap()

        override suspend fun read(key: StoreKey): ByteArray? {
            if (key.name == refuseReadOf) {
                refusedReads.incrementAndGet()
                throw IllegalStateException("store refused the read of ${key.name}")
            }
            return backing.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            counts.merge(key.name, 1, Int::plus)
            if (key.name == refuse) {
                refused.incrementAndGet()
                throw IllegalStateException("store refused ${key.name}")
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    private val route = AttributeKey.stringKey("route")

    private fun longPoint(value: Long, r: String, epoch: Long = 1_000L) =
        ImmutableLongPointData.create(0L, epoch, Attributes.of(route, r), value)

    private fun doublePoint(value: Double, r: String, epoch: Long = 1_000L) =
        ImmutableDoublePointData.create(0L, epoch, Attributes.of(route, r), value)

    private fun <T : PointData> deltaCounter(points: Collection<T>) =
        ImmutableSumData.create(true, AggregationTemporality.DELTA, points)

    private fun metric(name: String, type: MetricDataType, data: Data<*>) = object : MetricData {
        override fun getResource(): Resource = Resource.empty()
        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
        override fun getName() = name
        override fun getDescription() = ""
        override fun getUnit() = ""
        override fun getType() = type
        override fun getData(): Data<*> = data
    }

    /** Nine points over three metric kinds: four long-sum points, two double-sum, three gauge. */
    private val export = listOf(
        metric(
            "requests",
            MetricDataType.LONG_SUM,
            deltaCounter(listOf(longPoint(3L, "/a"), longPoint(1L, "/b"), longPoint(2L, "/c"), longPoint(5L, "/d"))),
        ),
        metric("cpu.seconds", MetricDataType.DOUBLE_SUM, deltaCounter(listOf(doublePoint(0.5, "/a"), doublePoint(0.25, "/b")))),
        metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a"), doublePoint(19.0, "/b")))),
        metric("threads", MetricDataType.LONG_GAUGE, ImmutableGaugeData.create(listOf(longPoint(8L, "/a")))),
    )

    private fun sumKey(name: String, r: String) = MetricKey(name, MetricKind.SUM, mapOf("route" to r))
    private fun gaugeKey(name: String, r: String) = MetricKey(name, MetricKind.GAUGE, mapOf("route" to r))

    @Test
    fun oneExportIsOneWritePerKindTouched() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)

        val code = bridge.export(export)
        runCurrent()

        assertAll(
            // Precondition: every point landed, so the export really carried nine of them.
            { assertEquals(9, exp.metricCount()) },
            { assertEquals(5L, exp.sumValue(sumKey("requests", "/d"))) },
            { assertEquals(0.25, exp.doubleSumValue(sumKey("cpu.seconds", "/b"))) },
            { assertEquals(8.0, exp.gaugeValue(gaugeKey("threads", "/a"))) },
            { assertTrue(code.isSuccess) },
            {
                assertEquals(
                    mapOf("otel.metrics.sums" to 1, "otel.metrics.sums.double" to 1, "otel.metrics.gauges" to 1),
                    store.writes(),
                )
            },
        )
    }

    @Test
    fun aNanCounterPointIsDroppedAndTheRestOfTheExportLands() = runTest {
        // SDK 1.68.0 lets a NaN through `DoubleCounter.add` and through an observable callback
        // that computes 0/0. A grow-only counter cannot take it, so that one point is dropped;
        // it must not take the other points of the same export down with it.
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val withNan = deltaCounter(listOf(doublePoint(0.5, "/a"), doublePoint(Double.NaN, "/b"), doublePoint(0.25, "/c")))
        val nanExport = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(3L, "/a")))),
            metric("cpu.seconds", MetricDataType.DOUBLE_SUM, withNan),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val code = bridge.export(nanExport)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            { assertTrue(withNan.points.any { it.value.isNaN() }, "precondition: the export carries a NaN point") },
            { assertTrue(code.isSuccess, "a dropped point is not an export failure") },
            { assertEquals(3L, exp.sumValue(sumKey("requests", "/a"))) },
            { assertEquals(0.5, exp.doubleSumValue(sumKey("cpu.seconds", "/a"))) },
            { assertEquals(0.25, exp.doubleSumValue(sumKey("cpu.seconds", "/c"))) },
            { assertEquals(0.0, exp.doubleSumValue(sumKey("cpu.seconds", "/b")), "the NaN point opened no series") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(0.25, recovered.doubleSumValue(sumKey("cpu.seconds", "/c")), "and it is on disk") },
        )
    }

    @Test
    fun aRefusedKindFailsTheExportAndTheOtherKindsStillLand() = runTest {
        val store = CountingStore(refuse = "otel.metrics.sums")
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)

        val code = bridge.export(export)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            { assertTrue(store.refused.get() > 0, "rig never refused") },
            { assertTrue(code.isDone) },
            { assertFalse(code.isSuccess) },
            // In memory regardless: a refused write is "not yet durable", not "not counted".
            { assertEquals(11L, (listOf("/a", "/b", "/c", "/d")).sumOf { exp.sumValue(sumKey("requests", it)) }) },
            // The kinds the store took are on disk; the refused one is not.
            { assertEquals(0L, recovered.sumValue(sumKey("requests", "/a"))) },
            { assertEquals(0.5, recovered.doubleSumValue(sumKey("cpu.seconds", "/a"))) },
            { assertEquals(19.0, recovered.gaugeValue(gaugeKey("temp", "/b"))) },
        )
    }
    /**
     * A counter whose count on this replica has reached [Long.MAX_VALUE] sends an increment in every
     * export, and `record` refuses any batch carrying it (#3318). That series is left out and the
     * rest of the export recorded again: the gauge lands, and the other counter holds exactly one
     * copy of its delta, because the refused attempt applied nothing.
     */
    @Test
    fun aSaturatedSumSeriesIsLeftOutAndTheRestOfTheExportLandsOnce() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val saturated = sumKey("requests", "/a")
        assertEquals(MetricExportResult.Success, exp.incrementSum(saturated, Long.MAX_VALUE - 1L))
        val rigRefuses = exp.incrementSum(saturated, 3L)
        val saturatedExport = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(3L, "/a"), longPoint(4L, "/b")))),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val code = bridge.export(saturatedExport)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the series really is saturated")
                assertIs<MetricSumOverflowException>(refusal.overflow)
            },
            { assertTrue(code.isSuccess, "a saturated series is not an export failure") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(4L, exp.sumValue(sumKey("requests", "/b")), "one copy of the delta, not two") },
            { assertEquals(Long.MAX_VALUE - 1L, exp.sumValue(saturated)) },
            { assertEquals(4L, recovered.sumValue(sumKey("requests", "/b")), "and it is on disk") },
        )
    }

    /**
     * An export holding a saturated sum and a gauge whose kind the store will not read. The first
     * `record` is refused for the overflow and applies nothing; the bridge records the rest again,
     * and that retry is refused for the unread gauges. One export, so one failed turn of the gauges,
     * and the export fails, because the gauge was not counted. The first record's read of the gauges
     * is refused and opens a window of one turn, so the retry refuses them without a read (#3436).
     */
    @Test
    fun aSaturatedSumBesideAnUnreadKindFailsTheExportAsOneFailedTurn() = runTest {
        val store = CountingStore(refuseReadOf = "otel.metrics.gauges")
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val saturated = sumKey("requests", "/a")
        assertEquals(MetricExportResult.Success, exp.incrementSum(saturated, Long.MAX_VALUE - 1L))
        val failedBefore = exp.health.value.failed

        val code = bridge.export(
            listOf(
                metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(3L, "/a"), longPoint(4L, "/b")))),
                metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
            ),
        )
        runCurrent()

        assertAll(
            { assertEquals(1, store.refusedReads.get(), "the rig did not refuse the first record's read of the gauges, or the retry read them") },
            { assertTrue(code.isDone && !code.isSuccess, "an export whose gauge was not counted succeeded") },
            { assertEquals(failedBefore + 1, exp.health.value.failed, "one export counted ${exp.health.value.failed - failedBefore} failed turns") },
            { assertEquals(4L, exp.sumValue(sumKey("requests", "/b")), "the unsaturated sum did not land once") },
            { assertEquals(null, exp.gaugeValue(gaugeKey("temp", "/a")), "the unread kind's gauge was counted") },
        )
    }

    /**
     * A saturated up-down series (#3307): this replica's increment slot is at [Long.MAX_VALUE], so
     * the level it reports in every export would move the slot past it. `record` refuses the batch
     * for it; the bridge leaves that series out and records the rest again, so the other up-down
     * series, the counter and the gauge land once and the export succeeds. The bridge's loop asserts
     * that a refusal names an update still in the batch; an up-down refusal must satisfy it, not trip it.
     */
    @Test
    fun aSaturatedUpDownSeriesIsLeftOutAndTheRestOfTheExportLandsOnce() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val saturated = MetricKey("in_flight", MetricKind.UP_DOWN_SUM, mapOf("route" to "/a"))
        val other = MetricKey("in_flight", MetricKind.UP_DOWN_SUM, mapOf("route" to "/b"))
        assertEquals(MetricExportResult.Success, exp.setUpDownSum(saturated, Long.MAX_VALUE))
        assertEquals(MetricExportResult.Success, exp.setUpDownSum(saturated, 0L))
        val rigRefuses = exp.setUpDownSum(saturated, 2L)
        val export = listOf(
            metric(
                "in_flight",
                MetricDataType.LONG_SUM,
                ImmutableSumData.create(false, AggregationTemporality.CUMULATIVE, listOf(longPoint(2L, "/a"), longPoint(5L, "/b"))),
            ),
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(4L, "/b")))),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val code = bridge.export(export)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the series really is saturated")
                assertEquals(saturated, assertIs<MetricUpDownSumOverflowException>(refusal.overflow).key)
            },
            { assertTrue(code.isDone && code.isSuccess, "a saturated up-down series is not an export failure") },
            { assertEquals(0L, exp.upDownSumValue(saturated), "the saturated series took nothing") },
            { assertEquals(5L, exp.upDownSumValue(other), "the other up-down series landed") },
            { assertEquals(4L, exp.sumValue(sumKey("requests", "/b")), "one copy of the delta, not two") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(5L, recovered.upDownSumValue(other), "and it is on disk") },
        )
    }

    /**
     * The up-down twin of [aSaturatedLongSumKeepsTheDoubleSumThatSharesItsKey]: a long and a double
     * UpDownCounter under one name and attributes are different series, one in each map, and
     * `record` refuses only the long one. Leaving it out must not take the double one with it.
     */
    @Test
    fun aSaturatedLongUpDownSeriesKeepsTheDoubleUpDownSeriesThatSharesItsKey() = runTest {
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = CountingStore())
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val shared = MetricKey("in_flight", MetricKind.UP_DOWN_SUM, mapOf("route" to "/a"))
        assertEquals(MetricExportResult.Success, exp.setUpDownSum(shared, Long.MAX_VALUE))
        assertEquals(MetricExportResult.Success, exp.setUpDownSum(shared, 0L))
        val rigRefuses = exp.setUpDownSum(shared, 2L)
        val sharedExport = listOf(
            metric(
                "in_flight",
                MetricDataType.LONG_SUM,
                ImmutableSumData.create(false, AggregationTemporality.CUMULATIVE, listOf(longPoint(2L, "/a"))),
            ),
            metric(
                "in_flight",
                MetricDataType.DOUBLE_SUM,
                ImmutableSumData.create(false, AggregationTemporality.CUMULATIVE, listOf(doublePoint(1.5, "/a"))),
            ),
        )

        val code = bridge.export(sharedExport)
        runCurrent()

        assertAll(
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the long series really is saturated")
                assertEquals(shared, assertIs<MetricUpDownSumOverflowException>(refusal.overflow).key)
            },
            { assertTrue(code.isDone && code.isSuccess, "a saturated up-down series is not an export failure") },
            { assertEquals(0L, exp.upDownSumValue(shared), "the saturated long series took nothing") },
            { assertEquals(1.5, exp.upDownSumDoubleValue(shared), "the double series under the same key was dropped with it") },
        )
    }

    /**
     * A long sum and a double sum share a name and attributes but are different counters. Leaving the
     * saturated long one out must not take the double one with it.
     */
    @Test
    fun aSaturatedLongSumKeepsTheDoubleSumThatSharesItsKey() = runTest {
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = CountingStore())
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val shared = sumKey("requests", "/a")
        assertEquals(MetricExportResult.Success, exp.incrementSum(shared, Long.MAX_VALUE - 1L))
        val rigRefuses = exp.incrementSum(shared, 3L)
        val sharedExport = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(3L, "/a")))),
            metric("requests", MetricDataType.DOUBLE_SUM, deltaCounter(listOf(doublePoint(0.5, "/a")))),
        )

        val code = bridge.export(sharedExport)
        runCurrent()

        assertAll(
            { assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the long series really is saturated") },
            { assertTrue(code.isSuccess) },
            { assertEquals(0.5, exp.doubleSumValue(shared), "the double point landed") },
            { assertEquals(Long.MAX_VALUE - 1L, exp.sumValue(shared)) },
        )
    }

    /**
     * A saturated histogram series (#3354): this replica's slot in one of its buckets is one short
     * of [Long.MAX_VALUE], and every export carries more for it. The series is left out and the
     * rest of each export lands exactly once, in this export and the next. Before the fix the
     * histogram update threw part-way through `record`, and every export failed.
     */
    @Test
    fun aSaturatedHistogramSeriesIsLeftOutAndTheRestOfEveryExportLandsOnce() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
        val scale = exp.histogramScale
        val seeded = exp.record(listOf(histogramUpdate(latency, scale, offset = 10, count = Long.MAX_VALUE - 1L)))
        val rigRefuses = exp.record(listOf(histogramUpdate(latency, scale, offset = 10, count = 3L)))
        val export = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(4L, "/b")))),
            metric("latency", MetricDataType.EXPONENTIAL_HISTOGRAM, deltaHistogram(scale, offset = 10, count = 3L)),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val first = bridge.export(export)
        runCurrent()
        val second = bridge.export(export)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            { assertEquals(MetricExportResult.Success, seeded, "the rig: the slot was seeded") },
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the series really is saturated")
                assertEquals(latency, assertIs<MetricHistogramOverflowException>(refusal.overflow).key)
            },
            {
                assertEquals(
                    Long.MAX_VALUE - 1L,
                    exp.histogramSnapshot(latency).positiveBuckets[11],
                    "the rig: the slot is one short, and the export's 3 never landed",
                )
            },
            { assertTrue(first.isSuccess, "a saturated histogram is not an export failure") },
            { assertTrue(second.isSuccess, "nor on the next export") },
            { assertEquals(8L, exp.sumValue(sumKey("requests", "/b")), "one copy of each export's delta") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(8L, recovered.sumValue(sumKey("requests", "/b")), "and it is on disk") },
        )
    }

    /**
     * A histogram series whose **total** would pass [Long.MAX_VALUE] (#3403): it holds
     * `Long.MAX_VALUE - 1` in one bucket, and every export carries two more for a different bucket,
     * whose own slot is empty. `record` refuses it, so the series is left out and the rest of each
     * export lands exactly once. Before the fix `record` checked only the slot, accepted the series,
     * and the OTLP bridge then skipped it.
     */
    @Test
    fun aHistogramSeriesWhoseTotalWouldOverflowIsLeftOutAndTheRestLandsOnce() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
        val scale = exp.histogramScale
        val seeded = exp.record(listOf(histogramUpdate(latency, scale, offset = 10, count = Long.MAX_VALUE - 1L)))
        val rigRefuses = exp.record(listOf(histogramUpdate(latency, scale, offset = 20, count = 2L)))
        val export = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(4L, "/b")))),
            metric("latency", MetricDataType.EXPONENTIAL_HISTOGRAM, deltaHistogram(scale, offset = 20, count = 2L)),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val first = bridge.export(export)
        runCurrent()
        val second = bridge.export(export)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            { assertEquals(MetricExportResult.Success, seeded, "the rig: the series was seeded") },
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the total really is saturated")
                assertEquals(latency, assertIs<MetricHistogramOverflowException>(refusal.overflow).key)
            },
            {
                assertEquals(
                    mapOf(11 to Long.MAX_VALUE - 1L),
                    exp.histogramSnapshot(latency).positiveBuckets,
                    "the rig: the export's bucket is a different slot from the full one, and its 2 never landed",
                )
            },
            { assertEquals(Long.MAX_VALUE - 1L, recovered.histogramSnapshot(latency).count, "nor on disk") },
            { assertTrue(first.isSuccess, "a saturated histogram is not an export failure") },
            { assertTrue(second.isSuccess, "nor on the next export") },
            { assertEquals(8L, exp.sumValue(sumKey("requests", "/b")), "one copy of each export's delta") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(8L, recovered.sumValue(sumKey("requests", "/b")), "and it is on disk") },
        )
    }

    /**
     * A sum series whose **total** would pass [Long.MAX_VALUE] (#3445): a peer's counter merged in
     * holds `Long.MAX_VALUE - 1`, this replica's slot is empty, and every export carries two more.
     * `record` refuses it, so the series is left out and the rest of each export lands exactly once.
     * Before the fix `record` checked only this replica's slot, accepted the series, and the OTLP
     * bridge then skipped it.
     */
    @Test
    fun aSumSeriesWhoseMergedTotalWouldOverflowIsLeftOutAndTheRestLandsOnce() = runTest {
        val store = CountingStore()
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = store)
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val saturated = sumKey("requests", "/a")
        val merged = exp.mergeSum(saturated, GCounter.of(ReplicaId("peer") to Long.MAX_VALUE - 1L))
        val rigRefuses = exp.incrementSum(saturated, 2L)
        val export = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(2L, "/a"), longPoint(4L, "/b")))),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val first = bridge.export(export)
        runCurrent()
        val second = bridge.export(export)
        runCurrent()

        val recovered = WarpMetricExporter(replica = ReplicaId("dev"), store = store).also { it.recover() }
        assertAll(
            { assertEquals(MetricWriteResult.Success, merged, "the rig: the peer's counter was merged") },
            { assertEquals(0L, exp.sumSnapshot(saturated).count(ReplicaId("dev")), "the rig: this replica's slot is empty, and its 2s never landed") },
            {
                val refusal = assertIs<MetricExportResult.Refused>(rigRefuses, "the rig: the total really is saturated")
                assertEquals(saturated, assertIs<MetricSumOverflowException>(refusal.overflow).key)
            },
            { assertEquals(Long.MAX_VALUE - 1L, recovered.sumSnapshot(saturated).valueOrNull(), "nor on disk") },
            { assertTrue(first.isSuccess, "a saturated sum is not an export failure") },
            { assertTrue(second.isSuccess, "nor on the next export") },
            { assertEquals(8L, exp.sumValue(sumKey("requests", "/b")), "one copy of each export's delta") },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
            { assertEquals(8L, recovered.sumValue(sumKey("requests", "/b")), "and it is on disk") },
        )
    }

    /** One positive OTLP bucket at [offset] and [scale] holding [count], for [key]. */
    private fun histogramUpdate(key: MetricKey, scale: Int, offset: Int, count: Long) =
        MetricUpdate.AddExponentialHistogram(
            key, scale = scale, zeroCount = 0L,
            positiveOffset = offset, positiveCounts = listOf(count),
            negativeOffset = 0, negativeCounts = emptyList(),
        )

    /** A delta exponential histogram with no attributes and one positive bucket at [offset] holding [count]. */
    private fun deltaHistogram(scale: Int, offset: Int, count: Long) = ImmutableExponentialHistogramData.create(
        AggregationTemporality.DELTA,
        listOf(
            ImmutableExponentialHistogramPointData.create(
                scale, 0.0, 0L, false, 0.0, false, 0.0,
                ImmutableExponentialHistogramBuckets.create(scale, offset, listOf(count)),
                ImmutableExponentialHistogramBuckets.create(scale, 0, emptyList()),
                0L, 1_000L, Attributes.empty(), emptyList(),
            ),
        ),
    )

    /** Two saturated series in one export are both left out, and every other point lands once. */
    @Test
    fun twoSaturatedSeriesAreBothLeftOutAndTheRestLandsOnce() = runTest {
        val exp = WarpMetricExporter(replica = ReplicaId("dev"), store = CountingStore())
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        listOf("/a", "/b").forEach { r ->
            assertEquals(MetricExportResult.Success, exp.incrementSum(sumKey("requests", r), Long.MAX_VALUE - 1L))
            assertIs<MetricExportResult.Refused>(exp.incrementSum(sumKey("requests", r), 3L), "the rig: $r saturated")
        }
        val twoExport = listOf(
            metric("requests", MetricDataType.LONG_SUM, deltaCounter(listOf(longPoint(3L, "/a"), longPoint(4L, "/b"), longPoint(6L, "/c")))),
            metric("cpu.seconds", MetricDataType.DOUBLE_SUM, deltaCounter(listOf(doublePoint(0.5, "/a")))),
            metric("temp", MetricDataType.DOUBLE_GAUGE, ImmutableGaugeData.create(listOf(doublePoint(21.5, "/a")))),
        )

        val code = bridge.export(twoExport)
        runCurrent()

        assertAll(
            { assertTrue(code.isSuccess) },
            { assertEquals(Long.MAX_VALUE - 1L, exp.sumValue(sumKey("requests", "/a"))) },
            { assertEquals(Long.MAX_VALUE - 1L, exp.sumValue(sumKey("requests", "/b"))) },
            { assertEquals(6L, exp.sumValue(sumKey("requests", "/c")), "once, not twice") },
            { assertEquals(0.5, exp.doubleSumValue(sumKey("cpu.seconds", "/a"))) },
            { assertEquals(21.5, exp.gaugeValue(gaugeKey("temp", "/a"))) },
        )
    }
}
