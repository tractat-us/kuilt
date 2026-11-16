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
                is MetricUpdate.AddUpDownSum -> addUpDownSum(u.key, u.by)
                is MetricUpdate.AddUpDownSumDouble -> addUpDownSumDouble(u.key, u.by)
                is MetricUpdate.SetUpDownSum -> setUpDownSum(u.key, u.level)
                is MetricUpdate.SetUpDownSumDouble -> setUpDownSumDouble(u.key, u.level)
                // No single-point mutator: its single-point form is a batch of one.
                is MetricUpdate.AddExponentialHistogram,
                is MetricUpdate.Forget,
                -> record(listOf(u))
            }
            if (firstFailure == null && r != MetricExportResult.Success) firstFailure = r
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

    private val inFlight = MetricKey("in_flight", MetricKind.UP_DOWN_SUM, mapOf("route" to "/a"))
    private val inFlightB = MetricKey("in_flight", MetricKind.UP_DOWN_SUM, mapOf("route" to "/b"))
    private val inFlightTwin = MetricKey("in_flight", MetricKind.GAUGE, mapOf("route" to "/a"))

    /**
     * Up-down sets and adds, both maps, and [MetricUpdate.Forget]s mixed with the other kinds,
     * including a forget followed by a set of the very key it forgot, and a set that moves nothing
     * (#3307). The batch must end where the same updates one by one end, in memory and on disk.
     * Both exporters start from the same state, so each forget has a series to drop.
     */
    @Test
    fun aBatchWithUpDownUpdatesAndForgetsEndsWhereOneByOneEnds() = runTest {
        val seed = listOf(
            MetricUpdate.SetUpDownSum(inFlight, 9L),
            MetricUpdate.SetUpDownSumDouble(inFlightB, 2.5),
            MetricUpdate.SetGauge(inFlightTwin, 9.0, timestamp = 1L),
            MetricUpdate.IncrementSum(requests, 4L),
        )
        val batch = listOf(
            MetricUpdate.SetUpDownSum(inFlight, 3L),
            MetricUpdate.IncrementSum(requests, 1L),
            MetricUpdate.Forget(inFlightTwin),
            MetricUpdate.AddUpDownSum(inFlightB, -4L),
            MetricUpdate.SetUpDownSumDouble(inFlightB, 0.75),
            MetricUpdate.Forget(inFlight),
            MetricUpdate.SetUpDownSum(inFlight, 5L),
            MetricUpdate.SetGauge(queue, 2.0, timestamp = 2L),
            MetricUpdate.AddUpDownSumDouble(inFlight, -1.25),
            MetricUpdate.Forget(requests),
            MetricUpdate.IncrementSum(requests, 2L),
            MetricUpdate.SetUpDownSum(inFlightB, -4L),
        )
        val referenceStore = RecordingStore()
        val reference = exporter(referenceStore).also { it.record(seed) }
        val referenceResult = reference.applyOneByOne(batch)

        val store = RecordingStore()
        val batched = exporter(store).also { it.record(seed) }
        val seeded = batched.snapshotAll()
        val result = batched.record(batch)
        val recovered = exporter(store).also { it.recover() }

        assertAll(
            { assertEquals(setOf(inFlight), seeded.upDownSums.keys, "the rig: the forgotten up-down series was held") },
            { assertEquals(setOf(inFlightTwin), seeded.gauges.keys, "the rig: the gauge twin was held") },
            { assertEquals(null, referenceResult, "control: one by one, every update succeeded") },
            { assertEquals(MetricExportResult.Success, result) },
            { assertEquals(reference.snapshotAll(), batched.snapshotAll(), "in memory") },
            { assertEquals(reference.snapshotAll(), recovered.snapshotAll(), "on disk") },
            { assertEquals(5L, batched.upDownSumValue(inFlight), "the set after the forget started from nothing") },
            { assertEquals(-1.25, batched.upDownSumDoubleValue(inFlight)) },
            { assertEquals(2L, batched.sumValue(requests), "the increment after the forget started from nothing") },
            { assertEquals(-4L, batched.upDownSumValue(inFlightB)) },
            { assertEquals(null, batched.gaugeValue(inFlightTwin), "the twin is gone") },
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

    /**
     * A batch whose sum increments to one series add up past what this replica's count can take
     * is refused whole (#3318): each increment fits on its own, only the two together overflow.
     * Nothing is applied in memory and nothing is written, the gauge and the other series
     * included, so the batch is never half-applied.
     */
    @Test
    fun aBatchWhoseSumIncrementsOverflowTogetherIsRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        assertEquals(MetricExportResult.Success, exporter.incrementSum(requests, Long.MAX_VALUE - 5L))
        val memoryBefore = exporter.snapshotAll()
        val writesBefore = store.writesByKey()
        val fitsAlone = listOf(
            MetricUpdate.SetGauge(queue, 1.0, timestamp = 1L),
            MetricUpdate.IncrementSum(requestsB, 1L),
            MetricUpdate.IncrementSum(requests, 3L),
        )

        val result = exporter.record(fitsAlone + MetricUpdate.IncrementSum(requests, 3L))

        val cause = assertIs<MetricSumOverflowException>(assertIs<MetricExportResult.Refused>(result).overflow)
        val memoryAfter = exporter.snapshotAll()
        val writesAfter = store.writesByKey()
        // The control: without its last increment the same batch fits, so the refusal above is the
        // overflow and not a rig that refuses everything.
        val control = exporter.record(fitsAlone)
        assertAll(
            { assertEquals(requests, cause.key) },
            { assertEquals(Long.MAX_VALUE - 2L, cause.total, "the batch's own earlier increment is counted") },
            { assertEquals(3L, cause.by) },
            { assertEquals(memoryBefore, memoryAfter, "nothing applied in memory") },
            { assertEquals(writesBefore, writesAfter, "nothing written") },
            { assertEquals(MetricExportResult.Success, control) },
            { assertEquals(Long.MAX_VALUE - 2L, exporter.sumValue(requests)) },
        )
    }

    @Test
    fun aSingleSumIncrementThatWouldOverflowIsRefusedAndAppliesNothing() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        exporter.incrementSum(requests, Long.MAX_VALUE - 5L)
        val writesBefore = store.writesByKey()

        val result = exporter.incrementSum(requests, 6L)

        assertAll(
            { assertIs<MetricSumOverflowException>(assertIs<MetricExportResult.Refused>(result).overflow) },
            { assertEquals(Long.MAX_VALUE - 5L, exporter.sumValue(requests)) },
            { assertEquals(writesBefore, store.writesByKey()) },
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
    fun twoRefusedKindsReturnTheEarlierKindsFailure() = runTest {
        // The batch names histograms first, but kinds are written in a fixed order, so the
        // double-sum refusal is the first failure — whatever order the updates arrived in.
        val refused = setOf("otel.metrics.sums.double", "otel.metrics.histograms")
        val store = WriteRefusingStore(InMemoryDurableStore()) { it.name in refused }.also { it.refuseWrites() }
        val result = exporter(store).record(
            listOf(
                MetricUpdate.RecordHistogram(latency, 1.0),
                MetricUpdate.SetGauge(queue, 1.0, timestamp = 1L),
                MetricUpdate.IncrementSumDouble(cpuSeconds, 1.0),
            ),
        )
        assertAll(
            { assertEquals(2, store.refusedWrites(), "precondition: both refused kinds were attempted") },
            {
                val partial = assertIs<MetricExportResult.Partial>(result)
                assertEquals(
                    listOf("store refused otel.metrics.sums.double", "store refused otel.metrics.histograms"),
                    partial.causes.map { it.message },
                    "each refused write, in kind order",
                )
            },
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
            { assertIs<MetricExportResult.Partial>(referenceResult) },
            // The batch returns a Partial carrying the refusal, like the first failing single call,
            // and lists nothing as uncounted: a refused write's updates are counted in memory.
            {
                val partial = assertIs<MetricExportResult.Partial>(result)
                assertEquals(listOf("store refused otel.metrics.sums"), partial.causes.map { it.message })
                assertEquals(emptyList(), partial.uncounted)
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
