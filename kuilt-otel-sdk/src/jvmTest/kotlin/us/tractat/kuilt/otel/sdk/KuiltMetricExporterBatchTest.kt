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
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One SDK export is one [WarpMetricExporter.record] batch: a durable write per metric kind the
 * export touches, not one per point (#3284).
 */
class KuiltMetricExporterBatchTest {

    /** Counts writes per store key; optionally refuses one key. */
    private class CountingStore(private val refuse: String? = null) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val counts = ConcurrentHashMap<String, Int>()
        val refused = java.util.concurrent.atomic.AtomicInteger()

        fun writes(): Map<String, Int> = counts.toMap()

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

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
}
