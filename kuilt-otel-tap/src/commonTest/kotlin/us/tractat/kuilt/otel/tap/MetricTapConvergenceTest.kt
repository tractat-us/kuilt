package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricTapConvergenceTest {
    private val config = MetricTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    @Test
    fun pullReconstructsEveryKind() = runTest {
        val exporter = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("req", MetricKind.SUM), by = 4L)
        exporter.incrementSumDouble(MetricKey("cpu", MetricKind.SUM), by = 2.5)
        exporter.setGauge(MetricKey("temp", MetricKind.GAUGE), 21.0, timestamp = 1L)
        exporter.addCardinality(MetricKey("users", MetricKind.CARDINALITY), "u1")

        val loom = InMemoryLoom()
        val host = installMetricTap(loom, exporter, backgroundScope, config)
        val client = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config)

        val snap = client.pull()
        assertEquals(4L, snap.sums.getValue(MetricKey("req", MetricKind.SUM)))
        assertEquals(2.5, snap.doubleSums.getValue(MetricKey("cpu", MetricKind.SUM)))
        assertEquals(21.0, snap.gauges.getValue(MetricKey("temp", MetricKind.GAUGE)))
        assertEquals(1L, snap.cardinalities.getValue(MetricKey("users", MetricKind.CARDINALITY)))

        client.close()
        host.close()
    }
}
