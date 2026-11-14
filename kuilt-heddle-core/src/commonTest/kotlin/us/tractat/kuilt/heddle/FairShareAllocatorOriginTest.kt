package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A parent's origin and the wide reads around it (#3353), at the allocator and the ledger: where a
 * seat is written, how an origin joins, and how it reads on a child's grid.
 */
class FairShareAllocatorOriginTest {

    private val root = GroupId("root")
    private val self = ReplicaId("self")
    private val hungry = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

    private fun withChildren(vararg records: AttachmentRecord): EntitlementLedger {
        var s = EntitlementLedger.bootstrap(root, mapOf(self to 100L), nonce = "3353-6")
        for (record in records) {
            s = s.piece(assertNotNull(s.prepare(record)).delta)
            s = s.piece(assertNotNull(s.activate(record.id)).delta)
        }
        return s
    }

    /**
     * An allocator-driven seat past the old ceiling is stored exactly (#3353). The runner `e`, at
     * weight 1/1000, sits at 10¹⁴ ticks, far below its own ceiling; on the newborn's grid, at weight
     * 1000, that front is 10²⁰ ticks, past both its ceiling and `Long`. Before slice 6 the front was
     * narrowed on its way to the seat, so the seat stored the newborn's ceiling instead.
     */
    @Test
    fun aSeatTheAllocatorTakesPastTheOldCeilingIsStoredExactly() {
        val runner = AttachmentRecord(AttachmentId("e"), root, GroupId("e"), Weight.of(1L, 1000L))
        val newborn = AttachmentRecord(AttachmentId("n"), root, GroupId("n"), Weight.of(1000L))
        val ledger = withChildren(runner, newborn)
            .piece(EntitlementLedger.of(gauges = mapOf(runner.id to gaugeAt(Int128.of(100_000_000_000_000L), folded = 0L))))
        val seated = FairShareAllocator(self, PolicyConfig(quantum = 10L)).settle(root, ledger) { hungry }
        val seat = assertNotNull(seated.gauge(newborn.id), "the newborn is seated")
        val exact = 100_000_000_000_000L.timesWide(1_000_000L)
        assertAll(
            { assertTrue(exact > Int128.of(positionCeiling(newborn.weight)), "the rig: the front is past the newborn's ceiling") },
            { assertTrue(exact.toLongOrNull() == null, "the rig: and past Long") },
            { assertEquals(exact, seat.ticks, "the seat is the front, exactly") },
        )
    }

    /**
     * A seat is absolute (#3353): `⌊O · w · G⌋ + ⌈V · w · G⌉`. Read back against the same origin, it
     * sits exactly at the front it was taken from, so the newborn starts level with the runner.
     */
    @Test
    fun aSeatUnderAnOriginIsAbsoluteAndReadsBackAtTheFront() {
        val runner = AttachmentRecord(AttachmentId("e"), root, GroupId("e"), Weight.ONE)
        val newborn = AttachmentRecord(AttachmentId("n"), root, GroupId("n"), Weight.of(3L, 7L))
        val origin = Int128.of(7_000L)
        val ledger = withChildren(runner, newborn).piece(
            EntitlementLedger.of(
                gauges = mapOf(runner.id to gaugeAt(Int128.of(7_000L * 16L + 160L), folded = 0L)),
                origins = mapOf(root to origin),
            ),
        )
        val seated = FairShareAllocator(self, PolicyConfig(quantum = 10L)).settle(root, ledger) { hungry }
        val seat = assertNotNull(seated.gauge(newborn.id))
        assertAll(
            { assertEquals(Rational.of(10L), seated.virtualService(runner.id), "the rig: the runner reads 10 units past the origin") },
            // ⌊7000 · 3/7 · 16⌋ + ⌈10 · 3/7 · 16⌉ = 48000 + 69.
            { assertEquals(Int128.of(48_000L + 69L), seat.ticks, "the seat adds the origin back on the newborn's grid") },
            { assertEquals(Rational.of(69L * 7L, 48L), seated.virtualService(newborn.id), "and reads back at the front, rounded up") },
        )
    }

    /**
     * The origin join is a `max` on the whole 128-bit value (#3353). `(1, 0)` is 2⁶⁴ and `(0, −1)` is
     * 2⁶⁴ − 1, the low word unsigned; a `max` on each word would assemble `(1, −1)`, which neither
     * side wrote.
     */
    @Test
    fun anOriginJoinsWholeNeverWordByWord() {
        val high = ParentRecord(1L, 0L)
        val low = ParentRecord(0L, -1L)
        assertAll(
            { assertEquals(high, high.join(low)) },
            { assertEquals(high, low.join(high)) },
            { assertEquals(low, low.join(low)) },
            {
                val joined = EntitlementLedger.of(origins = mapOf(root to high.origin))
                    .piece(EntitlementLedger.of(origins = mapOf(root to low.origin)))
                assertEquals(high.origin, joined.origin(root))
            },
        )
    }

    /**
     * An origin reads on a child's grid rounded down, so a child whose grid it is off reads under
     * one tick high: a penalty, never credit. `⌊5 · 1/3 · 16⌋ = 26`, where the exact value is 26⅔.
     */
    @Test
    fun anOriginOffAChildsGridReadsUnderOneTickHigh() {
        assertAll(
            { assertEquals(Int128.of(26L), originTicks(Int128.of(5L), Weight.of(1L, 3L))) },
            { assertEquals(Int128.of(80L), originTicks(Int128.of(5L), Weight.ONE)) },
            { assertEquals(Int128.ZERO, originTicks(Int128.ZERO, Weight.of(1L, 3L))) },
        )
    }
}
