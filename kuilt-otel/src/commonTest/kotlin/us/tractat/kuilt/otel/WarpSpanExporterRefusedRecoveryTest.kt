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
 * refused the turn returns [ExportResult.Failure] and changes nothing: a refused export adds its span
 * nowhere, and the caller's retry is the only copy. The read that lands joins the stored set with
 * memory (remote merges made meanwhile), evicts any excess, and the turn then takes the live path.
 * So once the store heals, the exporter holds exactly what one that never had an outage would after
 * the same successful calls. A *decode* failure still starts fresh.
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

    /** A remote replica's set holding the spans [ids]. */
    private suspend fun remoteWith(vararg ids: Byte) =
        WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore()).also { remote ->
            remote.recover()
            ids.forEach { remote.export(span(it)) }
        }.snapshot()

    /**
     * What an exporter that never had an outage holds: [stored] left by a previous process, then
     * `recover`, then [exports] — the calls the outage run saw succeed.
     */
    private suspend fun idsWithoutAnOutage(
        stored: ByteArray,
        maxSpans: Int,
        policy: BufferPolicy,
        vararg exports: Byte,
    ): Set<ByteString> {
        val store = InMemoryDurableStore()
        durableSpans(store, *stored)
        val exporter = WarpSpanExporter(replica, store, maxSpans = maxSpans, bufferPolicy = policy).also { it.recover() }
        exports.forEach { assertEquals(ExportResult.Success, exporter.export(span(it)), "the control: span $it was not saved") }
        return idsAfterRestart(store)
    }

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
        )
    }

    @Test
    fun theReadThatLandsKeepsTheDurableSpansAndARefusedExportLeavesNothingBehind() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val refused = restarted.export(span(3))
        val liveWhileRefused = restarted.liveIds()
        val refusals = store.refusals
        store.heal()
        val healed = restarted.export(span(4))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertEquals(2, refusals, "the rig did not refuse the recovery read and the export's retry") },
            { assertIs<ExportResult.Failure>(refused, "the rig: the export was not refused") },
            { assertEquals(emptySet(), liveWhileRefused, "the refused export added its span to memory") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(ids(1, 2, 4), restarted.liveIds(), "the healed exporter holds the wrong spans") },
            { assertEquals(ids(1, 2, 4), durable, "the healed write lost a span, or wrote a refused one") },
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
        restarted.merge(remoteWith(9))
        val refused = restarted.clear()
        val durableAfterRefused = idsAfterRestart(backing)
        store.heal()
        restarted.export(span(4))
        val durableAfterHeal = idsAfterRestart(backing)
        val healed = restarted.clear()
        val durableAfterHealed = idsAfterRestart(backing)

        assertAll(
            { assertTrue(store.refusals >= 3, "the rig did not refuse the reads before the clear") },
            { assertIs<ExportResult.Failure>(refused, "a clear over an unread store reported $refused") },
            { assertEquals(ids(1, 2), durableAfterRefused, "the refused clear overwrote the durable spans") },
            { assertEquals(ids(1, 2, 4, 9), durableAfterHeal, "the refused clear dropped a merged span") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(emptySet(), durableAfterHealed, "the healed clear left a durable span behind") },
        )
    }

    // ---- The healed turn takes the live path ----

    /**
     * The healing export's own eviction never takes its span, as a live export's never does. Under `DROP_NEWEST` at the
     * cap the live path evicts the newest *stored* span before inserting, so the store holds 1 and 4.
     * Adding the export after the join and then evicting the excess evicts 4 itself instead, writes
     * {1, 2}, and returns Success for a span that exists nowhere.
     */
    @Test
    fun underDropNewestTheHealingExportKeepsItsSpanAtTheCap() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 2, bufferPolicy = BufferPolicy.DROP_NEWEST)
        restarted.recover()
        val refused = restarted.export(span(3))
        val refusals = store.refusals
        store.heal()
        val healed = restarted.export(span(4))
        val durable = idsAfterRestart(backing)
        val control = idsWithoutAnOutage(byteArrayOf(1, 2), 2, BufferPolicy.DROP_NEWEST, 4)

        assertAll(
            { assertEquals(2, refusals, "the rig did not refuse the recovery read and the export's retry") },
            { assertIs<ExportResult.Failure>(refused, "the rig: the export was not refused") },
            { assertEquals(ids(1, 4), control, "the control: the live path's own eviction took the incoming span") },
            { assertEquals(ExportResult.Success, healed) },
            { assertTrue(span(4).spanId in durable, "Success for span 4, which is not durable: $durable") },
            { assertEquals(control, durable, "the healed store differs from one that never had an outage") },
            { assertEquals(control, restarted.liveIds(), "memory differs from one that never had an outage") },
        )
    }

    /**
     * The same under `DROP_OLDEST`, for a healing span that started before every stored one, as a
     * long-lived parent span does. The live path's eviction takes the oldest stored span, not the parent.
     */
    @Test
    fun underDropOldestAHealingSpanThatStartedFirstIsKeptAtTheCap() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 2, 3)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        restarted.recover()
        val refused = restarted.export(span(4))
        val refusals = store.refusals
        store.heal()
        val healed = restarted.export(span(1))
        val durable = idsAfterRestart(backing)
        val control = idsWithoutAnOutage(byteArrayOf(2, 3), 2, BufferPolicy.DROP_OLDEST, 1)

        assertAll(
            { assertEquals(2, refusals, "the rig did not refuse the recovery read and the export's retry") },
            { assertIs<ExportResult.Failure>(refused, "the rig: the export was not refused") },
            { assertEquals(ids(1, 3), control, "the control: the live path's own eviction took the incoming span") },
            { assertEquals(ExportResult.Success, healed) },
            { assertTrue(span(1).spanId in durable, "Success for span 1, which is not durable: $durable") },
            { assertEquals(control, durable, "the healed store differs from one that never had an outage") },
            { assertEquals(control, restarted.liveIds(), "memory differs from one that never had an outage") },
        )
    }

    /**
     * A join that brings in more than the cap, here a store a previous process wrote under a larger
     * one, is evicted to the cap before the healing export's own live eviction, as `recover` does.
     */
    @Test
    fun theHealingJoinEvictsItsExcessBeforeTheExport() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2, 3, 4)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 2).also { it.recover() }
        store.heal()
        val healed = restarted.export(span(5))
        val durable = idsAfterRestart(backing)
        val control = idsWithoutAnOutage(byteArrayOf(1, 2, 3, 4), 2, BufferPolicy.DROP_OLDEST, 5)

        assertAll(
            { assertEquals(1, store.refusals, "the rig did not refuse the recovery read") },
            { assertEquals(ids(4, 5), control, "the control: recover did not evict to the cap") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(control, restarted.liveIds(), "the join kept more than the cap, or the wrong spans") },
            { assertEquals(control, durable, "the healed write kept more than the cap, or lost a span") },
        )
    }

    /**
     * The landing join's excess is evicted by the policy's own end. Under `DROP_NEWEST` the join of
     * {1, 2, 3, 4} at a cap of 2 keeps {1, 2}, and the healing export of 5 then evicts 2, as the live
     * path does. Adding 5 first and evicting the excess after would keep {1, 2}; evicting the join's
     * excess from the oldest end would keep {3, 5}.
     */
    @Test
    fun underDropNewestTheHealingJoinEvictsItsNewestExcess() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2, 3, 4)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 2, bufferPolicy = BufferPolicy.DROP_NEWEST)
        restarted.recover()
        store.heal()
        val healed = restarted.export(span(5))
        val durable = idsAfterRestart(backing)
        val control = idsWithoutAnOutage(byteArrayOf(1, 2, 3, 4), 2, BufferPolicy.DROP_NEWEST, 5)

        assertAll(
            { assertEquals(1, store.refusals, "the rig did not refuse the recovery read") },
            { assertEquals(ids(1, 5), control, "the control: recover did not evict the newest excess") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(ids(1, 5), restarted.liveIds(), "the join evicted the wrong end, or kept too many") },
            { assertEquals(ids(1, 5), durable, "the healed write evicted the wrong end, or lost its span") },
        )
    }

    /**
     * Under `DROP_OLDEST`, with a healing span that started before every stored one: the join of
     * {1, 2, 3, 4} at a cap of 2 keeps {3, 4}, and the export of 0 then evicts 3. Evicting the join's
     * excess from the newest end would keep {0, 2}.
     */
    @Test
    fun underDropOldestTheHealingJoinEvictsItsOldestExcess() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2, 3, 4)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpSpanExporter(replica, store, maxSpans = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        restarted.recover()
        store.heal()
        val healed = restarted.export(span(0))
        val durable = idsAfterRestart(backing)
        val control = idsWithoutAnOutage(byteArrayOf(1, 2, 3, 4), 2, BufferPolicy.DROP_OLDEST, 0)

        assertAll(
            { assertEquals(1, store.refusals, "the rig did not refuse the recovery read") },
            { assertEquals(ids(0, 4), control, "the control: recover did not evict the oldest excess") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(ids(0, 4), restarted.liveIds(), "the join evicted the wrong end, or kept too many") },
            { assertEquals(ids(0, 4), durable, "the healed write evicted the wrong end, or lost its span") },
        )
    }

    /** A remote set merged while the read is refused is real state: the landing read joins it. */
    @Test
    fun aRemoteMergeMadeWhileTheReadIsRefusedSurvivesTheLandingRead() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)
        val remote = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore()).also { it.recover(); it.export(span(9)) }.snapshot()

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val merged = restarted.merge(remote)
        store.heal()
        restarted.export(span(3))
        val durable = idsAfterRestart(backing)

        assertAll(
            { assertIs<ExportResult.Failure>(merged, "the rig: the merge was not refused") },
            { assertEquals(ids(1, 2, 3, 9), durable, "the landing read dropped the stored or the merged spans") },
        )
    }

    /**
     * A refused export adds its freshly stamped span nowhere, so the caller's retry of the same
     * unstamped span leaves one stamped copy rather than two.
     */
    @Test
    fun aRefusedExportLeavesNoStampedCopyForItsRetryToDuplicate() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        val clockRecovery = clock.recover(store)
        exporter.recover()
        val refused = exporter.export(span(3))
        store.heal()
        val retried = exporter.export(span(3))
        val copies = WarpSpanExporter(replica, backing).also { it.recover() }.snapshot().elements
            .filter { it.spanId == span(3).spanId }

        assertAll(
            { assertEquals(ClockRecovery.Recovered, clockRecovery, "the rig: the clock could not read") },
            { assertIs<ExportResult.Failure>(refused, "the rig: the export was not refused") },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(1, copies.size, "the retry left ${copies.size} stamped copies of one span") },
        )
    }

    /**
     * Retries made while both reads are refused go out unstamped, because the clock cannot read
     * either. Kept anywhere until the heal, those copies would join the stamped one the healed retry
     * mints: two values, one span id.
     */
    @Test
    fun retriesOfARefusedExportLeaveOneCopyOnceTheStoreHeals() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val spanReads = ReadRefusingStore(backing, spansKey)
        val store = ReadRefusingStore(spanReads, WarpCausalClock.STORE_KEY)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        val clockRecovery = clock.recover(store)
        exporter.recover()
        val refused = (1..2).map { exporter.export(span(3)) }
        val clockRefusals = store.refusals
        spanReads.heal()
        store.heal()
        val retried = exporter.export(span(3))
        val copies = WarpSpanExporter(replica, backing).also { it.recover() }.snapshot().elements
            .filter { it.spanId == span(3).spanId }

        assertAll(
            { assertIs<ClockRecovery.Refused>(clockRecovery, "the rig: the clock's read was not refused") },
            { assertEquals(3, clockRefusals, "the rig: the exports did not retry the clock's read") },
            { refused.forEach { assertIs<ExportResult.Failure>(it, "the rig: a retry was not refused") } },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(1, copies.size, "the retries left ${copies.size} copies of one span: $copies") },
            { assertTrue(copies.single().causalStamp != null, "the surviving copy is not the stamped one") },
        )
    }

    // ---- Health ----

    /**
     * A turn the refused read stops is a failed turn, as the log exporter counts it, and the write
     * that lands once the store heals resolves it. Otherwise an exporter refusing every turn forever
     * reports `failed = 0, outstandingFailures = 0`. The refused `recover` is a recovery failure, not
     * a turn.
     */
    @Test
    fun aRefusedTurnIsAFailedTurnAndTheHealedWriteResolvesIt() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = ReadRefusingStore(backing, spansKey)
        val remote = remoteWith(9)

        val restarted = WarpSpanExporter(replica, store).also { it.recover() }
        val afterRecover = restarted.health.value
        restarted.export(span(3))
        restarted.merge(remote)
        restarted.clear()
        val whileRefused = restarted.health.value
        val refusals = store.refusals
        store.heal()
        val healed = restarted.export(span(4))
        val afterHeal = restarted.health.value

        assertAll(
            { assertEquals(4, refusals, "the rig did not refuse the recovery read and each turn's retry") },
            { assertTrue(afterRecover.recoveryFailed, "the refused recovery was not reported") },
            { assertEquals(0L, afterRecover.failed, "the refused recovery was counted as a turn") },
            { assertEquals(3L, whileRefused.failed, "the refused export, merge and clear were not all counted") },
            { assertEquals(3L, whileRefused.outstandingFailures, "the refused turns are not outstanding") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(3L, afterHeal.failed) },
            { assertEquals(1L, afterHeal.acknowledged) },
            { assertEquals(0L, afterHeal.outstandingFailures, "the healed write did not resolve the refused turns") },
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

    /**
     * An export on an exporter nobody recovered adds its stamped span inside the write section, after
     * the landing read. Cancelled in the write, it rolls that span back out of memory, as a recovered
     * export rolls back its early add: otherwise the caller's retry of the unstamped span mints a
     * second stamped copy beside it.
     */
    @Test
    fun aCancelledFirstExportOnANeverRecoveredExporterRollsItsStampBack() = runTest {
        val backing = InMemoryDurableStore()
        durableSpans(backing, 1, 2)
        val store = CancellationScriptedStore(backing)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        val clockRecovery = clock.recover(store)

        store.arm(StoreCall.WRITE, StoreAct.PARK) { it == spansKey }
        val returned = cancelInside(store) { exporter.export(span(3)) }
        val live = exporter.snapshot().elements

        assertAll(
            { assertEquals(ClockRecovery.Recovered, clockRecovery, "the rig: the clock could not read") },
            { assertTrue(clock.frontier().isNotEmpty(), "the rig: the export minted no stamp") },
            { assertEquals(1, store.parked, "the rig never parked the export's write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the write") },
            { assertNull(returned, "the cancelled export returned instead of being cancelled: $returned") },
            { assertEquals(ids(1, 2), live.map { it.spanId }.toSet(), "the cancelled export left its stamped span in memory") },
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
