package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a span read the store **refused** never lets a later write overwrite the durable spans
 * it could not see (#3390). Before the fix a refused read started the exporter fresh, and its next
 * write replaced the stored set with the live one, so a restart lost every span written before it.
 *
 * The rule is fail closed. Every write turn makes the read first, until one lands. While it is
 * refused the turn writes nothing and returns [ExportResult.Failure], and the live adds stay in
 * memory. The read that lands joins the stored set with the live one, evicts to the cap, and the
 * write carries both. A *decode* failure still starts fresh.
 *
 * Every arm asserts its rig fired, so a store that never refused cannot pass it by absence.
 */
class WarpSpanExporterRefusedRecoveryTest {

    private val replica = ReplicaId("a")
    private val spansKey = StoreKey("otel.spans")

    private fun span(id: Byte) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { id }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    private fun ids(vararg id: Byte): Set<ByteString> = id.map { span(it).spanId }.toSet()

    /** What a previous process left in [store]: the spans [ids], written through an exporter. */
    private suspend fun durableSpans(store: DurableStore, vararg ids: Byte, maxSpans: Int = DEFAULT_MAX_SPANS) {
        val previous = WarpSpanExporter(replica, store, maxSpans = maxSpans).also { it.recover() }
        ids.forEach { assertEquals(ExportResult.Success, previous.export(span(it)), "the rig: span $it was not saved") }
    }

    /** The span ids a restart over [store] recovers. */
    private suspend fun idsAfterRestart(store: DurableStore): Set<ByteString> =
        WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements.map { it.spanId }.toSet()

    private fun WarpSpanExporter.liveIds(): Set<ByteString> = snapshot().elements.map { it.spanId }.toSet()

    // ---- Fail closed ----

    @Test
    fun anExportAfterARefusedReadLeavesTheDurableSpansIntact() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val refusedRecovery = store.refusals
        val result = restarted.export(span(3))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusedRecovery, "the rig did not refuse the recovery read") },
            { assertTrue(store.refusals > refusedRecovery, "the export did not retry the refused read") },
            { assertIs<ExportResult.Failure>(result, "an export over an unread store reported $result") },
            { assertEquals(ids(1, 2), durable, "the export overwrote the durable spans it never read") },
            { assertEquals(ids(3), restarted.liveIds(), "the refused export dropped its live span") },
        )
    }

    @Test
    fun theReadThatLandsKeepsBothTheDurableAndTheLiveSpans() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        restarted.export(span(3))
        val refusals = store.refusals
        store.heal()
        val healed = restarted.export(span(4))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertEquals(2, refusals, "the rig did not refuse the recovery read and the export's retry") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(ids(1, 2, 3, 4), restarted.liveIds(), "the healed exporter lost a span from memory") },
            { assertEquals(ids(1, 2, 3, 4), durable, "the healed write lost a span") },
        )
    }

    @Test
    fun aCancellationTheStoreMintsOnTheRecoveryReadIsHealedByTheNextExport() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == spansKey }

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val result = restarted.export(span(3))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the recovery read") },
            { assertTrue(restarted.health.value.recoveryFailed, "the refused read was not reported") },
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(ids(1, 2, 3), durable, "the export overwrote the spans the refused read missed") },
        )
    }

    /** The same holds for an exporter nobody recovered: its first write turn reads first. */
    @Test
    fun anExporterThatWasNeverRecoveredReadsBeforeItsFirstWrite() = runTest {
        val store = InMemoryDurableStore()
        durableSpans(store, 1, 2)

        val result = WarpSpanExporter(replica, store).export(span(3))
        val durable = idsAfterRestart(store)

        assertAll(
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(ids(1, 2, 3), durable, "an unrecovered exporter overwrote the stored spans") },
        )
    }

    @Test
    fun aMergeAfterARefusedReadWritesNothing() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)
        val remote = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore()).also { it.recover(); it.export(span(9)) }.snapshot()

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val result = restarted.merge(remote)
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertTrue(store.refusals >= 2, "the rig did not refuse the recovery read and the merge's retry") },
            { assertIs<ExportResult.Failure>(result, "a merge over an unread store reported $result") },
            { assertEquals(ids(1, 2), durable, "the merge overwrote the durable spans it never read") },
        )
    }

    @Test
    fun aClearWhileTheReadIsRefusedChangesNothingAndTheHealedClearEmptiesTheStore() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        restarted.export(span(3))
        val refused = restarted.clear()
        val durableAfterRefused = idsAfterRestart(backing)
        val liveAfterRefused = restarted.liveIds()
        store.heal()
        val healed = restarted.clear()
        val durableAfterHealed = idsAfterRestart(backing)

        assertAll(
            { assertTrue(store.refusals >= 3, "the rig did not refuse the reads before the clear") },
            { assertIs<ExportResult.Failure>(refused, "a clear over an unread store reported $refused") },
            { assertEquals(ids(1, 2), durableAfterRefused, "the refused clear overwrote the durable spans") },
            { assertEquals(ids(3), liveAfterRefused, "the refused clear dropped the live spans") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(emptySet(), durableAfterHealed, "the healed clear left a durable span behind") },
        )
    }

    // ---- The join respects the cap ----

    @Test
    fun theHealingJoinEvictsToMaxSpans() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2, 3, maxSpans = 4)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 4).also { it.recover() }
        restarted.export(span(4))
        restarted.export(span(5))
        store.heal()
        val healed = restarted.export(span(6))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertTrue(store.refusals >= 3, "the rig did not refuse the reads before healing") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(ids(3, 4, 5, 6), restarted.liveIds(), "the join kept more than the cap, or the wrong spans") },
            { assertEquals(ids(3, 4, 5, 6), durable, "the healed write kept more than the cap, or lost a span") },
        )
    }

    // ---- Cancellation ----

    @Test
    fun theCallersOwnCancellationInTheRetriedReadStillPropagates() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == spansKey }
        val restarted = WarpSpanExporter(replica, store).also { it.recover() }

        store.arm(StoreCall.READ, StoreAct.PARK) { it == spansKey }
        val returned = cancelInside(store) { restarted.export(span(3)) }
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertEquals(1, store.minted, "the rig never refused the recovery read") },
            { assertEquals(1, store.parked, "the rig never parked the export's retried read") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the retried read") },
            { assertNull(returned, "the cancelled export returned instead of being cancelled: $returned") },
            { assertEquals(ids(1, 2), durable, "the cancelled export wrote anyway") },
        )
    }

    // ---- A corrupt entry still starts fresh ----

    @Test
    fun aCorruptEntryStillStartsTheSpansFresh() = runTest {
        val store = InMemoryDurableStore()
        store.write(spansKey, byteArrayOf(0x7f, 0x00, 0x13))

        val exporter = WarpSpanExporter(replica, store).also { it.recover() }
        val result = exporter.export(span(1))
        val durable = idsAfterRestart(store)

        assertAll(
            { assertTrue(exporter.health.value.recoveryFailed, "the rig: the corrupt entry was not reported") },
            { assertEquals(ExportResult.Success, result, "an exporter over a corrupt entry is dead instead of fresh") },
            { assertEquals(ids(1), durable, "the corrupt entry was not replaced") },
        )
    }
}
