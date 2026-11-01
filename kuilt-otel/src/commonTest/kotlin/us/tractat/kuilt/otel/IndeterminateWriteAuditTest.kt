package us.tractat.kuilt.otel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot
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
                { assertEquals(0, live.size, "failure and cancellation both roll the minted stamp back") },
                { assertEquals(1, durable.size, "a Failure does not mean the span was not persisted") },
                { assertEquals(2, retried.size, "audit: retry after recovery creates another stamped copy") },
                { assertEquals(2, retried.map { it.causalStamp?.dot }.toSet().size, "recovery must not reuse the dot") },
                { assertTrue(retried.any { requireNotNull(it.causalStamp).dot.seq > firstDot.seq }) },
            )
        }
    }

    /**
     * Cancelled inside the write: no write landed and nothing recovered, so the caller's retry must
     * leave one stamped copy rather than the two a kept stamp used to produce.
     */
    @Test
    fun exportCancelledInsideTheWriteRollsBackSoARetryDoesNotDuplicate() = runTest {
        val spansKey = StoreKey("otel.spans")
        val store = ParkingStore(spansKey)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
        // A caller's own timeout, abandoning the export while the span write is in flight.
        val timedOut = withTimeoutOrNull(1_000L) { exporter.export(span) }
        val liveAfterCancel = exporter.snapshot().elements
        val landedAtTimeout = store.landed(spansKey)
        store.parking = false
        assertIs<ExportResult.Success>(exporter.export(span))
        val live = exporter.snapshot().elements
        val durable = WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements
        assertAll(
            { assertNull(timedOut, "the first export must time out") },
            { assertEquals(1, store.parked, "the first span write must be parked when cancelled") },
            { assertNull(landedAtTimeout, "no span write landed before the retry") },
            { assertEquals(emptySet(), liveAfterCancel, "the cancelled export loses its span from memory") },
            { assertEquals(1, live.size, "the retry must leave one stamped copy") },
            { assertEquals(setOf(span.spanId), live.map { it.spanId }.toSet()) },
            { assertEquals(1, durable.size, "the next successful write persists one copy") },
        )
    }

    /** Cancelled while queued for the write section, the case a catch around only the write misses. */
    @Test
    fun exportCancelledWhileWaitingForTheWriteRollsBackSoARetryDoesNotDuplicate() = runTest {
        val store = ParkingStore(StoreKey("otel.spans"))
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
        val holder = launch { exporter.export(otherSpan(2)) }
        testScheduler.runCurrent()
        val waiter = launch { exporter.export(span) }
        testScheduler.runCurrent()
        val idsWhileWaiting = exporter.snapshot().elements.map { it.spanId }.toSet()
        val parkedWhileWaiting = store.parked
        waiter.cancelAndJoin()
        val idsAfterCancel = exporter.snapshot().elements.map { it.spanId }.toSet()
        store.parking = false
        store.release()
        holder.join()
        assertIs<ExportResult.Success>(exporter.export(span))
        val copies = exporter.snapshot().elements.filter { it.spanId == span.spanId }
        val durable = WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements
        assertAll(
            // The rig: the stamp is in memory and the waiter never reached the write.
            { assertEquals(setOf(otherSpan(2).spanId, span.spanId), idsWhileWaiting) },
            { assertEquals(1, parkedWhileWaiting, "only the holder may be inside the write section") },
            { assertEquals(setOf(otherSpan(2).spanId), idsAfterCancel, "the holder's span must survive the rollback") },
            { assertEquals(1, copies.size, "the retry must leave one stamped copy") },
            { assertEquals(1, durable.count { it.spanId == span.spanId }, "one copy reaches the store") },
        )
    }

    /** Without a minted stamp a retry re-adds one full value, so a cancelled export has nothing to undo. */
    @Test
    fun cancelledExportKeepsASpanItDidNotStamp() = runTest {
        val explicit = span.copy(causalStamp = CausalStamp(Dot(replica, 99L), emptySet()))
        val cases = listOf(span to null, explicit to WarpCausalClock(replica))
        for ((exported, clock) in cases) {
            val store = ParkingStore(StoreKey("otel.spans"))
            val exporter = WarpSpanExporter(replica, store, causalClock = clock)
            val timedOut = withTimeoutOrNull(1_000L) { exporter.export(exported) }
            val liveAfterCancel = exporter.snapshot().elements
            store.parking = false
            assertIs<ExportResult.Success>(exporter.export(exported))
            assertAll(
                { assertNull(timedOut, "the first export must time out") },
                { assertEquals(setOf(exported), liveAfterCancel, "nothing was minted, so nothing rolls back") },
                { assertEquals(setOf(exported), exporter.snapshot().elements, "the retry is the same value") },
            )
        }
    }

    /**
     * The stamp enters memory before the write section, so a concurrent export's write can carry a
     * stamp this export then rolls back. Cancellation and failure share this: the store holds a copy
     * memory has dropped until the next write replaces the snapshot, and a restart inside that
     * window recovers it, which is the duplicate no rollback reaches.
     */
    @Test
    fun aConcurrentWriteCanPersistAStampTheExportThenRollsBack() = runTest {
        for (cancel in listOf(true, false)) {
            val store = ParkingStore(StoreKey("otel.spans"))
            val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
            val holder = launch { exporter.export(otherSpan(2)) }
            testScheduler.runCurrent()
            val carrier = launch { exporter.export(otherSpan(3)) }
            testScheduler.runCurrent()
            val exporting = launch { exporter.export(span) }
            testScheduler.runCurrent()
            // The holder lands; the carrier encodes a snapshot holding the export's stamp and parks.
            store.release()
            testScheduler.runCurrent()
            val parkedBeforeRollback = store.parked
            if (cancel) exporting.cancelAndJoin() else store.refuseTargetWriteNumber = 3
            store.release()
            holder.join()
            carrier.join()
            exporting.join()
            val live = exporter.snapshot().elements.map { it.spanId }
            val durable = WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements
            // A restart inside the window, taken before the live retry replaces the snapshot.
            val recoveredClock = WarpCausalClock(replica).also { it.recover(store) }
            val recovered = WarpSpanExporter(replica, store, causalClock = recoveredClock).also { it.recover() }

            store.parking = false
            assertIs<ExportResult.Success>(exporter.export(span))
            val healed = WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements
            assertIs<ExportResult.Success>(recovered.export(span))
            assertAll(
                { assertEquals(2, parkedBeforeRollback, "holder and carrier must both have reached the write") },
                { assertEquals(if (cancel) 0 else 1, store.refused, "only the failure arm refuses a write") },
                { assertEquals(false, span.spanId in live, "the export rolled its stamp out of memory") },
                { assertEquals(1, durable.count { it.spanId == span.spanId }, "the carrier persisted the stamp") },
                { assertEquals(1, healed.count { it.spanId == span.spanId }, "the next write replaces the copy") },
                {
                    assertEquals(
                        2,
                        recovered.snapshot().elements.count { it.spanId == span.spanId },
                        "audit: a restart inside the window recovers the copy, so its retry duplicates",
                    )
                },
            )
        }
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

    private fun otherSpan(id: Byte) = span.copy(spanId = ByteString(ByteArray(8) { id }))

    /**
     * Parks writes to [target] while [parking], each until one [release] or its caller's cancellation,
     * so a test can abandon one mid-write or hold the write section with it.
     */
    private class ParkingStore(private val target: StoreKey) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val permits = Channel<Unit>(Channel.UNLIMITED)
        private var targetWrites = 0
        var parking = true
        var parked = 0
        var refused = 0
        var refuseTargetWriteNumber: Int? = null
        fun release() {
            permits.trySend(Unit)
        }
        suspend fun landed(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key == target && ++targetWrites == refuseTargetWriteNumber) {
                refused++
                throw Exception("refused")
            }
            if (parking && key == target) {
                parked++
                permits.receive()
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
