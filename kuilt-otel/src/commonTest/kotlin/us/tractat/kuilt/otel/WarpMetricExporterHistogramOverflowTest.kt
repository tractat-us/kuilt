package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A histogram update that would carry this replica's slot past [Long.MAX_VALUE] is refused before
 * anything in its batch is applied, as a sum increment is (#3354). Before the fix it threw part-way
 * through [WarpMetricExporter.record], leaving the earlier updates applied in memory and unwritten.
 */
class WarpMetricExporterHistogramOverflowTest {

    private val replica = ReplicaId("A")
    private val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
    private val requests = MetricKey("requests", MetricKind.SUM)
    private val queue = MetricKey("queue.depth", MetricKind.GAUGE)

    private fun exporter(store: RecordingStore) = WarpMetricExporter(replica = replica, store = store)

    private fun RecordingStore.writesByKey(): Map<String, Int> =
        operations().filter { it.kind == StoreOpKind.WRITE }.groupingBy { it.key.name }.eachCount()

    /** One positive OTLP bucket at [offset], at the exporter's own scale [scale], holding [count]. */
    private fun oneBucket(scale: Int, offset: Int, count: Long) = MetricUpdate.AddExponentialHistogram(
        latency, scale = scale, zeroCount = 0L,
        positiveOffset = offset, positiveCounts = listOf(count),
        negativeOffset = 0, negativeCounts = emptyList(),
    )

    /** The batch every test refuses, minus the histogram update: a gauge and a sum that fit. */
    private val fitsAlone = listOf(
        MetricUpdate.SetGauge(queue, 1.0, timestamp = 1L),
        MetricUpdate.IncrementSum(requests, 1L),
    )

    /**
     * Asserts [record] refused a batch of [fitsAlone] plus [overflowing] whole: nothing applied,
     * nothing written, and the same batch without [overflowing] then lands.
     */
    private suspend fun assertRefusedWhole(store: RecordingStore, exporter: WarpMetricExporter, overflowing: List<MetricUpdate>) {
        val memoryBefore = exporter.snapshotAll()
        val writesBefore = store.writesByKey()

        val result = exporter.record(fitsAlone + overflowing)

        val memoryAfter = exporter.snapshotAll()
        val writesAfter = store.writesByKey()
        val control = exporter.record(fitsAlone)
        assertAll(
            {
                val cause = assertIs<MetricHistogramOverflowException>(assertIs<MetricExportResult.Failure>(result).cause)
                assertEquals(latency, cause.key, "the refusal names the series")
            },
            { assertEquals(memoryBefore, memoryAfter, "nothing applied in memory") },
            { assertEquals(writesBefore, writesAfter, "nothing written") },
            { assertEquals(MetricExportResult.Success, control, "the control: the rest of the batch fits") },
            { assertEquals(1L, exporter.sumValue(requests)) },
        )
    }

    @Test
    fun anExponentialHistogramThatWouldCarryItsSlotPastLongMaxValueIsRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        assertEquals(MetricExportResult.Success, exporter.record(listOf(oneBucket(scale, offset = 10, count = Long.MAX_VALUE - 1L))))
        assertEquals(Long.MAX_VALUE - 1L, exporter.histogramSnapshot(latency).positiveBuckets[11], "the rig: the slot is one short")

        assertRefusedWhole(store, exporter, listOf(oneBucket(scale, offset = 10, count = 3L)))
    }

    @Test
    fun aValueRecordedIntoASaturatedSlotIsRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val probe = WarpMetricExporter(replica = replica, store = InMemoryDurableStore())
        probe.recordHistogram(latency, 12.5)
        val index = probe.histogramSnapshot(latency).positiveBuckets.keys.single()
        // The sketch's index is the OTLP index plus one.
        assertEquals(MetricExportResult.Success, exporter.record(listOf(oneBucket(exporter.histogramScale, index - 1, Long.MAX_VALUE))))
        assertEquals(Long.MAX_VALUE, exporter.histogramSnapshot(latency).positiveBuckets[index], "the rig: 12.5's slot is full")

        assertRefusedWhole(store, exporter, listOf(MetricUpdate.RecordHistogram(latency, 12.5)))
    }

    /**
     * Both signs share one overflow counter. Negative values have filled it while the positive top
     * bucket is still empty, so a large positive value is refused by the overflow slot alone.
     */
    @Test
    fun aPositiveValueIntoAnOverflowSlotNegativeValuesFilledIsRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val probe = WarpMetricExporter(replica = replica, store = InMemoryDurableStore())
        probe.recordHistogram(latency, 1e30)
        val top = probe.histogramSnapshot(latency).positiveBuckets.keys.single()
        // The sketch's index is the OTLP index plus one, so OTLP index `top` lands one past the top bucket.
        val negativeOverflow = MetricUpdate.AddExponentialHistogram(
            latency, scale = exporter.histogramScale, zeroCount = 0L,
            positiveOffset = 0, positiveCounts = emptyList(),
            negativeOffset = top, negativeCounts = listOf(Long.MAX_VALUE),
        )
        assertEquals(MetricExportResult.Success, exporter.record(listOf(negativeOverflow)))
        val seeded = exporter.histogramSnapshot(latency)
        assertAll(
            { assertEquals(Long.MAX_VALUE, seeded.overflowCount, "the rig: the overflow slot is full") },
            { assertEquals(Long.MAX_VALUE, seeded.negativeBuckets[top], "the rig: the negative top slot is full") },
            { assertEquals(emptyMap(), seeded.positiveBuckets, "the rig: the positive top slot is empty") },
        )

        assertRefusedWhole(store, exporter, listOf(MetricUpdate.RecordHistogram(latency, 1e30)))
    }

    /** Two OTLP buckets at a finer scale fold into one sketch bucket, and their sum is past [Long.MAX_VALUE]. */
    @Test
    fun countsThatFoldPastLongMaxValueAtAFinerScaleAreRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val finer = MetricUpdate.AddExponentialHistogram(
            latency, scale = exporter.histogramScale + 1, zeroCount = 0L,
            positiveOffset = 10, positiveCounts = listOf(Long.MAX_VALUE, 1L),
            negativeOffset = 0, negativeCounts = emptyList(),
        )

        assertRefusedWhole(store, exporter, listOf(finer))
    }

    /** Three updates to one series: each fits on its own, and only the batch together overflows. */
    @Test
    fun histogramUpdatesThatOverflowOnlyTogetherAreRefusedWhole() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        val each = listOf(
            oneBucket(scale, offset = 10, count = Long.MAX_VALUE - 1L),
            oneBucket(scale, offset = 10, count = 1L),
            oneBucket(scale, offset = 10, count = 1L),
        )

        assertRefusedWhole(store, exporter, each)
        assertEquals(MetricExportResult.Success, exporter.record(each.take(2)), "the control: two of the three fit")
        assertEquals(Long.MAX_VALUE, exporter.histogramSnapshot(latency).positiveBuckets[11])
    }
}
