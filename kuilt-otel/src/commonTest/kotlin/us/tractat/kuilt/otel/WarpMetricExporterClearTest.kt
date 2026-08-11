package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins [WarpMetricExporter.clear] (#2208), including the limit it cannot escape. */
class WarpMetricExporterClearTest {

    private val replicaA = ReplicaId("A")

    // MetricKey has no default for `kind`, and `name` must not be blank.
    private val sumKey = MetricKey("requests", MetricKind.SUM, mapOf("route" to "/health"))
    private val doubleSumKey = MetricKey("bytes.sent", MetricKind.SUM)
    private val gaugeKey = MetricKey("queue.depth", MetricKind.GAUGE)
    private val cardinalityKey = MetricKey("unique.users", MetricKind.CARDINALITY)
    private val histogramKey = MetricKey("latency.ms", MetricKind.EXPONENTIAL_HISTOGRAM)

    /** Populate all five kinds, so a clear that forgets one of the five maps reddens here. */
    private suspend fun WarpMetricExporter.populateEveryKind() {
        incrementSum(sumKey, by = 7L)
        incrementSumDouble(doubleSumKey, by = 2.5)
        // setGauge takes an explicit observation timestamp — there is no wall-clock default.
        setGauge(gaugeKey, value = 1.5, timestamp = 1_700_000_000_000L)
        addCardinality(cardinalityKey, "user-abc")
        recordHistogram(histogramKey, 12.5)
    }

    /**
     * Every kind, not just sums. [WarpMetricExporter.clear] empties five maps and deletes five
     * keys, and asserting on one of them would leave the other four free to be dropped from the
     * implementation with this test still green.
     */
    @Test
    fun clearEmptiesEveryMetricKindAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpMetricExporter(replica = replicaA, store = store)
        exporter.populateEveryKind()
        assertEquals(5, exporter.metricCount(), "the fixture must populate all five kinds")

        assertEquals(MetricExportResult.Success, exporter.clear())

        val recovered = WarpMetricExporter(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(0, exporter.metricCount(), "the live exporter forgets every series") },
            { assertEquals(0L, exporter.sumValue(sumKey), "the live exporter forgets") },
            { assertEquals(0.0, exporter.doubleSumValue(doubleSumKey)) },
            { assertNull(exporter.gaugeValue(gaugeKey)) },
            { assertEquals(0L, exporter.cardinalityEstimate(cardinalityKey)) },
            { assertNull(exporter.histogramQuantile(histogramKey, 0.5)) },
            { assertEquals(0, recovered.metricCount(), "a fresh exporter recovers empty") },
            { assertEquals(0L, recovered.sumValue(sumKey), "a fresh exporter recovers empty") },
            { assertEquals(0.0, recovered.doubleSumValue(doubleSumKey)) },
            { assertNull(recovered.gaugeValue(gaugeKey)) },
            { assertEquals(0L, recovered.cardinalityEstimate(cardinalityKey)) },
            { assertNull(recovered.histogramQuantile(histogramKey, 0.5)) },
        )
    }

    /**
     * Deliberate, not a defect. A `GCounter` is a monotonic join, so a merge takes the
     * element-wise max and the pre-clear total comes back. Clearing metrics is therefore
     * **local-only**, which is safe on a non-gossiping device and is what the KDoc says.
     * This test exists so a change that assumes otherwise reddens here rather than in the field.
     */
    @Test
    fun aMergeAfterAClearRestoresTheOldSumBecauseAMonotonicJoinCannotForget() = runTest {
        val exporter = WarpMetricExporter(replica = replicaA, store = InMemoryDurableStore())
        exporter.incrementSum(sumKey, by = 7L)
        val peerCopy = exporter.sumSnapshot(sumKey)

        exporter.clear()
        assertEquals(0L, exporter.sumValue(sumKey))

        exporter.mergeSum(sumKey, peerCopy)
        assertEquals(7L, exporter.sumValue(sumKey), "a monotonic join has no merge-safe forget")
    }

    /**
     * The "never throws" half of the KDoc, which nothing else here pins: a store that refuses
     * every delete must surface as a [MetricExportResult.Failure] rather than propagating out of
     * a public exporter method. The in-memory maps are cleared either way — the same divergence
     * [WarpLogRecordExporter.clear] documents, so a caller must read a non-Success as
     * "count unknown" rather than as zero.
     *
     * Uses the shared [FailDeleteStore] rather than a local copy — see SegmentStoreFakes.kt.
     */
    @Test
    fun aRefusedDeleteFailsTheClearWithoutThrowingAndStillEmptiesMemory() = runTest {
        val backing = InMemoryDurableStore()
        val exporter = WarpMetricExporter(replica = replicaA, store = FailDeleteStore(backing))
        exporter.populateEveryKind()

        val result = exporter.clear()

        assertAll(
            { assertTrue(result is MetricExportResult.Failure, "a refused delete fails the clear") },
            { assertEquals(0, exporter.metricCount(), "the in-memory drop is not undone on failure") },
        )
    }
}
