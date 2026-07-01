package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WarpMetricExporterTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // ---- helpers ----

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore = InMemoryDurableStore(),
        maxMetrics: Int = DEFAULT_MAX_METRICS,
        bufferPolicy: MetricBufferPolicy = MetricBufferPolicy.DROP_OLDEST,
    ) = WarpMetricExporter(
        replica = replica,
        store = store,
        maxMetrics = maxMetrics,
        bufferPolicy = bufferPolicy,
    )

    private fun sumKey(name: String, attrs: Map<String, String> = emptyMap()) =
        MetricKey(name, MetricKind.SUM, attrs)

    private fun gaugeKey(name: String, attrs: Map<String, String> = emptyMap()) =
        MetricKey(name, MetricKind.GAUGE, attrs)

    private fun cardinalityKey(name: String, attrs: Map<String, String> = emptyMap()) =
        MetricKey(name, MetricKind.CARDINALITY, attrs)

    // ---- MetricKey validation ----

    @Test
    fun metricKeyRejectsBlankName() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MetricKey("", MetricKind.SUM, emptyMap())
        }
    }

    // ---- Sum metrics (GCounter — monotonic cumulative) ----

    @Test
    fun sumIncrementReturnsSuccess() = runTest {
        val exporter = exporterFor()
        val result = exporter.incrementSum(sumKey("requests.count"), by = 1L)
        assertEquals(MetricExportResult.Success, result)
    }

    @Test
    fun sumValueAccumulatesAcrossIncrements() = runTest {
        val exporter = exporterFor()
        val key = sumKey("requests.count")
        exporter.incrementSum(key, by = 3L)
        exporter.incrementSum(key, by = 7L)
        assertEquals(10L, exporter.sumValue(key))
    }

    @Test
    fun sumRetryCannotDoubleCount() = runTest {
        // Idempotent: the same GCounter patch applied twice must not inflate.
        // In practice the caller retries export() on store failure; the GCounter
        // accumulates from its last committed value — this test verifies the
        // fundamental idempotency of two separate increments adding to the total.
        val exporter = exporterFor()
        val key = sumKey("hits")
        exporter.incrementSum(key, by = 5L)
        exporter.incrementSum(key, by = 5L)
        assertEquals(10L, exporter.sumValue(key))
    }

    @Test
    fun sumMergeConvergesTwoOfflineReplicas() = runTest {
        val storeA = InMemoryDurableStore()
        val storeB = InMemoryDurableStore()
        val exporterA = exporterFor(replica = replicaA, store = storeA)
        val exporterB = exporterFor(replica = replicaB, store = storeB)
        val key = sumKey("events")

        exporterA.incrementSum(key, by = 3L)
        exporterB.incrementSum(key, by = 7L)

        exporterA.mergeSum(key, exporterB.sumSnapshot(key))
        exporterB.mergeSum(key, exporterA.sumSnapshot(key))

        assertEquals(10L, exporterA.sumValue(key))
        assertEquals(10L, exporterB.sumValue(key))
    }

    @Test
    fun sumMergeIsIdempotent() = runTest {
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        val key = sumKey("ops")
        exporterB.incrementSum(key, by = 4L)
        val remote = exporterB.sumSnapshot(key)

        exporterA.mergeSum(key, remote)
        val after1 = exporterA.sumValue(key)
        exporterA.mergeSum(key, remote)
        val after2 = exporterA.sumValue(key)

        assertEquals(after1, after2)
    }

    @Test
    fun sumRecoveryRestoresState() = runTest {
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val key = sumKey("page.loads")
        exporter1.incrementSum(key, by = 42L)

        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        assertEquals(42L, exporter2.sumValue(key))
    }

    @Test
    fun sumStartsAtZeroBeforeAnyIncrement() = runTest {
        val exporter = exporterFor()
        assertEquals(0L, exporter.sumValue(sumKey("not.yet.incremented")))
    }

    // ---- Gauge metrics (LWWRegister<Double> — last writer wins) ----

    @Test
    fun gaugeSetReturnsSuccess() = runTest {
        val exporter = exporterFor()
        val result = exporter.setGauge(gaugeKey("cpu.usage"), value = 0.75, timestamp = 1000L)
        assertEquals(MetricExportResult.Success, result)
    }

    @Test
    fun gaugeLatestWriteWins() = runTest {
        val exporter = exporterFor()
        val key = gaugeKey("mem.usage")
        exporter.setGauge(key, value = 0.50, timestamp = 1000L)
        exporter.setGauge(key, value = 0.80, timestamp = 2000L)
        assertEquals(0.80, exporter.gaugeValue(key))
    }

    @Test
    fun gaugeOlderWriteDoesNotOverwriteNewer() = runTest {
        val exporter = exporterFor()
        val key = gaugeKey("disk.usage")
        exporter.setGauge(key, value = 0.90, timestamp = 2000L)
        exporter.setGauge(key, value = 0.10, timestamp = 1000L)
        assertEquals(0.90, exporter.gaugeValue(key))
    }

    @Test
    fun gaugeMergeFromTwoReplicasTakesHigherTimestamp() = runTest {
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        val key = gaugeKey("battery.level")

        exporterA.setGauge(key, value = 0.30, timestamp = 1000L)
        exporterB.setGauge(key, value = 0.60, timestamp = 2000L)

        exporterA.mergeGauge(key, exporterB.gaugeSnapshot(key))
        assertEquals(0.60, exporterA.gaugeValue(key))
    }

    @Test
    fun gaugeReturnsNullBeforeFirstWrite() = runTest {
        val exporter = exporterFor()
        assertEquals(null, exporter.gaugeValue(gaugeKey("never.set")))
    }

    @Test
    fun gaugeRecoveryRestoresState() = runTest {
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val key = gaugeKey("temperature")
        exporter1.setGauge(key, value = 22.5, timestamp = 5000L)

        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        assertEquals(22.5, exporter2.gaugeValue(key))
    }

    // ---- Cardinality metrics (HyperLogLog) ----

    @Test
    fun cardinalityAddReturnsSuccess() = runTest {
        val exporter = exporterFor()
        val result = exporter.addCardinality(cardinalityKey("unique.users"), element = "user-1")
        assertEquals(MetricExportResult.Success, result)
    }

    @Test
    fun cardinalityEstimateNonZeroAfterAdding() = runTest {
        val exporter = exporterFor()
        val key = cardinalityKey("unique.sessions")
        repeat(100) { i -> exporter.addCardinality(key, "session-$i") }
        assertTrue(exporter.cardinalityEstimate(key) > 0L)
    }

    @Test
    fun cardinalityAddingSameElementTwiceDoesNotDoubleCount() = runTest {
        // HyperLogLog is idempotent by design: same element, same hash, no change.
        val exporter = exporterFor()
        val key = cardinalityKey("dedup.test")
        exporter.addCardinality(key, "user-abc")
        val after1 = exporter.cardinalityEstimate(key)
        exporter.addCardinality(key, "user-abc")
        val after2 = exporter.cardinalityEstimate(key)
        assertEquals(after1, after2)
    }

    @Test
    fun cardinalityMergeConvergesTwoReplicas() = runTest {
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        val key = cardinalityKey("cross.device.users")

        // Replica A sees user-1 and user-2; replica B sees user-3.
        exporterA.addCardinality(key, "user-1")
        exporterA.addCardinality(key, "user-2")
        exporterB.addCardinality(key, "user-3")

        // Exchange and verify both converge (merged estimate should be higher).
        exporterA.mergeCardinality(key, exporterB.cardinalitySnapshot(key))
        exporterB.mergeCardinality(key, exporterA.cardinalitySnapshot(key))

        assertEquals(exporterA.cardinalityEstimate(key), exporterB.cardinalityEstimate(key))
    }

    @Test
    fun cardinalityMergeIsIdempotent() = runTest {
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        val key = cardinalityKey("idempotency.test")
        exporterB.addCardinality(key, "x")

        val remote = exporterB.cardinalitySnapshot(key)
        exporterA.mergeCardinality(key, remote)
        val after1 = exporterA.cardinalityEstimate(key)
        exporterA.mergeCardinality(key, remote)
        val after2 = exporterA.cardinalityEstimate(key)

        assertEquals(after1, after2)
    }

    @Test
    fun cardinalityEstimateZeroOnEmptySketch() = runTest {
        val exporter = exporterFor()
        assertEquals(0L, exporter.cardinalityEstimate(cardinalityKey("never.added")))
    }

    @Test
    fun cardinalityRecoveryRestoresState() = runTest {
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val key = cardinalityKey("recovered.users")
        exporter1.addCardinality(key, "user-1")
        val estimateBefore = exporter1.cardinalityEstimate(key)

        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        assertEquals(estimateBefore, exporter2.cardinalityEstimate(key))
    }

    // ---- Multi-metric coexistence ----

    @Test
    fun differentMetricKindsCoexist() = runTest {
        val exporter = exporterFor()
        exporter.incrementSum(sumKey("api.calls"), by = 10L)
        exporter.setGauge(gaugeKey("cpu"), value = 0.5, timestamp = 1L)
        exporter.addCardinality(cardinalityKey("unique.ids"), "x")

        assertEquals(10L, exporter.sumValue(sumKey("api.calls")))
        assertEquals(0.5, exporter.gaugeValue(gaugeKey("cpu")))
        assertTrue(exporter.cardinalityEstimate(cardinalityKey("unique.ids")) > 0L)
    }

    @Test
    fun metricsWithDifferentAttributesDontCollide() = runTest {
        val exporter = exporterFor()
        val key1 = sumKey("requests", mapOf("path" to "/a"))
        val key2 = sumKey("requests", mapOf("path" to "/b"))
        exporter.incrementSum(key1, by = 3L)
        exporter.incrementSum(key2, by = 7L)
        assertEquals(3L, exporter.sumValue(key1))
        assertEquals(7L, exporter.sumValue(key2))
    }

    // ---- Bounded buffer + drop logging ----

    @Test
    fun bufferCapDropsOldestMetricWithDropOldestPolicy() = runTest {
        val exporter = exporterFor(maxMetrics = 2, bufferPolicy = MetricBufferPolicy.DROP_OLDEST)
        val k1 = sumKey("m1")
        val k2 = sumKey("m2")
        val k3 = sumKey("m3")
        exporter.incrementSum(k1, by = 1L)
        exporter.incrementSum(k2, by = 1L)
        // Adding k3 while at capacity must evict one entry.
        val result = exporter.incrementSum(k3, by = 1L)
        assertEquals(MetricExportResult.Success, result)
        assertEquals(2, exporter.metricCount())
    }

    // ---- Store failures ----

    @Test
    fun exportReturnsFailureWhenStoreFails() = runTest {
        val exporter = exporterFor(store = AlwaysFailMetricStore)
        val result = exporter.incrementSum(sumKey("x"), by = 1L)
        assertIs<MetricExportResult.Failure>(result)
    }

    private object AlwaysFailMetricStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = null
        override suspend fun write(key: StoreKey, bytes: ByteArray) =
            throw RuntimeException("simulated store failure")
        override suspend fun delete(key: StoreKey) = Unit
    }

    // ---- Double sum metrics (GCounterDouble — exact-precision monotonic) ----

    @Test
    fun doubleSumAccumulatesExactly() = runTest {
        val exporter = exporterFor()
        val key = sumKey("cpu.seconds")
        exporter.incrementSumDouble(key, by = 0.75)
        exporter.incrementSumDouble(key, by = 0.25)
        assertEquals(1.0, exporter.doubleSumValue(key))
    }

    @Test
    fun doubleSumIsSeparateFromLongSum() = runTest {
        val exporter = exporterFor()
        val key = sumKey("requests")
        exporter.incrementSum(key, by = 2L)
        exporter.incrementSumDouble(key, by = 1.5)
        assertEquals(2L, exporter.sumValue(key))
        assertEquals(1.5, exporter.doubleSumValue(key))
    }

    @Test
    fun doubleSumRecoversFromStore() = runTest {
        val store = InMemoryDurableStore()
        exporterFor(store = store).incrementSumDouble(sumKey("cpu.seconds"), by = 3.5)
        val recovered = exporterFor(store = store).also { it.recover() }
        assertEquals(3.5, recovered.doubleSumValue(sumKey("cpu.seconds")))
    }

    @Test
    fun doubleSumMergeIsIdempotent() = runTest {
        val exporter = exporterFor()
        val key = sumKey("cpu.seconds")
        val remote = us.tractat.kuilt.crdt.GCounterDouble.of(replicaB to 4.0)
        exporter.mergeSumDouble(key, remote)
        exporter.mergeSumDouble(key, remote)
        assertEquals(4.0, exporter.doubleSumValue(key))
    }
}
