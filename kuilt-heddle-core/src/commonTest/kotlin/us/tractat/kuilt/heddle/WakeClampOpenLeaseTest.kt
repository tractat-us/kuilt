package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A lease still open when its group is clamped as a waker (#3238). The clamp lands the waker at
 * the front plus what its open leases reserve, so what that lease hands back when it settles is
 * the waker's own refund, not credit the clamp forgave.
 *
 * The rig: `root → {a, b}` at equal weight. Both ask through a warm-up, then `a` leases its last
 * job at [Row.reserved] and its queue empties, and `b` runs [ALONE] jobs alone, so the engine sees
 * `a` stop asking with that lease still open. Then `a`'s next burst queues while the lease is
 * open: the first pick wakes it, the lease settles as [Row.settle] says, and 19 more picks follow
 * with both asking. Each cell counts `a`'s 20 picks.
 *
 * Three engines take those 20 picks. **Kept**: persisted and restored with its wake memory, where
 * `a` is a returner moved by its parent's clock and the clamp never runs. **Dropped**: restored
 * without it, a restarted scheduler that never saw either child, so it clamps both as wakers.
 * **Reloaded**: the kept engine, with both lanes reweighted while the lease is open, which moves
 * the lease onto `a`'s new edge; both new edges are seated and then clamped as wakers no step has
 * seen. The last two must match the first within a pick.
 *
 * With the clamp at the front alone, `a` was forgiven its open lease: the dropped and reloaded
 * columns read 10, 14, 14, 10 against the kept 10, 10, 10, 6, three cells of four in each.
 */
class WakeClampOpenLeaseTest {

    private val pair = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("b")))
    private val doubled = Topology("root", listOf(Topology.Lane("a", Weight.of(2L)), Topology.Lane("b", Weight.of(2L))))
    private val a = pair.groupId("a")
    private val b = pair.groupId("b")
    private val both = setOf(a, b)
    private val policy = LocalPolicy(defaultReservation = 1L)

    /** How the open lease settles after the first pick wakes `a`. */
    private enum class Settle { COMPLETE, CANCEL }

    private class Row(val label: String, val reserved: Long, val settle: Settle, val cost: Long)

    private val rows = listOf(
        Row("reserved 1 cost 1", reserved = 1L, settle = Settle.COMPLETE, cost = 1L),
        Row("reserved 8 cost 1", reserved = 8L, settle = Settle.COMPLETE, cost = 1L),
        Row("reserved 8 cancelled", reserved = 8L, settle = Settle.CANCEL, cost = 0L),
        Row("reserved 8 cost 8", reserved = 8L, settle = Settle.COMPLETE, cost = 8L),
    )

    /** The engine once `a` has been seen to stop asking, and `a`'s lease that is still open. */
    private fun aWentIdleWithItsLeaseOpen(reserved: Long): Pair<LocalHeddle, Lease> {
        val h = LocalHeddle.bootWhole(pair, policy, epoch = 0L)
        h.reconcileCapacity(totalSlots = 8L)
        repeat(WARM_UP) {
            val lease = h.lease(both, startedAt = it.toLong())
            h.complete(lease.id, 1L, waiting = both)
        }
        // a's last job, picked from among both, and its queue is then empty.
        val out = h.acquire(waiting = both, eligible = setOf(a), startedAt = WARM_UP.toLong(), reservation = reserved)
        val open = assertIs<AcquireOutcome.Acquired>(out, "a's last job").lease
        repeat(ALONE) {
            val lease = h.lease(setOf(b), startedAt = WARM_UP + 1L + it)
            h.complete(lease.id, 1L, waiting = setOf(b))
        }
        return h to open
    }

    private fun restored(h: LocalHeddle, keepMemory: Boolean): LocalHeddle {
        val s = h.persist()
        val snapshot = if (keepMemory) s else LocalHeddleSnapshot(s.epoch, s.ledger, AllocatorMemory.EMPTY, s.totalSlots, s.open, s.lastApplied)
        return LocalHeddle.restore(snapshot, policy, epoch = s.epoch + 1)
    }

    /** `a`'s picks of the 20, and the wake offset on the edge holding its lease once the first pick has woken it, the lease still open. */
    private class Run(val picks: Int, val wakeOffset: Int128)

    /** The first pick wakes `a` with its lease open; the lease settles as [row] says; 19 more picks with both asking. */
    private fun aPicksAfter(h: LocalHeddle, open: Lease, row: Row): Run {
        var picks = 0
        var wakeOffset = Int128.ZERO
        repeat(PICKS) {
            val lease = h.lease(both, startedAt = 100L + it)
            if (lease.leaf == a) picks++
            h.complete(lease.id, 1L, waiting = both)
            if (it == 0) {
                val edge = assertNotNull(h.capturedPath(open.id), "${row.label}: the lease is still open at the wake").last()
                wakeOffset = h.persist().allocatorMemory.wakeOffsets[edge] ?: Int128.ZERO
                val settled = when (row.settle) {
                    Settle.COMPLETE -> h.complete(open.id, row.cost, waiting = both)
                    Settle.CANCEL -> h.cancel(open.id)
                }
                assertIs<LeaseOutcome.Done>(settled, "${row.label}: the open lease settles")
            }
        }
        return Run(picks, wakeOffset)
    }

    private class Cell(val kept: Int, val dropped: Run, val reloaded: Run, val leftWithLeaseOpen: Boolean, val leaseMoved: Boolean)

    private fun measure(row: Row): Cell {
        val (h, open) = aWentIdleWithItsLeaseOpen(row.reserved)
        val edge = assertNotNull(h.snapshot().ledger.lineageOf(a)).single()
        val left = edge in h.persist().allocatorMemory.departures && h.snapshot().leases.any { it.id == open.id }
        val kept = aPicksAfter(restored(h, keepMemory = true), open, row).picks
        val dropped = aPicksAfter(restored(h, keepMemory = false), open, row)
        val reload = restored(h, keepMemory = true)
        assertTrue(reload.apply(doubled, generation = 1L).refusals.isEmpty(), "${row.label}: the reload lands whole")
        val moved = reload.capturedPath(open.id) != listOf(edge)
        return Cell(kept, dropped, aPicksAfter(reload, open, row), left, moved)
    }

    private fun table(cells: Map<Row, Cell>): String =
        cells.entries.joinToString("\n") { (row, c) -> "  ${row.label}: kept ${c.kept}, dropped ${c.dropped.picks} (offset ${c.dropped.wakeOffset}), reloaded ${c.reloaded.picks} (offset ${c.reloaded.wakeOffset})" }

    private fun withinAPick(cells: Map<Row, Cell>, column: String, of: (Cell) -> Int): Array<() -> Unit> =
        cells.map { (row, cell) ->
            { assertTrue(abs(cell.kept - of(cell)) <= 1, "${row.label}: $column must match kept within a pick\n${table(cells)}") }
        }.toTypedArray()

    /**
     * The rig, standing: the wake left `a`'s lease edge an offset of at least the lease's own
     * reservation, in ticks. Clamped at the front alone, seven of the eight cells read `0`, since
     * `a` wakes level with or ahead of that front. Only the dropped engine's reserved-1 row stays
     * past it either way: `b`'s four jobs alone leave `a` 3 units behind the front, 48 ticks.
     */
    private fun clampedPastItsLease(cells: Map<Row, Cell>, column: String, of: (Cell) -> Run): Array<() -> Unit> =
        cells.map { (row, cell) ->
            {
                val floor = Int128.of(row.reserved * TICKS_PER_UNIT)
                assertTrue(of(cell).wakeOffset >= floor, "the rig, ${row.label}: the $column engine clamped a past the front by its open lease, ${of(cell).wakeOffset} < $floor\n${table(cells)}")
            }
        }.toTypedArray()

    /** A restart without the wake memory clamps `a` with its lease open, and gives it back nothing. */
    @Test
    fun aRestartedWakerIsClampedPastTheFrontByItsOpenLease() {
        val cells = rows.associateWith(::measure)
        assertAll(
            { assertTrue(cells.values.all { it.leftWithLeaseOpen }, "the rig: the engine saw a stop asking with its lease open") },
            { assertEquals(listOf(10, 10, 10, 6), cells.values.map { it.kept }, "the kept column, as the memory reads it\n${table(cells)}") },
            { assertEquals(listOf(10, 10, 10, 6), cells.values.map { it.dropped.picks }, "the dropped column, the same\n${table(cells)}") },
            *withinAPick(cells, "dropped") { it.dropped.picks },
            *clampedPastItsLease(cells, "dropped") { it.dropped },
        )
    }

    /**
     * A reweight while `a`'s lease is open moves the lease onto `a`'s new edge, and that edge is
     * clamped as a waker no step has seen: the same clamp, reached by a reload. A reweight resets
     * both lanes' history, so the 6 is the open lease's own 8 charged on the new edge.
     */
    @Test
    fun aReloadedWakerIsClampedPastTheFrontByTheLeaseItMoved() {
        val cells = rows.associateWith(::measure)
        assertAll(
            { assertTrue(cells.values.all { it.leaseMoved }, "the rig: the reload moved the open lease onto a new edge") },
            { assertEquals(listOf(10, 10, 10, 6), cells.values.map { it.reloaded.picks }, "the reloaded column\n${table(cells)}") },
            *withinAPick(cells, "reloaded") { it.reloaded.picks },
            *clampedPastItsLease(cells, "reloaded") { it.reloaded },
        )
    }

    /** How far `a` stands ahead of `b` on [h]'s share line, in units of service: `lag(b) − lag(a)`. */
    private fun aAheadOfB(h: LocalHeddle): Rational {
        val line = h.shareLine(both)
        return assertNotNull(line.lag(b)) - assertNotNull(line.lag(a))
    }

    /**
     * The share line takes the step a pick would, so on the restarted engine it reads `a` as far
     * ahead of `b` as the kept one does: its open lease counted past the front, before any pick has
     * clamped it.
     */
    @Test
    fun theShareLineCountsTheOpenLeaseAsThePickWould() {
        val read = rows.filter { it.settle == Settle.COMPLETE }.distinctBy { it.reserved }.map { row ->
            val (h, _) = aWentIdleWithItsLeaseOpen(row.reserved)
            Triple(row.reserved, aAheadOfB(restored(h, keepMemory = true)), aAheadOfB(restored(h, keepMemory = false)))
        }
        assertAll(
            { assertEquals(listOf(Rational.of(1L, 1L), Rational.of(8L, 1L)), read.map { it.second }, "the rig: a stands its open reservation ahead on the kept engine, $read") },
            *read.map { (reserved, kept, dropped) -> { assertEquals(kept, dropped, "reserved $reserved: the restarted engine's line reads a where the kept one does") } }.toTypedArray(),
        )
    }

    private companion object {
        const val WARM_UP = 20
        const val ALONE = 4
        const val PICKS = 20
    }
}
