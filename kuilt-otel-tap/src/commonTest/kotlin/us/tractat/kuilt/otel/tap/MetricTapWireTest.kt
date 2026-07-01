package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.crdt.GCounter
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
    fun configDefaultsMatchTheTapPattern() {
        assertEquals("kuilt-metric-tap", MetricTapConfig().pattern.displayName)
    }
}
