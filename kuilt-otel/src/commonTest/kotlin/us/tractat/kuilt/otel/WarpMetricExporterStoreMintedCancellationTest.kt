package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a `CancellationException` a [DurableStore] **mints** reads as a failed store call to
 * [WarpMetricExporter], while the caller's **own** cancellation still propagates (#3388).
 *
 * `runCatchingCancellable` rethrows every `CancellationException`, so each store call it guarded let
 * a store's own `withTimeout` escape: every mutator threw where it returns
 * [MetricExportResult.Failure], [WarpMetricExporter.clear] threw although it never throws, and
 * [WarpMetricExporter.recover] threw out of one kind's read and left the kinds after it unread.
 * Every mint arm has a control in which the caller really is cancelled inside the same call.
 */
class WarpMetricExporterStoreMintedCancellationTest {

    private val replica = ReplicaId("a")
    private val sum = MetricKey("sum", MetricKind.SUM)
    private val doubleSum = MetricKey("double", MetricKind.SUM)
    private val gauge = MetricKey("gauge", MetricKind.GAUGE)
    private val cardinality = MetricKey("cardinality", MetricKind.CARDINALITY)
    private val histogram = MetricKey("histogram", MetricKind.EXPONENTIAL_HISTOGRAM)

    private val sumsKey = StoreKey("otel.metrics.sums")

    /** Each persisted key, with how many series of its kind a [MetricCatalog] holds. */
    private val kinds: List<Pair<StoreKey, (MetricCatalog) -> Int>> = listOf(
        sumsKey to { it.sums.size },
        StoreKey("otel.metrics.sums.double") to { it.doubleSums.size },
        StoreKey("otel.metrics.gauges") to { it.gauges.size },
        StoreKey("otel.metrics.cardinalities") to { it.cardinalities.size },
        StoreKey("otel.metrics.histograms") to { it.histograms.size },
    )

    /** A store holding one series of every kind. */
    private suspend fun seeded(): RecordingStore {
        val backing = RecordingStore()
        WarpMetricExporter(replica, backing).apply {
            incrementSum(sum, 3L)
            incrementSumDouble(doubleSum, 2.5)
            setGauge(gauge, 4.0, 1L)
            addCardinality(cardinality, "one")
            recordHistogram(histogram, 5.0)
        }
        return backing
    }

    private suspend fun sumAfterRestart(store: DurableStore): Long =
        WarpMetricExporter(replica, store).also { it.recover() }.sumValue(sum)

    // ---- Recovery ----

    @Test
    fun aCancellationTheStoreMintsOnOneKindsReadCostsThatKindAlone() = runTest {
        val backing = seeded()
        val outcomes = kinds.map { (key, seriesOf) ->
            val store = CancellationScriptedStore(backing)
            store.arm(StoreCall.READ, StoreAct.MINT) { it == key }
            val exporter = WarpMetricExporter(replica, store)
            val outcome = escapeOf { exporter.recover() }
            RecoveryOutcome(
                key = key,
                minted = store.minted,
                escaped = outcome.escaped,
                recoveryFailed = exporter.health.value.recoveryFailed,
                unreadSeries = seriesOf(exporter.snapshotAll()),
                total = exporter.metricCount(),
            )
        }

        assertAll(
            *outcomes.flatMap { o ->
                val name = o.key.name
                listOf(
                    { assertEquals(1, o.minted, "the rig minted ${o.minted} cancellations on the read of $name") },
                    { assertNull(o.escaped, "a cancellation minted on $name escaped recover: ${o.escaped}") },
                    { assertTrue(o.recoveryFailed, "an unread $name was not reported as a recovery failure") },
                    { assertEquals(0, o.unreadSeries, "the unread $name should start fresh") },
                    { assertEquals(kinds.size - 1, o.total, "a minted read of $name cost another kind") },
                )
            }.toTypedArray(),
        )
    }

