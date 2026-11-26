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
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * MEASURING.
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
    fun aRestartedNodeForgivesTheGrantsAWakerHolds() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val cells = rows.associateWith { measure(it) }
        assertAll(
            { assertEquals(rows.map { it.held }, cells.values.map { it.heldAtRestart }, "the rig: a holds its grants across the restart") },
            { assertEquals(true, cells.values.all { it.droppedWasEmpty }, "the rig: the restarted node has no wake memory") },
            { assertEquals(true, cells.values.all { it.keptSawA }, "the rig: the kept node has some") },
            { assertNotEquals(-1, cells.size) },
            { assertEquals(emptyList(), cells.values.map { it.kept to it.dropped }, table(cells)) },
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
