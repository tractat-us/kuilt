package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins that a clock whose entry would not decode never re-mints a dot a durable span already
 * carries (#3407, the uniqueness #1053 protects).
 *
 * An undecodable clock entry starts the clock fresh at `seq=0`, and must: no later read can do
 * better. But the stored spans still carry this replica's stamps up to the old high-water mark, so
 * before the fix the next stamp was `seq=1` again. The read of the stored spans is the floor: once it
 * lands, the clock mints above the highest own-replica stamp it found. It can land in `recover` or,
 * after a refused one, in a later write turn, and the stamp that turn mints must already be above it.
 *
 * Every arm asserts its rig fired: the clock entry really was undecodable, and the span read really
 * was refused first where the arm says so.
 */
class WarpCausalClockSpanFloorTest {

    private val replica = ReplicaId("a")
    private val clockKey = WarpCausalClock.STORE_KEY
    private val spansKey = StoreKey("otel.spans")

    private fun span(id: Byte) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { id }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    /** What a previous process left in [store]: [count] spans, auto-stamped by its clock. Its own dots. */
    private suspend fun durableStampedSpans(store: DurableStore, count: Int): Set<Dot> {
        val previous = WarpTelemetry(replica, store).also { it.recover() }
        repeat(count) { i ->
            assertEquals(ExportResult.Success, previous.spans.export(span((i + 1).toByte())), "the rig: span ${i + 1} was not saved")
        }
        return previous.spans.snapshot().elements.mapNotNull { it.causalStamp?.dot }.toSet()
    }

    /** Overwrite the clock's entry with bytes that do not decode, and prove a fresh read finds them so. */
    private suspend fun corruptTheClock(store: DurableStore): ClockRecovery {
        store.write(clockKey, byteArrayOf(0x7f, 0x00, 0x13))
        return WarpCausalClock(replica).recover(store)
    }

    /** Overwrite the clock's entry with a clean one at [seq], written by a clock that minted to it. */
    private suspend fun clockEntryAt(store: DurableStore, seq: Long) {
        val clock = WarpCausalClock(replica).armed()
        repeat(seq.toInt()) { clock.mint() }
        assertEquals(ClockPersist.Persisted, clock.persist(store), "the rig: the clock entry was not written")
    }

    /** The dot [telemetry] stamped [id] with, or an [AssertionError] if the span is missing or unstamped. */
    private fun WarpTelemetry.dotOf(id: Byte): Dot =
        spans.snapshot().elements.single { it.spanId == span(id).spanId }.causalStamp?.dot
            ?: throw AssertionError("span $id was saved unstamped")

    @Test
    fun aCorruptClockMintsAboveTheSpansRecoverRead() = runTest {
        val store = InMemoryDurableStore()
        val durable = durableStampedSpans(store, 5)
        val corrupt = corruptTheClock(store)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val result = restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals((1L..5L).map { Dot(replica, it) }.toSet(), durable, "the rig: the durable spans are not stamped 1..5") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(ExportResult.Success, result) },
            { assertTrue(next.seq > 5, "the corrupt clock re-minted $next, a dot a durable span holds") },
        )
    }

    @Test
    fun aCorruptClockMintsAboveTheSpansAHealingTurnReads() = runTest {
        val backing = InMemoryDurableStore()
        val durable = durableStampedSpans(backing, 5)
        val corrupt = corruptTheClock(backing)
        val store = ReadRefusingStore(backing, spansKey)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val refusedAtRecover = store.refusals
        store.heal()
        // recover's refusal opens a window of one turn (#3436), so the healing turn is the next.
        restarted.spans.skipReadWindow(1, span(98))
        val result = restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals(5, durable.size, "the rig: the durable spans are not stamped 1..5") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(1, refusedAtRecover, "the rig: recover's span read was not refused") },
            { assertEquals(1, store.refusals, "the rig: the healing turn's read was refused too") },
            { assertEquals(ExportResult.Success, result) },
            { assertTrue(next.seq > 5, "the healing turn minted $next before its span read raised the floor") },
        )
    }

    @Test
    fun aFloorFromSpansReadBeforeTheClockReadLandsSurvivesACorruptEntry() = runTest {
        val backing = InMemoryDurableStore()
        val durable = durableStampedSpans(backing, 5)
        val corrupt = corruptTheClock(backing)
        val store = ReadRefusingStore(backing, clockKey)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val refusedAtRecover = store.refusals
        store.heal()
        // recover's refusal opens a window of one turn that skips the clock's read (#3436).
        restarted.spans.skipClockWindow(1, span(98))
        val result = restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals(5, durable.size, "the rig: the durable spans are not stamped 1..5") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(1, refusedAtRecover, "the rig: recover's clock read was not refused") },
            { assertEquals(ExportResult.Success, result) },
            { assertTrue(next.seq > 5, "a floor read while the clock was unrecovered was lost: $next") },
        )
    }

    @Test
    fun aSpanTheRecoveryJoinEvictsStillRaisesTheFloor() = runTest {
        val store = InMemoryDurableStore()
        val durable = durableStampedSpans(store, 5)
        corruptTheClock(store)

        // DROP_NEWEST at a cap of 2 evicts the spans stamped 3, 4 and 5, so only 1 and 2 stay in memory.
        val restarted = WarpTelemetry(replica, store, maxSpans = 2, bufferPolicy = BufferPolicy.DROP_NEWEST)
            .also { it.recover() }
        val keptAfterRecover = restarted.spans.snapshot().elements.mapNotNull { it.causalStamp?.dot?.seq }.toSet()
        restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals(5, durable.size, "the rig: the durable spans are not stamped 1..5") },
            { assertEquals(setOf(1L, 2L), keptAfterRecover, "the rig: the recovery join did not evict 3..5") },
            { assertTrue(next.seq > 5, "the floor skipped the spans the join evicted: $next") },
        )
    }

    /**
     * A remote span names one of this replica's dots as a predecessor, and no span of this replica's
     * carries it any more: here this replica stored no span at all, only the one peer `b` gossiped.
     * The predecessor is then the only evidence that `(a, 7)` was minted. A corrupt clock that re-minted
     * it would let [inferCausalLinks] resolve `b`'s old predecessor to the new span, an edge that never
     * happened.
     */
    @Test
    fun aCorruptClockMintsAboveAnOwnDotOnlyARemotePredecessorNames() = runTest {
        val store = InMemoryDurableStore()
        val peer = ReplicaId("b")
        val remote = WarpSpanExporter(peer, InMemoryDurableStore()).apply {
            export(span(42).copy(causalStamp = CausalStamp(Dot(peer, 1L), setOf(Dot(replica, 7L)))))
        }.snapshot()
        val previous = WarpTelemetry(replica, store).also { it.recover() }
        val merged = previous.spans.merge(remote)
        val corrupt = corruptTheClock(store)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val recovered = restarted.spans.snapshot().elements
        val ownStamps = recovered.count { it.causalStamp?.dot?.replica == replica }
        val predecessors = recovered.flatMap { it.causalStamp?.predecessors.orEmpty() }.toSet()
        restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals(ExportResult.Success, merged, "the rig: the remote span was not stored") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(0, ownStamps, "the rig: the recovered set holds a stamp of this replica's") },
            { assertEquals(setOf(Dot(replica, 7L)), predecessors, "the rig: the remote predecessor did not survive") },
            { assertTrue(next.seq > 7, "the corrupt clock re-minted $next, a dot a remote predecessor names") },
        )
    }

    /**
     * The floor trusts what the stored spans say, and a peer's span is stored once merged. One that
     * claims this replica's last possible dot, from a buggy or hostile peer, raises the floor to
     * `Long.MAX_VALUE`. The next stamp has no dot left to take. Before the fix `seq + 1` wrapped and
     * [Dot]'s `init` threw out of [WarpSpanExporter.export] on every turn. Now the clock mints nothing
     * and the span is saved unstamped. Whether a merge should refuse such a dot is #3432's question.
     */
    @Test
    fun aFloorAtTheLastDotSavesSpansUnstampedInsteadOfThrowing() = runTest {
        val store = InMemoryDurableStore()
        val peer = ReplicaId("b")
        val last = Dot(replica, Long.MAX_VALUE)
        val remote = WarpSpanExporter(peer, InMemoryDurableStore()).apply {
            export(span(42).copy(causalStamp = CausalStamp(last, emptySet())))
        }.snapshot()
        val previous = WarpTelemetry(replica, store).also { it.recover() }
        val merged = previous.spans.merge(remote)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val stored = restarted.spans.snapshot().elements.mapNotNull { it.causalStamp?.dot }.toSet()
        val first = restarted.spans.export(span(98))
        val second = restarted.spans.export(span(99))
        val saved = restarted.spans.snapshot().elements.filter { it.spanId in setOf(span(98).spanId, span(99).spanId) }

        assertAll(
            { assertEquals(ExportResult.Success, merged, "the rig: the remote span was not stored") },
            { assertEquals(setOf(last), stored, "the rig: the recovered set does not hold the last dot") },
            { assertEquals(ExportResult.Success, first) },
            { assertEquals(ExportResult.Success, second) },
            { assertEquals(listOf(null, null), saved.map { it.causalStamp }, "a span was not saved unstamped: $saved") },
        )
    }

    /**
     * This replica's spans are gone from its own store and its clock entry is corrupt, so the read
     * raises no floor. A peer still holds those spans and gossips them back. They are in memory now,
     * and the next local stamp must not collide with one of them.
     */
    @Test
    fun aCorruptClockMintsAboveTheOwnDotsAMergeGossipsBack() = runTest {
        val previous = InMemoryDurableStore()
        val held = durableStampedSpans(previous, 5)
        val gossipedBack = WarpTelemetry(replica, previous).also { it.recover() }.spans.snapshot()
        val store = InMemoryDurableStore()
        val corrupt = corruptTheClock(store)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val ownBeforeMerge = restarted.spans.snapshot().elements.count { it.causalStamp != null }
        val merged = restarted.spans.merge(gossipedBack)
        restarted.spans.export(span(99))
        val next = restarted.dotOf(99)

        assertAll(
            { assertEquals((1L..5L).map { Dot(replica, it) }.toSet(), held, "the rig: the peer does not hold 1..5") },
            { assertIs<ClockRecovery.Undecodable>(corrupt, "the rig: the clock entry decoded as $corrupt") },
            { assertEquals(0, ownBeforeMerge, "the rig: the restarted store still held a stamped span") },
            { assertEquals(ExportResult.Success, merged, "the rig: the gossiped spans were not merged") },
            { assertTrue(next.seq > 5, "the next stamp $next collides with a dot the merge gossiped back") },
        )
    }

    @Test
    fun aCleanClockAheadOfTheSpansIsNotWoundBack() = runTest {
        val store = InMemoryDurableStore()
        val durable = durableStampedSpans(store, 3)
        clockEntryAt(store, 10)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        restarted.spans.export(span(99))

        assertAll(
            { assertEquals(3L, durable.maxOf { it.seq }, "the rig: the durable spans do not end at 3") },
            { assertEquals(Dot(replica, 11L), restarted.dotOf(99), "the span floor moved the clock") },
        )
    }

    @Test
    fun aCleanClockBehindTheSpansIsRaised() = runTest {
        val store = InMemoryDurableStore()
        val durable = durableStampedSpans(store, 5)
        clockEntryAt(store, 2)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        restarted.spans.export(span(99))

        assertAll(
            { assertEquals(5L, durable.maxOf { it.seq }, "the rig: the durable spans do not end at 5") },
            { assertEquals(Dot(replica, 6L), restarted.dotOf(99), "a clock behind the spans re-minted a dot") },
        )
    }
}
