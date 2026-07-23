package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementLedgerTest {

    private val root = GroupId("root")
    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val e1 = AttachmentId("e1")

    @Test
    fun zeroIsEmptyAndMergesAsIdentity() {
        val z = EntitlementLedger.ZERO
        assertNull(z.edge(e1))
        assertTrue(z.activeChildren(root).isEmpty())
        assertEquals(z, z.piece(z))
    }

    @Test
    fun bootstrapRecordsMintedSupplyAndMergesConsistently() {
        val a = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 50L))
        val b = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 50L))
        // Same supply observed independently on two peers converges to itself.
        assertEquals(a, a.piece(b))
        assertEquals(a, b.piece(a))
    }

    @Test
    fun edgeReturnsNullForUnknownAndSummaryForKnown() {
        val ledger = EntitlementLedger.of(
            records = mapOf(e1 to AttachmentRecord(e1, root, GroupId("child"), Weight.ONE, 0L)),
            issued = mapOf(e1 to GCounter.of(alice to 10L)),
            returned = mapOf(e1 to GCounter.of(alice to 3L)),
            leafSpent = mapOf(e1 to GCounter.of(alice to 2L)),
            rollupSpent = mapOf(e1 to GCounter.of(alice to 4L)),
        )
        assertNull(ledger.edge(AttachmentId("nope")))
        val summary = ledger.edge(e1)
        assertEquals(EdgeSummary(e1, issued = 10L, returned = 3L, spent = 6L), summary)
        // derived views
        assertEquals(1L, summary?.outstanding) // 10 - 3 - 6
        assertEquals(7L, summary?.committedService) // 10 - 3
    }

    @Test
    fun activeChildrenReturnsParentEdgesInDeterministicOrder() {
        val child = GroupId("child")
        val eB = AttachmentId("b")
        val eA = AttachmentId("a")
        val ledger = EntitlementLedger.of(
            records = mapOf(
                eB to AttachmentRecord(eB, root, child, Weight.ONE, 0L),
                eA to AttachmentRecord(eA, root, child, Weight.ONE, 0L),
            ),
            issued = mapOf(eA to GCounter.of(alice to 5L), eB to GCounter.of(alice to 7L)),
        )
        val kids = ledger.activeChildren(root)
        assertEquals(listOf(AttachmentId("a"), AttachmentId("b")), kids.map { it.attachment })
        assertTrue(ledger.activeChildren(GroupId("elsewhere")).isEmpty())
    }

    @Test
    fun perEdgeSlotsMergeByMaxAcrossReplicas() {
        // alice and bob each spend on their own slot of the same edge; merge keeps both.
        val onAlice = EntitlementLedger.of(leafSpent = mapOf(e1 to GCounter.of(alice to 4L)))
        val onBob = EntitlementLedger.of(leafSpent = mapOf(e1 to GCounter.of(bob to 6L)))
        val merged = onAlice.piece(onBob)
        assertEquals(10L, merged.edge(e1)?.spent)
    }

    @Test
    fun roundTripsThroughJson() {
        val ledger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L)).piece(
            EntitlementLedger.of(
                records = mapOf(e1 to AttachmentRecord(e1, root, GroupId("child"), Weight.of(3, 1), 12L)),
                issued = mapOf(e1 to GCounter.of(alice to 40L)),
                transfers = mapOf(PathKey.of(e1) to mapOf(alice to GCounter.of(bob to 5L))),
            ),
        )
        val encoded = Json.encodeToString(EntitlementLedger.serializer(), ledger)
        assertEquals(ledger, Json.decodeFromString(EntitlementLedger.serializer(), encoded))
    }
}
