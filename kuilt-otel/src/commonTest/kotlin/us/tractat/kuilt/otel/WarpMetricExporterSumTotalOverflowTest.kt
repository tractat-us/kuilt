package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A sum increment that would carry its series' **total** past [Long.MAX_VALUE] is refused before
 * anything in its batch is applied (#3445). The total is what the OTLP bridge exports as the point's
 * value: [GCounter.valueOrNull], the sum over every replica's slot. A series past it is one the
 * bridge skips, so the exporter must not accept it.
 *
 * Before the fix [WarpMetricExporter.record] checked only this replica's slot (#3318), so with a
 * peer's counter merged in, an increment that fit this replica's slot was accepted even when it took
 * the total past [Long.MAX_VALUE].
 *
 * Every rig puts its series a known distance below [Long.MAX_VALUE], with this replica's slot
 * empty, and asserts it did; every refusal has a control one short that lands and that the bridge
 * still renders.
 */
class WarpMetricExporterSumTotalOverflowTest {

    private val replica = ReplicaId("A")
    private val requests = MetricKey("requests", MetricKind.SUM)
    private val other = MetricKey("other", MetricKind.SUM)

    private fun exporter(store: RecordingStore) = WarpMetricExporter(replica = replica, store = store)

    private fun RecordingStore.writes(): Int = operations().count { it.kind == StoreOpKind.WRITE }

    /** The value the bridge would export for [requests] right now, or `null` if it would skip the series. */
    private fun WarpMetricExporter.renderedSum(): Long? =
        renderMetricPoints(snapshotAll(), nowEpochNanos = 1L, FirstSightings(), FirstSightings())
            .filterIsInstance<MetricPoint.Sum>()
            .singleOrNull { it.key == requests }
            ?.value

    /** Merge a peer's counter for [requests] holding [slots], and assert this replica's own slot is still empty. */
    private suspend fun WarpMetricExporter.mergePeers(vararg slots: Pair<ReplicaId, Long>) {
        assertEquals(MetricWriteResult.Success, mergeSum(requests, GCounter.of(*slots)))
        assertEquals(0L, sumSnapshot(requests).count(replica), "the rig: this replica's slot is empty")
    }

    /**
     * Asserts [record] refused [batch] whole, naming [requests]: nothing applied in memory, nothing
     * written, and a sum on another series riding in the same batch was not counted either.
     */
    private suspend fun assertRefusedWhole(
        store: RecordingStore,
        exporter: WarpMetricExporter,
        batch: List<MetricUpdate>,
    ): MetricSumOverflowException {
        val memoryBefore = exporter.snapshotAll()
        val writesBefore = store.writes()

        val result = exporter.record(listOf(MetricUpdate.IncrementSum(other, 1L)) + batch)

        val cause = assertIs<MetricSumOverflowException>(assertIs<MetricExportResult.Refused>(result).overflow)
        assertAll(
            { assertEquals(requests, cause.key, "the refusal names the series") },
            { assertEquals(memoryBefore, exporter.snapshotAll(), "nothing applied in memory") },
            { assertEquals(writesBefore, store.writes(), "nothing written") },
            { assertEquals(0L, exporter.sumValue(other), "the sum beside it was not counted") },
        )
        return cause
    }

    /**
     * A peer's slot counts towards the total the bridge exports. A peer's counter merged in holds
     * `Long.MAX_VALUE - 1`; this replica's own slot is empty, so two more fit it but not the total.
     */
    @Test
    fun anIncrementThatTipsAMergedTotalPastLongMaxValueIsRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        exporter.mergePeers(ReplicaId("B") to Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE - 1L, exporter.sumSnapshot(requests).valueOrNull(), "the rig: the merged total is one short")

        assertRefusedWhole(store, exporter, listOf(MetricUpdate.IncrementSum(requests, 2L)))

        val control = exporter.incrementSum(requests, 1L)
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: one more lands the total on Long.MAX_VALUE") },
            { assertEquals(Long.MAX_VALUE, exporter.sumValue(requests)) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedSum(), "and the bridge still exports it") },
        )
    }

    /**
     * Two increments to one series in one batch. The merged total stands at `Long.MAX_VALUE - 2`,
     * so one or two alone fits and only the two together pass [Long.MAX_VALUE].
     */
    @Test
    fun incrementsThatTipAMergedTotalOnlyTogetherAreRefused() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        exporter.mergePeers(ReplicaId("B") to Long.MAX_VALUE - 2L)
        assertEquals(Long.MAX_VALUE - 2L, exporter.sumSnapshot(requests).valueOrNull(), "the rig: the merged total is two short")

        assertRefusedWhole(
            store,
            exporter,
            listOf(MetricUpdate.IncrementSum(requests, 1L), MetricUpdate.IncrementSum(requests, 2L)),
        )

        val control = exporter.record(listOf(MetricUpdate.IncrementSum(requests, 2L)))
        assertAll(
            { assertEquals(MetricExportResult.Success, control, "the control: either one alone fits") },
            { assertEquals(Long.MAX_VALUE, exporter.sumValue(requests)) },
            { assertEquals(Long.MAX_VALUE, exporter.renderedSum(), "and the bridge still exports it") },
        )
    }

    /**
     * A merge whose join carries the total past [Long.MAX_VALUE] is kept: it is a lattice join. The
     * bridge then skips the series, and every later increment to it is refused. Three full peer
     * slots: summed in a wrapping `Long` they come to `Long.MAX_VALUE - 2`, so a total read that way
     * would accept one more, and a sign check would not catch it.
     */
    @Test
    fun aMergedTotalAlreadyPastLongMaxValueRefusesEveryIncrement() = runTest {
        val store = RecordingStore()
        val exporter = exporter(store)
        exporter.mergePeers(
            ReplicaId("B") to Long.MAX_VALUE,
            ReplicaId("C") to Long.MAX_VALUE,
            ReplicaId("D") to Long.MAX_VALUE,
        )
        assertAll(
            { assertNull(exporter.sumSnapshot(requests).valueOrNull(), "the rig: the merge kept a total past Long") },
            { assertNull(exporter.renderedSum(), "the rig: the bridge skips the series") },
        )

        assertRefusedWhole(store, exporter, listOf(MetricUpdate.IncrementSum(requests, 1L)))
    }
}
