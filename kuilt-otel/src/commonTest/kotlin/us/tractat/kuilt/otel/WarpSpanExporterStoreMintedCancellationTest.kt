package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
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
 * [WarpSpanExporter], while the caller's **own** cancellation still propagates (#3388).
 *
 * `runCatchingCancellable` rethrows every `CancellationException`, so each store call it guarded let
 * a store's own `withTimeout` escape: [WarpSpanExporter.export], [WarpSpanExporter.merge] and
 * [WarpSpanExporter.clear] threw where they return [ExportResult.Failure], and
 * [WarpSpanExporter.recover] threw where it records a recovery failure and starts fresh. Every mint
 * arm has a control in which the caller really is cancelled inside the same call.
 */
class WarpSpanExporterStoreMintedCancellationTest {

    private val replica = ReplicaId("a")
    private val spansKey = StoreKey("otel.spans")
    private val isSpans: (StoreKey) -> Boolean = { it == spansKey }
    private val isClock: (StoreKey) -> Boolean = { it == WarpCausalClock.STORE_KEY }

    private fun span(id: Byte) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { id }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    private suspend fun idsAfterRestart(store: DurableStore): Set<ByteString> =
        WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements.map { it.spanId }.toSet()

    // ---- Recovery ----

    @Test
    fun aCancellationTheStoreMintsOnTheRecoveryReadDoesNotEscapeRecover() = runTest {
        val backing = RecordingStore()
        WarpSpanExporter(replica, backing).export(span(1))
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT, isSpans)

        val exporter = WarpSpanExporter(replica, store)
        val outcome = escapeOf { exporter.recover() }

