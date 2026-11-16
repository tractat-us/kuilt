package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A histogram update that would carry its series' **total** count past [Long.MAX_VALUE] is refused
 * before anything in its batch is applied (#3403). The total is what the OTLP bridge exports as the
 * point's `count`: the sum over every replica of every bucket of both signs and the zero counter. A
 * series past it is one the bridge skips, so the exporter must not accept it.
 *
 * Before the fix [WarpMetricExporter.record] checked one slot at a time (#3354), so counts that each
 * fit their own slot were accepted even when they added up past [Long.MAX_VALUE] — in separate
 * buckets, in the zero counter, or in another replica's slots merged in.
 *
 * Every rig puts its series a known distance below [Long.MAX_VALUE] and asserts it did, and every
 * refusal has a control one short that lands and that the bridge still renders.
 */
class WarpMetricExporterHistogramTotalOverflowTest {

    private val replica = ReplicaId("A")
    private val latency = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)
    private val requests = MetricKey("requests", MetricKind.SUM)

    private fun exporter(store: RecordingStore) = WarpMetricExporter(replica = replica, store = store)

    private fun RecordingStore.writes(): Int = operations().count { it.kind == StoreOpKind.WRITE }

    /** Positive OTLP buckets from [offset], at [scale], holding [counts], plus [zeroCount] zeros. */
    private fun positive(scale: Int, offset: Int, vararg counts: Long, zeroCount: Long = 0L) =
        MetricUpdate.AddExponentialHistogram(
            latency, scale = scale, zeroCount = zeroCount,
            positiveOffset = offset, positiveCounts = counts.toList(),
            negativeOffset = 0, negativeCounts = emptyList(),
        )

    /** One negative OTLP bucket at [offset], at [scale], holding [count]. */
    private fun negative(scale: Int, offset: Int, count: Long) = MetricUpdate.AddExponentialHistogram(
        latency, scale = scale, zeroCount = 0L,
        positiveOffset = 0, positiveCounts = emptyList(),
        negativeOffset = offset, negativeCounts = listOf(count),
    )

    /** The count the bridge would export for [latency] right now, or `null` if it would skip the series. */
    private fun WarpMetricExporter.renderedCount(): Long? =
        renderMetricPoints(snapshotAll(), nowEpochNanos = 1L, FirstSightings(), FirstSightings(), FirstSightings())
            .filterIsInstance<MetricPoint.ExponentialHistogram>()
            .singleOrNull { it.key == latency }
            ?.count

    /**
     * Asserts [record] refused [batch] whole, naming [latency]: nothing applied in memory, nothing
     * written, and a sum riding in the same batch was not counted either.
     */
    private suspend fun assertRefusedWhole(store: RecordingStore, exporter: WarpMetricExporter, batch: List<MetricUpdate>) {
        val memoryBefore = exporter.snapshotAll()
        val writesBefore = store.writes()

        val result = exporter.record(listOf(MetricUpdate.IncrementSum(requests, 1L)) + batch)

        assertAll(
            {
                val cause = assertIs<MetricHistogramOverflowException>(assertIs<MetricExportResult.Refused>(result).overflow)
                assertEquals(latency, cause.key, "the refusal names the series")
            },
            { assertEquals(memoryBefore, exporter.snapshotAll(), "nothing applied in memory") },
            { assertEquals(writesBefore, store.writes(), "nothing written") },
            { assertEquals(0L, exporter.sumValue(requests), "the sum beside it was not counted") },
        )
    }

    /**
     * The series holds `Long.MAX_VALUE - 1` in one positive bucket. Two in a negative bucket each fit
     * their own slot, but take the total one past [Long.MAX_VALUE]; one lands it exactly on it.
     */
    @Test
    fun anUpdateThatTipsTheTotalPastLongMaxValueInAnotherBucketIsRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        assertEquals(MetricExportResult.Success, exporter.record(listOf(positive(scale, offset = 10, Long.MAX_VALUE - 1L))))
        assertAll(
            { assertEquals(Long.MAX_VALUE - 1L, exporter.histogramSnapshot(latency).count, "the rig: the total is one short") },
            { assertEquals(emptyMap(), exporter.histogramSnapshot(latency).negativeBuckets, "the rig: the negative slot is empty") },
        )

        assertRefusedWhole(store, exporter, listOf(negative(scale, offset = 10, count = 2L)))

        val control = exporter.record(listOf(negative(scale, offset = 10, count = 1L)))
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: one more lands the total on Long.MAX_VALUE") },
            { assertEquals(Long.MAX_VALUE, exporter.histogramSnapshot(latency).count) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedCount(), "and the bridge still exports it") },
        )
    }

    /**
     * A peer's slots count towards the total the bridge exports. A peer's sketch merged in holds
     * `Long.MAX_VALUE - 1`; this replica's own slot is empty, so two more fit it but not the total.
     */
    @Test
    fun anUpdateThatTipsAMergedTotalPastLongMaxValueIsRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        val prototype = exporter.histogramSnapshot(latency)
        // The sketch's index is the OTLP index plus one, so OTLP offset 10 is sketch bucket 11.
        val peer = prototype.piece(prototype.addBuckets(ReplicaId("B"), mapOf(11 to Long.MAX_VALUE - 1L), emptyMap(), 0L).delta)
        assertEquals(MetricWriteResult.Success, exporter.mergeHistogram(latency, peer))
        assertEquals(Long.MAX_VALUE - 1L, exporter.histogramSnapshot(latency).count, "the rig: the merged total is one short")

        assertRefusedWhole(store, exporter, listOf(positive(scale, offset = 10, 2L)))

        val control = exporter.record(listOf(positive(scale, offset = 10, 1L)))
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: one more fits") },
            { assertEquals(Long.MAX_VALUE, exporter.histogramSnapshot(latency).count) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedCount(), "and the bridge still exports it") },
        )
    }

    /**
     * Two updates to one series in one batch. The series stands at `Long.MAX_VALUE - 1`, and each
     * update adds one — to a bucket and to the zero counter — so either alone fits and only the two
     * together pass [Long.MAX_VALUE].
     */
    @Test
    fun updatesThatTipTheTotalOnlyTogetherAreRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        assertEquals(MetricExportResult.Success, exporter.record(listOf(positive(scale, offset = 10, Long.MAX_VALUE - 1L))))
        assertAll(
            { assertEquals(Long.MAX_VALUE - 1L, exporter.histogramSnapshot(latency).count, "the rig: the total is one short") },
            { assertEquals(0L, exporter.histogramSnapshot(latency).zeroCount, "the rig: the zero slot is empty") },
        )
        val bucket = positive(scale, offset = 20, 1L)
        val zero = positive(scale, offset = 0, zeroCount = 1L)

        assertRefusedWhole(store, exporter, listOf(bucket, zero))

        val control = exporter.record(listOf(zero))
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: either one alone fits") },
            { assertEquals(Long.MAX_VALUE, exporter.histogramSnapshot(latency).count) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedCount(), "and the bridge still exports it") },
        )
    }

    /**
     * One update to an empty series whose own counts, in two neighbouring buckets that do not fold
     * together, add up past [Long.MAX_VALUE]. Each bucket's slot fits; the total does not.
     */
    @Test
    fun oneUpdateWhoseOwnBucketsAddUpPastLongMaxValueIsRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        val scale = exporter.histogramScale
        assertNull(exporter.renderedCount(), "the rig: the series is empty")

        assertRefusedWhole(store, exporter, listOf(positive(scale, offset = 10, Long.MAX_VALUE, 1L)))
        // Three full buckets: a total summed in a Long wraps twice, to Long.MAX_VALUE - 2, which a
        // sign check would wave through.
        assertRefusedWhole(store, exporter, listOf(positive(scale, offset = 10, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)))

        val control = exporter.record(listOf(positive(scale, offset = 10, Long.MAX_VALUE - 1L, 1L)))
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: one less fits") },
            { assertEquals(mapOf(11 to Long.MAX_VALUE - 1L, 12 to 1L), exporter.histogramSnapshot(latency).positiveBuckets) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedCount(), "and the bridge still exports it") },
        )
    }
}
