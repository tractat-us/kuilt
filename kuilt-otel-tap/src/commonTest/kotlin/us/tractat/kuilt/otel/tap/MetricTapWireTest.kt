package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricTapWireTest {
    @Test
    fun metricCatalogRoundTripsThroughCbor() {
        val cat = MetricCatalog(sums = mapOf(MetricKey("x", MetricKind.SUM) to GCounter.of(ReplicaId("A") to 3L)))
        val bytes = MetricTapCbor.encodeToByteArray(metricCatalogSerializer(), cat)
        val back = MetricTapCbor.decodeFromByteArray(metricCatalogSerializer(), bytes)
        assertEquals(cat, back)
    }

    @Test
    fun allFourKindsRoundTripThroughCbor() {
        val a = ReplicaId("A")
        val hll = HyperLogLog.empty()
        val cat = MetricCatalog(
            sums = mapOf(MetricKey("req", MetricKind.SUM) to GCounter.of(a to 3L)),
            doubleSums = mapOf(MetricKey("cpu", MetricKind.SUM) to GCounterDouble.of(a to 2.5)),
            gauges = mapOf(MetricKey("temp", MetricKind.GAUGE) to LWWRegister.empty<Double>().set(a, timestamp = 1L, value = 21.0)),
            cardinalities = mapOf(MetricKey("users", MetricKind.CARDINALITY) to hll.piece(hll.add("u1").delta)),
        )
        val bytes = MetricTapCbor.encodeToByteArray(metricCatalogSerializer(), cat)
        val back = MetricTapCbor.decodeFromByteArray(metricCatalogSerializer(), bytes)
        assertEquals(cat, back)
    }

    @Test
    fun configDefaultsMatchTheTapPattern() {
        assertEquals("kuilt-metric-tap", MetricTapConfig().pattern.displayName)
    }
}