    private class RecoveryOutcome(
        val key: StoreKey,
        val minted: Int,
        val escaped: Throwable?,
        val recoveryFailed: Boolean,
        val unreadSeries: Int,
        val total: Int,
    )

    @Test
    fun theCallersOwnCancellationInEachKindsReadStillPropagates() = runTest {
        val backing = seeded()
        val outcomes = kinds.map { (key, _) ->
            val store = CancellationScriptedStore(backing)
            store.arm(StoreCall.READ, StoreAct.PARK) { it == key }
            val returned = cancelInside(store) { WarpMetricExporter(replica, store).recover() }
            Triple(key, store, returned)
        }

        assertAll(
            *outcomes.flatMap { (key, store, returned) ->
                listOf(
                    { assertEquals(1, store.parked, "the rig never parked the read of ${key.name}") },
                    { assertEquals(1, store.cancelled, "the cancellation never landed inside the read of ${key.name}") },
                    { assertNull(returned, "recover cancelled in its read of ${key.name} returned instead of being cancelled") },
                )
            }.toTypedArray(),
        )
    }

    // ---- The durable write every mutator funnels through ----

    @Test
    fun aCancellationTheStoreMintsOnAWriteFailsTheCallAndTheNextWriteRepairsIt() = runTest {
        val backing = RecordingStore()
        val store = CancellationScriptedStore(backing)
        val exporter = WarpMetricExporter(replica, store)

        store.arm(StoreCall.WRITE, StoreAct.MINT) { it == sumsKey }
        val minted = escapeOf { exporter.incrementSum(sum, 3L) }
        val failedAfter = exporter.health.value.failed
        val durableBeforeRepair = sumAfterRestart(backing)
        val repaired = exporter.incrementSum(sum, 4L)

        assertAll(
            { assertEquals(1, store.minted, "the rig minted ${store.minted} cancellations on the write, not one") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped incrementSum: ${minted.escaped}") },
            { assertIs<MetricExportResult.Failure>(minted.returned, "the minted write reported ${minted.returned}") },
            { assertEquals(1L, failedAfter, "the minted write was not counted as a refused write") },
            { assertEquals(0L, durableBeforeRepair, "the minted write landed anyway, so nothing was left to repair") },
            { assertEquals(MetricExportResult.Success, repaired) },
            { assertEquals(7L, sumAfterRestart(backing), "the next write did not carry the refused increment") },
        )
    }

    @Test
    fun theCallersOwnCancellationInAWriteStillPropagates() = runTest {
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpMetricExporter(replica, store)
        store.arm(StoreCall.WRITE, StoreAct.PARK) { it == sumsKey }

        val returned = cancelInside(store) { exporter.incrementSum(sum, 3L) }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the write") },
            { assertNull(returned, "the cancelled incrementSum returned instead of being cancelled: $returned") },
        )
    }

    // ---- Clear ----

    @Test
    fun aCancellationTheStoreMintsOnAClearDeleteFailsTheClear() = runTest {
        val store = CancellationScriptedStore(seeded())
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }

        store.arm(StoreCall.DELETE, StoreAct.MINT) { it == sumsKey }
        val minted = escapeOf { exporter.clear() }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on a clear delete") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped clear: ${minted.escaped}") },
            { assertIs<MetricExportResult.Failure>(minted.returned, "the minted delete reported ${minted.returned}") },
            { assertEquals(1L, exporter.health.value.failed, "the minted delete was not counted as a refused turn") },
            { assertEquals(0, exporter.metricCount(), "clear forgets in memory whatever the store does") },
        )
    }

    @Test
    fun theCallersOwnCancellationInAClearDeleteStillPropagates() = runTest {
        val store = CancellationScriptedStore(seeded())
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        store.arm(StoreCall.DELETE, StoreAct.PARK) { it == sumsKey }

        val returned = cancelInside(store) { exporter.clear() }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the clear delete") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the clear delete") },
            { assertNull(returned, "the cancelled clear returned instead of being cancelled: $returned") },
        )
    }
}
