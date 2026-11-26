package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
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
 * `a` stop asking with that lease still open. The engine is persisted and restored twice: with its
 * wake memory, where `a` is a returner moved by its parent's clock, and without it, a restarted
 * scheduler that never saw either child and so clamps both as wakers. Then `a`'s next burst
 * queues while the lease is open: the first pick wakes it, the lease settles as [Row.settle]
 * says, and 19 more picks follow with both asking. Each column counts `a`'s 20 picks.
 *
 * Kept and dropped must agree within a pick. Before the fix the dropped column gave `a` back
 * what the clamp forgave of its open lease: 14 of 20 where it had 10, three cells of four.
 */
class WakeClampOpenLeaseTest {

    private val pair = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("b")))
    private val a = pair.groupId("a")
    private val b = pair.groupId("b")
    private val both = setOf(a, b)
    private val policy = LocalPolicy(defaultReservation = 1L)

    /** How the open lease settles after the first pick wakes `a`. */
    private enum class Settle { COMPLETE, CANCEL }

    private class Row(val label: String, val reserved: Long, val settle: Settle, val cost: Long)

    private val rows = listOf(
        Row("reserved 1, cost 1", reserved = 1L, settle = Settle.COMPLETE, cost = 1L),
        Row("reserved 8, cost 1", reserved = 8L, settle = Settle.COMPLETE, cost = 1L),
        Row("reserved 8, cancelled", reserved = 8L, settle = Settle.CANCEL, cost = 0L),
        Row("reserved 8, cost 8", reserved = 8L, settle = Settle.COMPLETE, cost = 8L),
    )

    /** The engine at the restart, and `a`'s lease that is open across it. */
    private fun beforeTheRestart(reserved: Long): Pair<LocalHeddle, Lease> {
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

    private fun settle(h: LocalHeddle, open: Lease, row: Row): LeaseOutcome = when (row.settle) {
        Settle.COMPLETE -> h.complete(open.id, row.cost, waiting = both)
        Settle.CANCEL -> h.cancel(open.id)
    }

    /** The first pick wakes `a` with its lease open; the lease settles; 19 more picks with both asking. */
    private fun picksAfter(h: LocalHeddle, open: Lease, row: Row): List<GroupId> {
        val picks = ArrayList<GroupId>()
        repeat(PICKS) {
            val lease = h.lease(both, startedAt = 100L + it)
            picks += lease.leaf
            h.complete(lease.id, 1L, waiting = both)
            if (it == 0) assertIs<LeaseOutcome.Done>(settle(h, open, row), "${row.label}: the open lease settles")
        }
        return picks
    }

    private class Cell(val kept: Int, val dropped: Int, val leftWithLeaseOpen: Boolean)

    private fun measure(row: Row): Cell {
        val (h, open) = beforeTheRestart(row.reserved)
        val kept = restored(h, keepMemory = true)
        val dropped = restored(h, keepMemory = false)
        val keptPicks = picksAfter(kept, open, row)
        val droppedPicks = picksAfter(dropped, open, row)
        // The rig fired: the engine saw a stop asking, so the kept one moves it by the clock, with its lease open.
        val edge = assertNotNull(h.snapshot().ledger.lineageOf(a)).single()
        val left = edge in h.persist().allocatorMemory.departures && h.snapshot().leases.any { it.id == open.id }
        return Cell(keptPicks.count { it == a }, droppedPicks.count { it == a }, left)
    }

    @Test
    fun aWakerIsClampedToTheFrontPlusWhatItsOpenLeasesReserve() {
        val cells = rows.associateWith(::measure)
        val table = cells.entries.joinToString("\n") { (row, cell) -> "  ${row.label}: kept ${cell.kept}, dropped ${cell.dropped}" }
        assertAll(
            { assertEquals(listOf(10, 10, 10, 6), cells.values.map { it.kept }, "the kept column, as the memory reads it:\n$table") },
            { assertEquals(listOf(10, 10, 10, 6), cells.values.map { it.dropped }, "the dropped column, the same\n$table") },
            { assertTrue(cells.values.all { it.leftWithLeaseOpen }, "the rig: the engine saw a stop asking with its lease open") },
            *cells.map { (row, cell) ->
                { assertTrue(kotlin.math.abs(cell.kept - cell.dropped) <= 1, "${row.label}: dropped must match kept within a pick\n$table") }
            }.toTypedArray(),
        )
    }

    /** PROBE: a reload reweighting both lanes while a's lease is open, against the lease settling first. */
    @Test
    fun probeReload() {
        val doubled = Topology("root", listOf(Topology.Lane("a", Weight.of(2L)), Topology.Lane("b", Weight.of(2L))))
        val out = rows.map { row ->
            val (h, open) = beforeTheRestart(row.reserved)
            val (c, controlOpen) = beforeTheRestart(row.reserved)
            val moved = h.apply(doubled, generation = 1L)
            val acrossPicks = picksAfter(h, open, row).count { it == a }
            settle(c, controlOpen, row)
            c.apply(doubled, generation = 1L)
            val controlPicks = ArrayList<GroupId>()
            repeat(PICKS) {
                val lease = c.lease(both, startedAt = 100L + it)
                controlPicks += lease.leaf
                c.complete(lease.id, 1L, waiting = both)
            }
            "${row.label}: across ${acrossPicks}, settled first ${controlPicks.count { it == a }}, refusals ${moved.refusals.size}, path ${h.capturedPath(open.id)}"
        }
        kotlin.test.fail(out.joinToString("\n"))
    }

    private companion object {
        const val WARM_UP = 20
        const val ALONE = 4
        const val PICKS = 20
    }
}
