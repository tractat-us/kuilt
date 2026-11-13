package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [WarpMetricExporter.record]: a batch of updates costs one encode and one durable write per metric
 * kind it touches, and leaves the exporter — in memory and on disk — where applying the same updates
 * one by one would (#3284).
 */
class WarpMetricExporterBatchTest {

    private val replica = ReplicaId("A")

    private fun exporter(store: DurableStore) = WarpMetricExporter(replica = replica, store = store)

    private val requests = MetricKey("requests", MetricKind.SUM, mapOf("route" to "/a"))
    private val requestsB = MetricKey("requests", MetricKind.SUM, mapOf("route" to "/b"))
    private val cpuSeconds = MetricKey("cpu.seconds", MetricKind.SUM)
    private val queue = MetricKey("queue.depth", MetricKind.GAUGE)
    private val temp = MetricKey("temp", MetricKind.GAUGE)
    private val users = MetricKey("users", MetricKind.CARDINALITY)
    private val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)

    /** Twelve updates across all five kinds, several landing on the same series. */
    private val mixedBatch: List<MetricUpdate> = listOf(
        MetricUpdate.IncrementSum(requests, 3L),
        MetricUpdate.SetGauge(queue, 4.0, timestamp = 10L),
        MetricUpdate.IncrementSum(requestsB, 1L),
        MetricUpdate.IncrementSumDouble(cpuSeconds, 0.25),
        MetricUpdate.AddCardinality(users, "u1"),
        MetricUpdate.RecordHistogram(latency, 12.5),
        MetricUpdate.IncrementSum(requests, 2L),
        MetricUpdate.SetGauge(temp, 21.5, timestamp = 11L),
        MetricUpdate.IncrementSumDouble(cpuSeconds, 0.5),
        MetricUpdate.AddCardinality(users, "u2"),
        MetricUpdate.RecordHistogram(latency, 480.0),
        MetricUpdate.SetGauge(queue, 6.0, timestamp = 12L),
    )

    /** The same updates through the single-point mutators — the semantics a batch must reproduce. */
    private suspend fun WarpMetricExporter.applyOneByOne(updates: List<MetricUpdate>): MetricExportResult? {
        var firstFailure: MetricExportResult? = null
        updates.forEach { u ->
            val r = when (u) {
                is MetricUpdate.IncrementSum -> incrementSum(u.key, u.by)
                is MetricUpdate.IncrementSumDouble -> incrementSumDouble(u.key, u.by)
                is MetricUpdate.SetGauge -> setGauge(u.key, u.value, u.timestamp)
                is MetricUpdate.AddCardinality -> addCardinality(u.key, u.element)
                is MetricUpdate.RecordHistogram -> recordHistogram(u.key, u.value)
            }
            if (firstFailure == null && r is MetricExportResult.Failure) firstFailure = r
        }
        return firstFailure
    }

    private fun RecordingStore.writesByKey(): Map<String, Int> =
        operations().filter { it.kind == StoreOpKind.WRITE }.groupingBy { it.key.name }.eachCount()

    @Test
    fun aBatchWritesEachKindItTouchesExactlyOnce() = runTest {
        // Control: the one-by-one path on an identical store. It proves the RecordingStore counts
        // writes at all — one per update — so the batch arm's "one per kind" is a measured drop,
        // not a counter that never moved.
        val referenceStore = RecordingStore()
        val reference = exporter(referenceStore)
        reference.applyOneByOne(mixedBatch)

        val store = RecordingStore()
        val batched = exporter(store)
        val result = batched.record(mixedBatch)

        val recovered = exporter(store).also { it.recover() }
        assertAll(
            { assertEquals(12, referenceStore.writesByKey().values.sum(), "control: one write per update") },
            { assertEquals(MetricExportResult.Success, result) },
            {
                assertEquals(
                    mapOf(
                        "otel.metrics.sums" to 1,
                        "otel.metrics.sums.double" to 1,
                        "otel.metrics.gauges" to 1,
                        "otel.metrics.cardinalities" to 1,
                        "otel.metrics.histograms" to 1,
                    ),
                    store.writesByKey(),
                )
            },
            { assertEquals(reference.snapshotAll(), batched.snapshotAll(), "in memory") },
            { assertEquals(reference.snapshotAll(), recovered.snapshotAll(), "on disk") },
            { assertEquals(5L, recovered.sumValue(requests)) },
        )
    }

    @Test
    fun aBatchWritesNoKindItDoesNotTouch() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val gaugesOnly = listOf(
            MetricUpdate.SetGauge(queue, 1.0, timestamp = 1L),
            MetricUpdate.SetGauge(temp, 2.0, timestamp = 2L),
            MetricUpdate.SetGauge(queue, 3.0, timestamp = 3L),
        )
        val result = exporter.record(gaugesOnly)
        assertAll(
            { assertEquals(MetricExportResult.Success, result) },
            { assertEquals(mapOf("otel.metrics.gauges" to 1), store.writesByKey()) },
            { assertEquals(3.0, exporter.gaugeValue(queue)) },
        )
    }

    @Test
    fun anEmptyBatchSucceedsWithoutWriting() = runTest {
        val store = RecordingStore()
        val result = exporter(store).record(emptyList())
        assertAll(
            { assertEquals(MetricExportResult.Success, result) },
            { assertEquals(emptyMap(), store.writesByKey()) },
        )
    }

    @Test
    fun aRefusedWriteInABatchKeepsTheSingleUpdateContract() = runTest {
        // The store refuses the sums key and takes every other one. Sums is the first kind a batch
        // writes, so a batch that stopped at its first failure would also leave the four later
        // kinds off disk — which the one-by-one path never does.
        val refusesSums: (us.tractat.kuilt.store.StoreKey) -> Boolean = { it.name == "otel.metrics.sums" }

        val referenceBacking = InMemoryDurableStore()
        val referenceStore = WriteRefusingStore(referenceBacking, refusesSums).also { it.refuseWrites() }
        val reference = exporter(referenceStore)
        val referenceResult = reference.applyOneByOne(mixedBatch)

        val backing = InMemoryDurableStore()
        val store = WriteRefusingStore(backing, refusesSums).also { it.refuseWrites() }
        val batched = exporter(store)
        val result = batched.record(mixedBatch)

        val referenceOnDisk = exporter(referenceBacking).also { it.recover() }.snapshotAll()
        val onDisk = exporter(backing).also { it.recover() }.snapshotAll()
        assertAll(
            // Preconditions: the rig refused something on both paths, and the control's first
            // failure is the refused sums write.
            { assertTrue(referenceStore.refusedWrites() > 0, "control rig never refused") },
            { assertTrue(store.refusedWrites() > 0, "batch rig never refused") },
            { assertIs<MetricExportResult.Failure>(referenceResult) },
            // The batch returns a Failure carrying the refusal, like the first failing single call.
            {
                val failure = assertIs<MetricExportResult.Failure>(result)
                assertEquals("store refused otel.metrics.sums", failure.cause.message)
            },
            // "Not yet durable", not "not counted": every update is in memory on both paths.
            { assertEquals(reference.snapshotAll(), batched.snapshotAll(), "in memory") },
            { assertEquals(5L, batched.sumValue(requests)) },
            // And the kinds the store did take are on disk on both paths; sums is on neither.
            { assertEquals(referenceOnDisk, onDisk, "on disk") },
            { assertTrue(onDisk.sums.isEmpty(), "sums were refused, so none should be on disk") },
            { assertEquals(2, onDisk.gauges.size) },
            { assertEquals(1, onDisk.histograms.size) },
        )
    }
}
