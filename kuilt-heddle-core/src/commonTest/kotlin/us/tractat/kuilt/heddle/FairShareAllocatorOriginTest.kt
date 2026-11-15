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
     * An origin raise is a whole number of ticks on every child's grid (#3353): `Δ` is a multiple of
     * the least common multiple of `d / gcd(d, 16n)`, here `7 / gcd(7, 48) = 7` and
     * `5 / gcd(5, 16) = 5`, so 35. A raise off a child's grid would shift it by a fraction of a tick,
     * which the floor in [originTicks] turns into a residue that can move a pick.
     */
    @Test
    fun anOriginRaiseIsWholeTicksOnEveryChildsGrid() {
        val x = AttachmentRecord(AttachmentId("x"), root, GroupId("x"), Weight.of(3L, 7L))
        val y = AttachmentRecord(AttachmentId("y"), root, GroupId("y"), Weight.of(1L, 5L))
        var ledger = withChildren(x, y)
        ledger = ledger.piece(ledger.mint(MintId("big"), root, self, 1L shl 61).delta)
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = ledger
            override fun mutateOrSkip(transform: (EntitlementLedger) -> us.tractat.kuilt.crdt.Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                return true
            }
        }
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = LocalHeddle.MAX_RESERVATION))
        val origins = ArrayList<Int128>()
        var calls = 0
        while (allocator.rebases < 4 && calls < 20_000) {
            calls++
            val target = { id: AttachmentId -> (cell.current.edge(id)?.outstanding ?: 0L) + LocalHeddle.MAX_RESERVATION }
            allocator.schedule(root, cell, earmarked = 0L) { id -> Demand(target(id), LocalHeddle.MAX_RESERVATION) }
            val o = cell.current.origin(root)
            if (origins.lastOrNull() != o && o > Int128.ZERO) origins += o
        }
        fun onGrid(o: Int128, w: Weight) =
            us.tractat.kuilt.math.mulDiv(o, 16L * w.numerator, w.denominator, us.tractat.kuilt.math.Rounding.FLOOR) ==
                us.tractat.kuilt.math.mulDiv(o, 16L * w.numerator, w.denominator, us.tractat.kuilt.math.Rounding.CEILING)
        assertAll(
            { assertTrue(allocator.rebases >= 4, "the rig: the parent rebased ${allocator.rebases} times in $calls calls") },
            { assertTrue(origins.all { onGrid(it, x.weight) && onGrid(it, y.weight) }, "every origin is on both grids: $origins") },
        )
    }

    /**
     * A heavy newborn joining a busy light runner (#3353 review, item A). The runner, at weight 1,
     * sits just below its own rebase threshold `R`, so nothing rebased it; the newborn, at weight
     * [k], is seated at that front, about `k · R` on its own grid. Past 16 that is past its ceiling,
     * and the pick that followed refused on every call. The origin is now raised before the seat.
     */
    private fun assertAHeavyNewbornJoinsABusyLightRunner(k: Long) {
        val runner = AttachmentRecord(AttachmentId("r"), root, GroupId("r"), Weight.ONE)
        val newborn = AttachmentRecord(AttachmentId("n"), root, GroupId("n"), Weight.of(k))
        var ledger = withChildren(runner, newborn)
        ledger = ledger.piece(ledger.mint(MintId("more"), root, self, 1L shl 50).delta)
        val below = rebaseThreshold(Weight.ONE) - TICKS_PER_UNIT
        ledger = ledger.piece(EntitlementLedger.of(gauges = mapOf(runner.id to gaugeAt(Int128.of(below), folded = 0L))))
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = ledger
            override fun mutateOrSkip(transform: (EntitlementLedger) -> us.tractat.kuilt.crdt.Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                return true
            }
        }
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 1L), AllocatorMemory(emptyMap(), mapOf(runner.id to true)))
        val wants = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1L)
        val outcomes = (0 until 3).map { allocator.schedule(root, cell, earmarked = 0L, demand = { wants }) }
        val nIssued = cell.current.edge(newborn.id)?.issued ?: 0L
        assertAll(
            { assertTrue(Int128.of(below).let { it < Int128.of(rebaseThreshold(Weight.ONE)) }, "the rig: the runner is below its own threshold") },
            { assertTrue(outcomes.none { it is AllocationOutcome.PositionCeiling }, "1:$k: no call refused: $outcomes") },
            { assertTrue(nIssued > 0L, "1:$k: the newborn was served ($nIssued)") },
            { assertTrue(allocator.rebases > 0, "1:$k: the rig: the parent rebased") },
        )
    }

    @Test
    fun aOneToTwentyNewbornJoinsABusyLightRunner() = assertAHeavyNewbornJoinsABusyLightRunner(20L)

    @Test
    fun aOneToSixtyNewbornJoinsABusyLightRunner() = assertAHeavyNewbornJoinsABusyLightRunner(60L)

    /**
     * A restarted allocator meets a long-idle child demanding again (#3353 review, item B). It has
     * observed nothing, so the child is not a waker by observation. It sits about 2⁶⁸ ticks below
     * the origin on its own grid, past `Long`, and the pick that ordered it refused with
     * `PositionCeiling` on every call. A seated, demanding child below zero that this allocator never
     * saw is clamped as a waker now: no rebase takes a child that kept demanding below zero.
     */
    @Test
    fun aNeverObservedChildFarBelowTheOriginIsClampedNotRefused() {
        val runner = AttachmentRecord(AttachmentId("a"), root, GroupId("a"), Weight.ONE)
        val idler = AttachmentRecord(AttachmentId("b"), root, GroupId("b"), Weight.of(15L))
        val origin = Int128.of(1L shl 60)
        var ledger = withChildren(runner, idler)
        ledger = ledger.piece(
            EntitlementLedger.of(
                gauges = mapOf(
                    runner.id to gaugeAt(originTicks(origin, Weight.ONE) + 1_600L, folded = 0L),
                    idler.id to gaugeAt(Int128.ZERO, folded = 0L),
                ),
                origins = mapOf(root to origin),
            ),
        )
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = ledger
            override fun mutateOrSkip(transform: (EntitlementLedger) -> us.tractat.kuilt.crdt.Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                return true
            }
        }
        val wants = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1L)
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 1L))
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { wants }
        val below = originTicks(origin, idler.weight)
        assertAll(
            { assertTrue(below.toLongOrNull() == null, "the rig: b sits past Long below the origin, $below ticks") },
            { assertTrue(outcome is AllocationOutcome.Delegated, "the parent is scheduled, not refused: $outcome") },
            { assertTrue((allocator.memory.wakeOffsets[idler.id] ?: Rational.ZERO) > Rational.ZERO, "b was clamped as a waker") },
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