        assertAll(
            { assertEquals(1, store.minted, "the rig minted ${store.minted} cancellations on the read, not one") },
            { assertNull(outcome.escaped, "a cancellation the store minted escaped recover: ${outcome.escaped}") },
            { assertTrue(exporter.health.value.recoveryFailed, "an unread store was not reported as a recovery failure") },
            { assertEquals(emptySet(), exporter.snapshot().elements, "an unread store should start fresh") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheRecoveryReadStillPropagates() = runTest {
        val backing = RecordingStore()
        WarpSpanExporter(replica, backing).export(span(1))
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.PARK, isSpans)

        val returned = cancelInside(store) { WarpSpanExporter(replica, store).recover() }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the recovery read") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the recovery read") },
            { assertNull(returned, "recover cancelled in its read returned instead of being cancelled") },
        )
    }

    // ---- The causal clock's recovery, which the span path stamps from ----

    @Test
    fun aCancellationTheStoreMintsOnTheClockReadDoesNotEscapeItsRecovery() = runTest {
        val backing = RecordingStore()
        WarpCausalClock(replica).armed().also { it.mint() }.persist(backing)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT, isClock)

        val clock = WarpCausalClock(replica)
        val outcome = escapeOf { clock.recover(store) }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the clock read") },
            { assertNull(outcome.escaped, "a cancellation the store minted escaped the clock's recovery: ${outcome.escaped}") },
            { assertIs<ClockRecovery.Refused>(outcome.returned, "the unread clock was reported as ${outcome.returned}") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheClockReadStillPropagates() = runTest {
        val backing = RecordingStore()
        WarpCausalClock(replica).armed().also { it.mint() }.persist(backing)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.PARK, isClock)

        val returned = cancelInside(store) { WarpCausalClock(replica).recover(store) }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the clock read") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the clock read") },
            { assertNull(returned, "the clock's recovery cancelled in its read returned instead of being cancelled") },
        )
    }

    // ---- Export ----

    /**
     * The refused export publishes nothing (#3517), so the next export's write does not carry its span:
     * the caller's retry is its only copy, as it is for a stamped span.
     */
    @Test
    fun aCancellationTheStoreMintsOnTheExportWriteFailsTheExportAndPublishesNothing() = runTest {
        val backing = RecordingStore()
        val store = CancellationScriptedStore(backing)
        val exporter = WarpSpanExporter(replica, store)

        store.arm(StoreCall.WRITE, StoreAct.MINT, isSpans)
        val minted = escapeOf { exporter.export(span(1)) }
        val failedAfter = exporter.health.value.failed
        val liveAfterFailure = exporter.snapshot().elements.map { it.spanId }.toSet()
        val durableBeforeNext = idsAfterRestart(backing)
        val next = exporter.export(span(2))
        val durableAfterNext = idsAfterRestart(backing)

        assertAll(
            { assertEquals(1, store.minted, "the rig minted ${store.minted} cancellations on the write, not one") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped export: ${minted.escaped}") },
            { assertIs<ExportResult.Failure>(minted.returned, "the minted write reported ${minted.returned}") },
            { assertEquals(1L, failedAfter, "the minted write was not counted as a refused write") },
            { assertEquals(emptySet(), durableBeforeNext, "the rig: the minted write landed anyway") },
            { assertEquals(emptySet(), liveAfterFailure, "the refused export published its span") },
            { assertEquals(ExportResult.Success, next) },
            { assertEquals(setOf(span(2).spanId), durableAfterNext, "the next export carried the refused span") },
        )
    }

    @Test
    fun aCancellationTheStoreMintsOnTheClockPersistFailsTheExportAndPublishesNothing() = runTest {
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))

        store.arm(StoreCall.WRITE, StoreAct.MINT, isClock)
        val minted = escapeOf { exporter.export(span(1)) }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the clock persist") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped export: ${minted.escaped}") },
            { assertIs<ExportResult.Failure>(minted.returned, "the minted clock persist reported ${minted.returned}") },
            { assertEquals(emptySet(), exporter.snapshot().elements, "the refused export kept its minted stamp") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheExportWriteStillPropagates() = runTest {
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store)
        store.arm(StoreCall.WRITE, StoreAct.PARK, isSpans)

        val returned = cancelInside(store) { exporter.export(span(1)) }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the export write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the export write") },
            { assertNull(returned, "the cancelled export returned instead of being cancelled: $returned") },
        )
    }

    // ---- Merge ----

    @Test
    fun aCancellationTheStoreMintsOnTheMergeWriteFailsTheMerge() = runTest {
        val remote = WarpSpanExporter(ReplicaId("b"), RecordingStore()).also { it.export(span(1)) }.snapshot()
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store)

        store.arm(StoreCall.WRITE, StoreAct.MINT, isSpans)
        val minted = escapeOf { exporter.merge(remote) }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the merge write") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped merge: ${minted.escaped}") },
            { assertIs<ExportResult.Failure>(minted.returned, "the minted merge write reported ${minted.returned}") },
            { assertEquals(1L, exporter.health.value.failed, "the minted write was not counted as a refused write") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheMergeWriteStillPropagates() = runTest {
        val remote = WarpSpanExporter(ReplicaId("b"), RecordingStore()).also { it.export(span(1)) }.snapshot()
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store)
        store.arm(StoreCall.WRITE, StoreAct.PARK, isSpans)

        val returned = cancelInside(store) { exporter.merge(remote) }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the merge write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the merge write") },
            { assertNull(returned, "the cancelled merge returned instead of being cancelled: $returned") },
        )
    }

    // ---- Clear ----

    @Test
    fun aCancellationTheStoreMintsOnTheClearWriteFailsTheClear() = runTest {
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store).also { it.export(span(1)) }

        store.arm(StoreCall.WRITE, StoreAct.MINT, isSpans)
        val minted = escapeOf { exporter.clear() }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the clear write") },
            { assertNull(minted.escaped, "a cancellation the store minted escaped clear: ${minted.escaped}") },
            { assertIs<ExportResult.Failure>(minted.returned, "the minted clear write reported ${minted.returned}") },
            { assertEquals(1L, exporter.health.value.failed, "the minted write was not counted as a refused write") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheClearWriteStillPropagates() = runTest {
        val store = CancellationScriptedStore(RecordingStore())
        val exporter = WarpSpanExporter(replica, store).also { it.export(span(1)) }
        store.arm(StoreCall.WRITE, StoreAct.PARK, isSpans)

        val returned = cancelInside(store) { exporter.clear() }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the clear write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the clear write") },
            { assertNull(returned, "the cancelled clear returned instead of being cancelled: $returned") },
        )
    }
}
