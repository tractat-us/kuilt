package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    /**
     * A device that has recorded only histograms is a non-empty device: [MetricTapClient.pull]
     * returns instead of waiting out `pullTimeout` for a state it already has.
     */
    @Test
    fun pullReturnsTheHistogramsOfAHistogramOnlyDevice() = runTest {
        val key = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
        val exporter = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())
        repeat(3) { exporter.recordHistogram(key, 100.0) }

        val loom = InMemoryLoom()
        val host = installMetricTap(loom, exporter, backgroundScope, config)
        val client = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config)

        val snap = client.pull()
        val sketch = assertNotNull(snap.histograms[key], "the histogram did not reach the puller")
        // The empties are the rig: any other kind present would satisfy pull()'s non-empty wait.
        assertAll(
            { assertTrue(snap.sums.isEmpty() && snap.doubleSums.isEmpty(), "the rig recorded a sum") },
            { assertTrue(snap.gauges.isEmpty() && snap.cardinalities.isEmpty(), "the rig recorded a non-sum") },
            { assertEquals(3L, sketch.count) },
            { assertEquals(100.0, sketch.quantile(0.5), absoluteTolerance = 100.0 * sketch.relativeAccuracy) },
        )

        client.close()
        host.close()
    }

    /**
     * Only a histogram changes between the two states, so a [MetricTapClient.tail] that collapses
     * it away has nothing to emit: `distinctUntilChanged` sees an equal value.
     */
    @Test
    fun tailEmitsWhenOnlyAHistogramChanges() = runTest {
        val sumKey = MetricKey("req", MetricKind.SUM)
        val histogramKey = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
        val exporter = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())
        exporter.incrementSum(sumKey, by = 1L)
        exporter.recordHistogram(histogramKey, 100.0)

        val loom = InMemoryLoom()
        val host = installMetricTap(loom, exporter, backgroundScope, config)
        val client = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config)

        val before = client.pull()
        exporter.recordHistogram(histogramKey, 100.0)
        host.sync()

        val after = withTimeout(5.seconds) { client.tail().first { it != before } }
        assertAll(
            { assertEquals(before.sums, after.sums, "the rig changed something besides the histogram") },
            { assertEquals(1L, before.histograms[histogramKey]?.count) },
            { assertEquals(2L, after.histograms[histogramKey]?.count) },
        )

        client.close()
        host.close()
    }
}
