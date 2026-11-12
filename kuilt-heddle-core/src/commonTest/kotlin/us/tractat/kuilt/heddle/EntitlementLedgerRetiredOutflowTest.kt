package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A retired child that served still counts against its parent (#3173, plan Task L0).
 *
 * Retirement needs outstanding 0, so a served child retires with `issued − returned = spent > 0`:
 * that net inflow left the parent and was charged below it. [EntitlementLedger.holdings] of the
 * parent must keep subtracting it, so a ledger that stops scanning retired children has to fold
 * them, never skip them. Skipping would hand the parent back every unit its retired children spent,
 * and conservation (`Σ holdings + spent == minted`) is what catches that.
 */
class EntitlementLedgerRetiredOutflowTest {

    private val alice = ReplicaId("alice")
    private val root = GroupId("root")
    private val lane = GroupId("lane")
    private val laneEdge = AttachmentRecord(AttachmentId("root>lane"), root, lane, Weight.ONE)

    private fun child(k: Int, prefix: String) =
        AttachmentRecord(AttachmentId("lane>$prefix-$k"), lane, GroupId("lane/$prefix-$k"), Weight.ONE)

    private val served = (0 until SERVED).map { child(it, "gone") }
    private val open = (0 until OPEN).map { child(it, "open") }

    private fun EntitlementLedger.then(patch: (EntitlementLedger) -> Patch<EntitlementLedger>?): EntitlementLedger =
        piece(checkNotNull(patch(this)) { "a step of the fixture was refused" }.delta)

    /** Served children first: each takes `k + 2`, spends `k + 1`, returns 1, closes and retires. */
    private fun history(): List<EntitlementLedger> {
        val states = ArrayList<EntitlementLedger>()
        var l = EntitlementLedger.bootstrap(root, mapOf(alice to MINTED), nonce = "genesis")
            .then { it.prepare(laneEdge) }
            .then { it.activate(laneEdge.id) }
            .then { it.delegate(alice, laneEdge.id, TO_LANE) }
        states += l
        for ((k, c) in served.withIndex()) {
            l = l.then { it.prepare(c) }
                .then { it.activate(c.id) }
                .then { it.delegate(alice, c.id, k + 2L) }
                .then { it.spendCaptured(alice, listOf(laneEdge.id, c.id), k + 1L) }
                .then { it.release(alice, c.id, 1L) }
                .then { it.close(c.id) }
                .then { it.retire(c.id) }
            states += l
        }
        for (c in open) {
            l = l.then { it.prepare(c) }
                .then { it.activate(c.id) }
                .then { it.delegate(alice, c.id, 5L) }
                .then { it.spendCaptured(alice, listOf(laneEdge.id, c.id), 2L) }
            states += l
        }
        return states
    }

    private val groups = listOf(root, lane) + (served + open).map { it.child }

    private fun assertConserves(l: EntitlementLedger, label: String) {
        val held = groups.sumOf { l.holdings(it, alice) }
        assertAll(
            { assertEquals(l.mintedTotal(), held + l.leafSpentTotal(), "$label: Σ holdings + spent == minted") },
            { assertEquals(emptyList(), l.validate(), "$label: validate") },
        )
    }

    @Test
    fun servedThenRetiredChildrenStillCountAgainstTheirParent() {
        val states = history()
        val last = states.last()
        val servedSpend = (0 until SERVED).sumOf { it + 1L }
        val decoded = Json.decodeFromString(
            EntitlementLedger.serializer(),
            Json.encodeToString(EntitlementLedger.serializer(), last),
        )
        val partial = states[states.size / 2]
        assertAll(
            // The rig fired: every served child is retired and carries its spend as net inflow.
            {
                for ((k, c) in served.withIndex()) {
                    assertEquals(Lifecycle.RETIRED, last.lifecycle(c.id), "${c.id.value} retired")
                    assertEquals(k + 1L, last.edge(c.id)?.spent, "${c.id.value} carries its spend")
                    assertEquals(0L, last.edge(c.id)?.outstanding, "${c.id.value} drained")
                }
            },
            {
                assertEquals(
                    TO_LANE - servedSpend - OPEN * 5L,
                    last.holdings(lane, alice),
                    "the lane is short exactly its retired children's spend and its open children's grants",
                )
            },
            { for (c in served) assertEquals(listOf(c.id), last.retiredInboundEdges(c.child), "${c.child} retired inbound") },
            { for ((i, s) in states.withIndex()) assertConserves(s, "step $i") },
            { assertConserves(decoded, "after a JSON round trip") },
            { assertConserves(last.piece(partial), "last ⊔ partial") },
            { assertConserves(partial.piece(last), "partial ⊔ last") },
            { assertTrue(servedSpend > 0L, "the served population has spend to lose") },
        )
    }

    private companion object {
        const val SERVED = 8
        const val OPEN = 3
        const val MINTED = 1_000L
        const val TO_LANE = 300L
    }
}
