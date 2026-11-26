package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A `HeddleNode` restart does not forgive a waker the grants it still holds (#3721). It is the
 * twin of `WakeClampOpenLeaseTest` in `:kuilt-heddle-core`, shaped like #3238's table.
 *
 * The rig: `root → p → {a, b}` at equal weight, quantum 8. A pick tops `p` up from the root by
 * exactly one grant's worth and lets `p` grant it, so each `schedule(p)` is one pick, and the
 * winner spends it at once, as a finished job. Both ask through a warm-up of [WARM_UP] picks. Then
 * `a`'s last grant is [Row.held] units, with `b` competing but unservable, and `a` keeps them
 * unspent. `a` stops asking and `b` runs [ALONE] picks alone, so the node sees `a` stop holding
 * them. Then 20 picks with both asking; after the first, `a` settles what it held as [Row.settle]
 * says. Each cell counts `a`'s 20 picks.
 *
 * **Kept** is the node that saw it all, so `a` returns moved by its parent's clock and no clamp
 * runs. **Dropped** is a new incarnation over the same ledger: the same replica and its holdings,
 * and no wake memory, since a `HeddleNode` keeps none across a restart. It meets `a` demanding for
 * the first time and clamps it to the front plus what its edge has outstanding, the units `a`
 * holds, so they stay counted as its service, as a position counts what was issued down an edge,
 * spent or not.
 *
 * Nothing a `HeddleNode` does hands a grant back: `complete` keeps the unused part at the leaf, so
 * "unspent" is the nearest row to #3238's cancel. How `a` settles therefore moves no position, and
 * the three held-8 rows read alike.
 *
 * Measured before the clamp counted the outstanding, the three held-8 dropped cells read 10, four
 * extra picks of 20 for `a`: the credit a clamp to the front alone forgave. Counting it past the
 * front brings them to 6, level with the kept column. Held 1 reads 10 and 10 either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeddleNodeRestartCreditTest {

    private val root = GroupId("root")
    private val p = GroupId("p")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val toP = AttachmentId("p")
    private val toA = AttachmentId("a")
    private val toB = AttachmentId("b")
    private val topology = listOf(
        AttachmentRecord(toP, root, p, Weight.ONE),
        AttachmentRecord(toA, p, a, Weight.ONE),
        AttachmentRecord(toB, p, b, Weight.ONE),
    )

    /** How `a` settles its held grants after the first pick of the measured run. */
    private enum class Settle { SPEND, NOTHING }

    private class Row(val label: String, val held: Long, val settle: Settle, val cost: Long)

    private val rows = listOf(
        Row("held 1 spent 1", held = 1L, settle = Settle.SPEND, cost = 1L),
        Row("held 8 spent 1", held = 8L, settle = Settle.SPEND, cost = 1L),
        Row("held 8 unspent", held = 8L, settle = Settle.NOTHING, cost = 0L),
        Row("held 8 spent 8", held = 8L, settle = Settle.SPEND, cost = 8L),
    )

    private fun HeddleNode.outstanding(edge: AttachmentId): Long = checkNotNull(ledger.value.edge(edge)).outstanding

    private fun HeddleNode.issued(edge: AttachmentId): Long = checkNotNull(ledger.value.edge(edge)).issued

    /** Spend [cost] of what [leaf] holds, as a finished job of that size. */
    private fun HeddleNode.spend(leaf: GroupId, cost: Long) {
        val reserved = assertIs<ReserveOutcome.Reserved>(reserve(leaf, cost), "${leaf.value} holds $cost to spend")
        complete(reserved.id, cost)
    }

    /**
     * One pick at `p`: top `p` up by exactly [amount] from the root, then let `p` grant it to one
     * of its children. [wants] is each child's appetite: `amount` more, `0` for one competing but
     * unservable (`maximumUsefulGrant = 0` keeps it demanding while trimming every grant to it to
     * nothing), and `null` for one asking nothing. Returns the edge the grant went down.
     */
    private fun HeddleNode.pick(amount: Long, wants: Map<AttachmentId, Long?>): AttachmentId {
        advertise(toP, Demand(targetOutstanding = outstanding(toP) + amount, maximumUsefulGrant = amount))
        assertEquals(ScheduleOutcome.Delegated(1), schedule(root), "the root tops p up by $amount")
        for ((edge, want) in wants) {
            advertise(edge, if (want == null) Demand.NONE else Demand(targetOutstanding = outstanding(edge) + amount, maximumUsefulGrant = want))
        }
        val before = listOf(toA, toB).associateWith { issued(it) }
        assertEquals(ScheduleOutcome.Delegated(1), schedule(p), "p grants its $amount")
        return listOf(toA, toB).single { issued(it) == before.getValue(it) + amount }
    }

    private val both: Map<AttachmentId, Long?> get() = mapOf(toA to 1L, toB to 1L)

    /** A pick of one unit among both, the winner spending it at once. */
    private fun HeddleNode.job() {
        val won = pick(1L, both)
        spend(if (won == toA) a else b, 1L)
    }

    /** The node once `a` has been seen to stop asking while it holds [held] unspent units. */
    private suspend fun TestScope.aWentIdleHolding(held: Long): HeddleNode {
        val node = staticCluster(root, peers = 1, mint = mapOf(0 to MINT), topology = topology, quantum = QUANTUM).peers.single().node
        repeat(WARM_UP) { node.job() }
        // a's last job, b asking but unservable: a is granted `held` and keeps it.
        assertEquals(toA, node.pick(held, mapOf(toA to held, toB to 0L)), "a's last grant")
        // a stops asking; b runs alone, spending each unit.
        repeat(ALONE) {
            assertEquals(toB, node.pick(1L, mapOf(toA to null, toB to 1L)))
            node.spend(b, 1L)
        }
        return node
    }

    /** A new incarnation of [node] over its ledger: the same replica, its holdings, and no wake memory. */
    private suspend fun TestScope.restarted(node: HeddleNode): HeddleNode {
        val seam = InMemoryLoom().host(Pattern("heddle-e2e"))
        assertEquals(node.self, ReplicaId(seam.selfId.value), "the rig: the restart keeps the replica")
        return HeddleNode(
            scope = backgroundScope,
            seam = seam,
            self = node.self,
            initialLedger = node.ledger.value,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = heddleTestConfig(seed = 2_000, quantum = QUANTUM),
            epoch = 1L,
        )
    }

    /** `a`'s picks of the next [PICKS], both asking; after the first, `a` settles what it held as [row] says. */
    private fun HeddleNode.aPicks(row: Row): Int {
        var picks = 0
        repeat(PICKS) {
            val won = pick(1L, both)
            if (won == toA) picks++
            spend(if (won == toA) a else b, 1L)
            if (it == 0 && row.settle == Settle.SPEND) spend(a, row.cost)
        }
        return picks
    }

    private class Cell(val kept: Int, val dropped: Int, val heldAtRestart: Long, val droppedWasEmpty: Boolean, val keptSawA: Boolean)

    private suspend fun TestScope.measure(row: Row): Cell {
        val node = aWentIdleHolding(row.held)
        val held = node.ledger.value.holdings(a, node.self)
        val fresh = restarted(node)
        val droppedWasEmpty = fresh.allocatorMemoryForTest == AllocatorMemory.EMPTY
        val keptSawA = node.allocatorMemoryForTest != AllocatorMemory.EMPTY
        val dropped = fresh.aPicks(row)
        val kept = node.aPicks(row)
        return Cell(kept, dropped, held, droppedWasEmpty, keptSawA)
    }

    private fun table(cells: Map<Row, Cell>): String =
        cells.entries.joinToString("\n") { (row, c) -> "  ${row.label}: kept ${c.kept}, dropped ${c.dropped}" }

    @Test
    fun aRestartedNodeKeepsCountingTheGrantsAWakerHolds() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val cells = rows.associateWith { measure(it) }
        assertAll(
            { assertEquals(rows.map { it.held }, cells.values.map { it.heldAtRestart }, "the rig: a holds its grants across the restart") },
            { assertEquals(true, cells.values.all { it.droppedWasEmpty }, "the rig: the restarted node has no wake memory") },
            { assertEquals(true, cells.values.all { it.keptSawA }, "the rig: the kept node has some") },
            { assertEquals(listOf(10, 6, 6, 6), cells.values.map { it.kept }, "the kept column\n${table(cells)}") },
            { assertEquals(listOf(10, 6, 6, 6), cells.values.map { it.dropped }, "the dropped column: level with the kept one, nothing forgiven\n${table(cells)}") },
        )
    }

    /** The node once `a`, still asking, has been granted [held] while `b` asked nothing. */
    private suspend fun TestScope.aRanOnWhileBIdled(held: Long): HeddleNode {
        val node = staticCluster(root, peers = 1, mint = mapOf(0 to MINT), topology = topology, quantum = QUANTUM).peers.single().node
        repeat(WARM_UP) { node.job() }
        // b stops asking; a is granted `held` and keeps asking for more.
        node.advertise(toP, Demand(targetOutstanding = node.outstanding(toP) + held, maximumUsefulGrant = held))
        assertEquals(ScheduleOutcome.Delegated(1), node.schedule(root), "the root tops p up by $held")
        node.advertise(toA, Demand(targetOutstanding = node.outstanding(toA) + 2 * held, maximumUsefulGrant = held))
        node.advertise(toB, Demand.NONE)
        assertEquals(ScheduleOutcome.Delegated(1), node.schedule(p), "a's grant of $held, b idle")
        return node
    }

    /**
     * The other history of the same ledger (#3721, ruling 3): `a` is the front, holding 8 it has
     * not spent, and `b` idled while it was granted, so `b`'s lag is one it should forfeit. Kept,
     * the node saw `b` leave and moves it by its parent's clock, level with `a`: 10 of 20. Dropped,
     * the new incarnation meets both for the first time, clamps `a` 8 past itself and `b` to the
     * front, and `b` keeps the 8 as lag: `a` takes 6.
     *
     * No clamp gets this row and the held-8 rows of [aRestartedNodeKeepsCountingTheGrantsAWakerHolds]
     * both right, since the restarted node cannot tell them apart; this one pins what the clamp
     * gives up, and that it is bounded. `b`'s 8 picks ahead are a gap of 8, every pick after them
     * alternating, so `2 · (kept − dropped)` is the gap, and it is at most `a`'s outstanding.
     */
    @Test
    fun aRunningFrontHoldingGrantsLeavesAnIdleSiblingAtMostThatLag() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val row = Row("running front holds 8, b idle", held = 8L, settle = Settle.NOTHING, cost = 0L)
        val node = aRanOnWhileBIdled(row.held)
        val outstanding = node.outstanding(toA)
        val fresh = restarted(node)
        val wasEmpty = fresh.allocatorMemoryForTest == AllocatorMemory.EMPTY
        val dropped = fresh.aPicks(row)
        val kept = node.aPicks(row)
        assertAll(
            { assertEquals(row.held, outstanding, "the rig: a holds its grant across the restart") },
            { assertTrue(wasEmpty, "the rig: the restarted node has no wake memory") },
            { assertEquals(10, kept, "kept: b comes back by its parent's clock, level with a") },
            { assertEquals(6, dropped, "dropped: b is clamped to the front a is clamped past, and keeps the gap") },
            { assertTrue(kept != dropped, "the two histories read apart: kept $kept, dropped $dropped") },
            { assertTrue(2L * (kept - dropped) <= outstanding, "the lag b keeps, ${2 * (kept - dropped)}, is at most a's outstanding, $outstanding") },
        )
    }

    private companion object {
        const val WARM_UP = 20
        const val ALONE = 4
        const val PICKS = 20
        const val QUANTUM = 8L
        const val MINT = 1_000L
    }
}
