package us.tractat.kuilt.otel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #2915: characterize recovery separately from whether an operation reported success. */
class IndeterminateWriteAuditTest {
    private val replica = ReplicaId("a")
    private val span = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 1 }), null,
        "audit", SpanKind.INTERNAL, 1L, 2L,
    )

    @Test
    fun stampedSpanRollbackDoesNotUndoTheDurableWriteAndRestartRetryDuplicates() = runTest {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore(StoreKey("otel.spans"), cancel)
            val clock = WarpCausalClock(replica)
            val exporter = WarpSpanExporter(replica, store, causalClock = clock)
            if (cancel) assertFailsWith<CancellationException> { exporter.export(span) }
            else assertIs<ExportResult.Failure>(exporter.export(span))
            val live = exporter.snapshot().elements
            val recoveredClock = WarpCausalClock(replica).also { it.recover(store) }
            val recovered = WarpSpanExporter(replica, store, causalClock = recoveredClock).also { it.recover() }
            val durable = recovered.snapshot().elements
            val firstDot = requireNotNull(durable.single().causalStamp).dot
            assertIs<ExportResult.Success>(recovered.export(span))
            val retried = recovered.snapshot().elements
            assertAll(
                { assertEquals(1, store.injections, "span commit-before-failure injection must fire") },
                { assertEquals(if (cancel) 1 else 0, live.size, "only ordinary failure runs rollback") },
                { assertEquals(1, durable.size, "a Failure does not mean the span was not persisted") },
                { assertEquals(2, retried.size, "audit: retry after recovery creates another stamped copy") },
                { assertEquals(2, retried.map { it.causalStamp?.dot }.toSet().size, "recovery must not reuse the dot") },
                { assertTrue(retried.any { requireNotNull(it.causalStamp).dot.seq > firstDot.seq }) },
            )
        }
    }

    /**
     * The commoner duplicate needs neither a landed span write nor recovery: cancellation skips
     * the rollback, so the minted stamp stays in memory and a caller's retry mints a second one.
     */
    @Test
    fun cancelledExportKeepsItsMintedStampSoARetryDuplicatesWithoutRecovery() = runTest {
        val spansKey = StoreKey("otel.spans")
        val store = ParkingStore(spansKey)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
        // A caller's own timeout, abandoning the export while the span write is in flight.
        val timedOut = withTimeoutOrNull(1_000L) { exporter.export(span) }
        val landedAtTimeout = store.landed(spansKey)
        store.parking = false
        assertIs<ExportResult.Success>(exporter.export(span))
        val live = exporter.snapshot().elements
        val durable = WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements
        assertAll(
            { assertNull(timedOut, "the first export must time out") },
            { assertEquals(1, store.parked, "the first span write must be parked when cancelled") },
            { assertNull(landedAtTimeout, "no span write landed before the retry") },
            { assertEquals(2, live.size, "audit: the retry adds a second stamped copy in memory") },
            { assertEquals(setOf(span.spanId), live.map { it.spanId }.toSet()) },
            { assertEquals(2, durable.size, "the next successful write persists both copies") },
        )
    }

    @Test
    fun clockCommitBeforeFailureRecoversItsHighWaterMark() = runTest {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore(WarpCausalClock.STORE_KEY, cancel)
            val clock = WarpCausalClock(replica)
            val minted = clock.tick().dot
            assertFailsWith<Exception> { clock.persist(store) }
            val recovered = WarpCausalClock(replica).also { it.recover(store) }
            val frontier = recovered.frontier()
            val next = recovered.tick().dot
            assertAll(
                { assertEquals(1, store.injections, "clock commit-before-failure injection must fire") },
                { assertEquals(setOf(minted), frontier) },
                { assertTrue(next.seq > minted.seq, "recovered clock must not remint a landed dot") },
            )
        }
    }

    @Test
    fun metricSnapshotsRecoverAndRewriteWithoutReapplyingTheOperation() = runTest {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore(null, cancel)
            val exporter = WarpMetricExporter(replica, store)
            val sum = MetricKey("sum", MetricKind.SUM)
            val doubleSum = MetricKey("double", MetricKind.SUM)
            val gauge = MetricKey("gauge", MetricKind.GAUGE)
            val cardinality = MetricKey("cardinality", MetricKind.CARDINALITY)
            val histogram = MetricKey("histogram", MetricKind.EXPONENTIAL_HISTOGRAM)
            val writes: List<suspend () -> MetricExportResult> = listOf(
                { exporter.incrementSum(sum, 3L) },
                { exporter.incrementSumDouble(doubleSum, 2.5) },
                { exporter.setGauge(gauge, 4.0, 1L) },
                { exporter.addCardinality(cardinality, "one") },
                { exporter.recordHistogram(histogram, 5.0) },
            )
            for (write in writes) {
                store.armed = true
                if (cancel) assertFailsWith<CancellationException> { write() }
                else assertIs<MetricExportResult.Failure>(write())
            }
            val recovered = WarpMetricExporter(replica, store).also { it.recover() }
            val durable = recovered.snapshotAll()
            // Rewriting snapshots is idempotent; calling increment/record again is a NEW operation.
            exporter.mergeSum(sum, exporter.sumSnapshot(sum))
            exporter.mergeSumDouble(doubleSum, exporter.doubleSumSnapshot(doubleSum))
            exporter.mergeGauge(gauge, exporter.gaugeSnapshot(gauge))
            exporter.mergeCardinality(cardinality, exporter.cardinalitySnapshot(cardinality))
            exporter.mergeHistogram(histogram, exporter.histogramSnapshot(histogram))
            val rewritten = WarpMetricExporter(replica, store).also { it.recover() }.snapshotAll()
            assertAll(
                { assertEquals(writes.size, store.injections, "every metric kind must reach the injection") },
                { assertEquals(exporter.snapshotAll(), durable) },
                { assertEquals(durable, rewritten, "rewriting state must not reapply increments/samples") },
                { assertEquals(3L, recovered.sumValue(sum)) },
            )
        }
    }

    /** Parks writes to [target] until [parking] is cleared, so a caller can abandon one mid-write. */
    private class ParkingStore(private val target: StoreKey) : DurableStore {
        private val backing = InMemoryDurableStore()
        var parking = true
        var parked = 0
        suspend fun landed(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (parking && key == target) {
                parked++
                awaitCancellation()
            }
            backing.write(key, bytes)
        }
    }

    /** A minted cancellation exercises propagation; real caller cancellation is covered by the log fixture. */
    private class CommitThenFailStore(private val target: StoreKey?, private val cancel: Boolean) : DurableStore {
        private val backing = InMemoryDurableStore()
        var armed = true
        var injections = 0
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            backing.write(key, bytes)
            if (armed && (target == null || target == key)) {
                armed = false
                injections++
                if (cancel) throw CancellationException("committed before cancellation")
                throw Exception("committed before failure")
            }
        }
    }
}
