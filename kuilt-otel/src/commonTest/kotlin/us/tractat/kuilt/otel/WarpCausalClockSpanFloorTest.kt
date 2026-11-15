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
