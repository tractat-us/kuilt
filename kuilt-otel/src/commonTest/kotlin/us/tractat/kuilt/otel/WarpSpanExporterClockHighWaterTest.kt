package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins that a corrupt clock entry never re-mints a dot this replica minted, even one no stored span
 * still carries or names (#3435).
 *
 * #3407's floor comes from the dots the stored spans hold. A span evicted past `maxSpans`, or removed
 * by `clear`, takes its dot with it, so before the fix a corrupt clock entry over such a store started
 * at `seq=0` and minted those dots again. The span bytes now carry this replica's high-water mark, and
 * the floor is the larger of that and the highest own dot in the set.
 *
 * Every arm asserts its rig: the clock entry really is undecodable, and the restarted set really holds
 * no dot of this replica's at or above the high-water span's, so only the carried value can cover it.
 */
class WarpSpanExporterClockHighWaterTest {

    private val replica = ReplicaId("a")
    private val peer = ReplicaId("b")
    private val clockKey = WarpCausalClock.STORE_KEY
    private val spansKey = StoreKey("otel.spans")

    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { 1 }),
        spanId = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    /** A span stamped by the peer, so exporting it mints no dot of this replica's. */
    private fun peerSpan(id: Int) = span(id).copy(causalStamp = CausalStamp(Dot(peer, id.toLong()), emptySet()))

    /** Overwrite the clock's entry with bytes that do not decode, and prove a fresh read finds them so. */
    private suspend fun corruptTheClock(store: DurableStore): ClockRecovery {
        store.write(clockKey, byteArrayOf(0x7f, 0x00, 0x13))
        return WarpCausalClock(replica).recover(store)
    }

    /** Every `seq` of this replica's that [exporter]'s set holds, stamps and predecessors both. */
    private fun ownSeqs(exporter: WarpSpanExporter): Set<Long> = exporter.snapshot().elements
        .flatMap { span -> span.causalStamp?.let { it.predecessors + it.dot }.orEmpty() }
        .filter { it.replica == replica }
        .map { it.seq }
        .toSet()

    /** The dot [exporter] stamped span [id] with, or an [AssertionError] if it is missing or unstamped. */
    private fun dotOf(exporter: WarpSpanExporter, id: Int): Dot =
        exporter.snapshot().elements.single { it.spanId == span(id).spanId }.causalStamp?.dot
            ?: throw AssertionError("span $id was saved unstamped")

    /** A restarted process over [store]: its clock recovered first, as [WarpTelemetry.recover] does. */
    private suspend fun restart(store: DurableStore, maxSpans: Int = DEFAULT_MAX_SPANS): Restarted {
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, maxSpans = maxSpans, causalClock = clock)
        val clockRecovery = clock.recover(store)
        exporter.clockRecovered(clockRecovery)
        exporter.recover()
        return Restarted(exporter, clockRecovery)
    }

    private class Restarted(val exporter: WarpSpanExporter, val clockRecovery: ClockRecovery)

    /** Export [ids] unstamped through [exporter], asserting each lands. */
    private suspend fun exportAll(exporter: WarpSpanExporter, ids: IntRange) {
        ids.forEach { assertEquals(ExportResult.Success, exporter.export(span(it)), "the rig: span $it was not saved") }
    }

    /**
     * Five stamped spans, then two of the peer's at a cap of two: `DROP_OLDEST` evicts every span of
     * this replica's before the last span write, so the store holds none of its dots.
     */
    @Test
    fun aCorruptClockMintsAboveAStampEvictedBeforeTheLastWrite() = runTest {
        val store = InMemoryDurableStore()
        val previous = restart(store, maxSpans = 2).exporter
        exportAll(previous, 1..5)
        val stampsBefore = previous.snapshot().elements.mapNotNull { it.causalStamp?.dot?.seq }.toSet()
        listOf(6, 7).forEach { assertEquals(ExportResult.Success, previous.export(peerSpan(it)), "the rig: peer span $it was not saved") }
        val corrupt = corruptTheClock(store)

        val restarted = restart(store, maxSpans = 2)
        val heldAfterRestart = ownSeqs(restarted.exporter)
        val result = restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertEquals(setOf(4L, 5L), stampsBefore, "the rig: the cap did not hold the last two stamps") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertIs<ClockRecovery.Undecodable>(restarted.clockRecovery, "the rig: the restart decoded the clock") },
            { assertEquals(emptySet(), heldAfterRestart, "the rig: the store still holds a dot of this replica's") },
            { assertEquals(ExportResult.Success, result) },
            { assertTrue(next.seq > 5, "the corrupt clock re-minted $next, the dot of an evicted span") },
        )
    }

    /** A clear on an armed clock: the emptied set names no dot, and the clock entry is then corrupted. */
    @Test
    fun aCorruptClockMintsAboveAStampTheClearRemoved() = runTest {
        val store = InMemoryDurableStore()
        val previous = restart(store).exporter
        exportAll(previous, 1..5)
        val mintedBefore = ownSeqs(previous)
        val cleared = previous.clear()
        val corrupt = corruptTheClock(store)

        val restarted = restart(store)
        val heldAfterRestart = restarted.exporter.snapshot().elements.size
        val result = restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertEquals((1L..5L).toSet(), mintedBefore, "the rig: the spans are not stamped 1..5") },
            { assertEquals(ExportResult.Success, cleared, "the rig: the clear was not saved") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(0, heldAfterRestart, "the rig: the clear left a span in the store") },
            { assertEquals(ExportResult.Success, result) },
            { assertTrue(next.seq > 5, "the corrupt clock re-minted $next, the dot of a cleared span") },
        )
    }

    /**
     * A clear while the clock's read is refused: the clock is unarmed, so the clear writes no clock
     * entry and its span write is the only one that can carry the high-water mark the spans read.
     */
    @Test
    fun aClearWhileTheClockIsUnrecoveredKeepsTheHighWater() = runTest {
        val backing = InMemoryDurableStore()
        exportAll(restart(backing).exporter, 1..5)
        val store = ReadRefusingStore(backing, clockKey)
        val middle = restart(store)
        val ownBeforeClear = ownSeqs(middle.exporter)
        val cleared = middle.exporter.clear()
        store.heal()
        val corrupt = corruptTheClock(backing)

        val restarted = restart(backing)
        val heldAfterRestart = restarted.exporter.snapshot().elements.size
        restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertIs<ClockRecovery.Refused>(middle.clockRecovery, "the rig: the clock read was not refused") },
            { assertEquals((1L..5L).toSet(), ownBeforeClear, "the rig: the spans read did not bring back 1..5") },
            { assertEquals(ExportResult.Success, cleared, "the rig: the clear was not saved") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(0, heldAfterRestart, "the rig: the clear left a span in the store") },
            { assertTrue(next.seq > 5, "a clear over an unarmed clock reset the high-water: $next") },
        )
    }

    /** An explicit stamp of this replica's raises the clock at once: no restart needs to read it back. */
    @Test
    fun anExplicitOwnStampRaisesTheClockWithoutARestart() = runTest {
        val exporter = restart(InMemoryDurableStore()).exporter
        val explicit = span(1).copy(causalStamp = CausalStamp(Dot(replica, 1_000L), emptySet()))
        val stored = exporter.export(explicit)
        exporter.export(span(2))
        val next = dotOf(exporter, 2)

        assertAll(
            { assertEquals(ExportResult.Success, stored, "the rig: the explicit stamp was not saved") },
            { assertTrue(next.seq > 1_000L, "the clock minted $next under an explicit stamp it already holds") },
        )
    }

    /**
     * An explicit stamp of this replica's, its span then evicted, and the clock entry corrupted: only
     * the carried high-water mark still knows the stamp's dot was used.
     */
    @Test
    fun anEvictedExplicitOwnStampIsCoveredAfterARestart() = runTest {
        val store = InMemoryDurableStore()
        val previous = restart(store, maxSpans = 1).exporter
        val explicit = span(1).copy(causalStamp = CausalStamp(Dot(replica, 1_000L), emptySet()))
        val stored = previous.export(explicit)
        val evicting = previous.export(peerSpan(2))
        val corrupt = corruptTheClock(store)

        val restarted = restart(store, maxSpans = 1)
        val heldAfterRestart = ownSeqs(restarted.exporter)
        restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertEquals(ExportResult.Success, stored, "the rig: the explicit stamp was not saved") },
            { assertEquals(ExportResult.Success, evicting, "the rig: the evicting span was not saved") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(emptySet(), heldAfterRestart, "the rig: the explicit stamp's span was not evicted") },
            { assertTrue(next.seq > 1_000L, "the corrupt clock minted $next under an evicted explicit stamp") },
        )
    }

    /**
     * The same, exported while the clock's read is refused: an unarmed clock has no `seq` to carry,
     * so the span write's own high-water mark is the only record of the explicit stamp.
     */
    @Test
    fun anEvictedExplicitOwnStampExportedWhileTheClockIsUnrecoveredIsCovered() = runTest {
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val previous = restart(store, maxSpans = 1)
        val explicit = span(1).copy(causalStamp = CausalStamp(Dot(replica, 1_000L), emptySet()))
        val stored = previous.exporter.export(explicit)
        val evicting = previous.exporter.export(peerSpan(2))
        store.heal()
        val corrupt = corruptTheClock(backing)

        val restarted = restart(backing, maxSpans = 1)
        val heldAfterRestart = ownSeqs(restarted.exporter)
        restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertIs<ClockRecovery.Refused>(previous.clockRecovery, "the rig: the clock read was not refused") },
            // recover refused, the first export skipped inside its window (#3436), the second was refused.
            { assertEquals(2, store.refusals, "the rig: recover and the second export did not meet a refused clock read") },
            { assertEquals(ExportResult.Success, stored, "the rig: the explicit stamp was not saved") },
            { assertEquals(ExportResult.Success, evicting, "the rig: the evicting span was not saved") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(emptySet(), heldAfterRestart, "the rig: the explicit stamp's span was not evicted") },
            { assertTrue(next.seq > 1_000L, "the corrupt clock minted $next under an explicit stamp an unarmed clock saw") },
        )
    }

    /**
     * A peer hands back a dot of this replica's after the span read has landed, with the clock unarmed
     * for the whole process, and an export at the cap then evicts it. The merge is the only thing that
     * saw the dot: the clock has no `seq` to carry, and the landing read came before the merge.
     */
    @Test
    fun anEvictedOwnDotMergedWhileTheClockIsUnrecoveredIsCovered() = runTest {
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val previous = restart(store, maxSpans = 1)
        val handedBack = span(1).copy(causalStamp = CausalStamp(Dot(replica, 1_000L), emptySet()))
        val merged = previous.exporter.merge(ORSet.empty<SpanRecord>().piece { it.add(peer, handedBack) })
        val ownAfterMerge = ownSeqs(previous.exporter)
        val evicting = previous.exporter.export(peerSpan(2))
        store.heal()
        val corrupt = corruptTheClock(backing)

        val restarted = restart(backing, maxSpans = 1)
        val heldAfterRestart = ownSeqs(restarted.exporter)
        restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertIs<ClockRecovery.Refused>(previous.clockRecovery, "the rig: the clock read was not refused") },
            // recover refused, and the export skipped the read inside that refusal's window (#3436).
            { assertEquals(1, store.refusals, "the rig: recover did not meet a refused clock read, or the export read past its window") },
            { assertEquals(ExportResult.Success, merged, "the rig: the merge was not saved") },
            { assertEquals(setOf(1_000L), ownAfterMerge, "the rig: the merge did not bring this replica's dot") },
            { assertEquals(ExportResult.Success, evicting, "the rig: the evicting span was not saved") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(emptySet(), heldAfterRestart, "the rig: the merged span was not evicted") },
            { assertTrue(next.seq > 1_000L, "the corrupt clock minted $next under a merged dot an unarmed clock saw") },
        )
    }

    /**
     * The clock's entry is ahead of every span: it minted ten dots no stored span holds, as a stamp
     * rolled back from a refused write leaves one. A write that mints nothing, a merge here, still
     * carries the clock's `seq`, so a later corrupt entry floors above all ten.
     */
    @Test
    fun aWriteThatMintsNothingStillCarriesTheClocksSeq() = runTest {
        val store = InMemoryDurableStore()
        val ahead = WarpCausalClock(replica).armed()
        repeat(10) { ahead.mint() }
        val clockWritten = ahead.persist(store)
        val middle = restart(store)
        val merged = middle.exporter.merge(WarpSpanExporter(peer, InMemoryDurableStore()).apply { export(peerSpan(1)) }.snapshot())
        val ownAfterMerge = ownSeqs(middle.exporter)
        val corrupt = corruptTheClock(store)

        val restarted = restart(store)
        restarted.exporter.export(span(99))
        val next = dotOf(restarted.exporter, 99)

        assertAll(
            { assertEquals(ClockPersist.Persisted, clockWritten, "the rig: the clock entry at 10 was not written") },
            { assertIs<ClockRecovery.Recovered>(middle.clockRecovery, "the rig: the middle process did not read the clock") },
            { assertEquals(ExportResult.Success, merged, "the rig: the merge was not saved") },
            { assertEquals(emptySet(), ownAfterMerge, "the rig: a span holds a dot of this replica's") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertTrue(next.seq > 10, "the corrupt clock minted $next, under a seq the clock had reached") },
        )
    }

    /**
     * The format before #3435 stored the bare set. There are no users of it, so it is not migrated: it
     * reads as undecodable and the exporter starts fresh, as for any other corrupt entry.
     */
    @Test
    fun aBareSetEntryReadsAsUndecodable() = runTest {
        val store = InMemoryDurableStore()
        val bare = ORSet.empty<SpanRecord>().piece { it.add(replica, span(1)) }
        store.write(spansKey, Cbor { alwaysUseByteString = true }.encodeToByteArray(ORSet.serializer(SpanRecord.serializer()), bare))

        val exporter = WarpSpanExporter(replica, store)
        exporter.recover()

        assertAll(
            { assertTrue(exporter.health.value.recoveryFailed, "the bare set was not recorded as a recovery failure") },
            { assertEquals(0, exporter.snapshot().elements.size, "the bare set was decoded as spans") },
        )
    }
}
