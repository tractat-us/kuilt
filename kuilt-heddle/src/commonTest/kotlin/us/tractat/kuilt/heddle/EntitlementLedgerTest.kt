package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        val a = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 50L), nonce = "genesis")
        val b = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 50L), nonce = "genesis")
        // The same mint act (same nonce) observed independently on two peers converges to itself.
        assertEquals(a, a.piece(b))
        assertEquals(a, b.piece(a))
    }

    @Test
    fun distinctMintActsUnionRatherThanCollide() {
        // Two independent acts crediting the SAME holder must both survive (fix 4):
        // a lost mint would be a conservation break the moment holdings derive from it.
        val first = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "act-1")
        val second = EntitlementLedger.bootstrap(root, mapOf(alice to 40L), nonce = "act-2")
        val merged = first.piece(second)
        // Distinct MintIds ⇒ the union carries BOTH mints, so it equals neither operand.
        // (A per-(root,holder) key would max-collide to `first` and silently drop act-2.)
        assertNotEquals(first, merged)
        assertNotEquals(second, merged)
        // ...and the union is still order-independent.
        assertEquals(merged, second.piece(first))
    }

    @Test
    fun edgeReturnsNullForUnknownAndSummaryForKnown() {
        val ledger = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, GroupId("child"), Weight.ONE))),
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
                eB to setOf(AttachmentRecord(eB, root, child, Weight.ONE)),
                eA to setOf(AttachmentRecord(eA, root, child, Weight.ONE)),
            ),
            issued = mapOf(eA to GCounter.of(alice to 5L), eB to GCounter.of(alice to 7L)),
        )
        val kids = ledger.activeChildren(root)
        assertEquals(listOf(AttachmentId("a"), AttachmentId("b")), kids.map { it.attachment })
        assertTrue(ledger.activeChildren(GroupId("elsewhere")).isEmpty())
    }

    @Test
    fun divergentRecordsUnderOneIdAreBothRetained() {
        // Same edge id, two different parent pointers on two peers. The merge must NOT
        // pick a winner (LWW on a parent pointer is forbidden, §5.2): both survive so a
        // later phase can flag RecordDivergence. Observable here because the edge then
        // appears as a child of BOTH parents.
        val leftParent = GroupId("left")
        val rightParent = GroupId("right")
        val onLeft = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, leftParent, GroupId("c"), Weight.ONE))),
        )
        val onRight = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, rightParent, GroupId("c"), Weight.ONE))),
        )
        val merged = onLeft.piece(onRight)
        assertEquals(listOf(e1), merged.activeChildren(leftParent).map { it.attachment })
        assertEquals(listOf(e1), merged.activeChildren(rightParent).map { it.attachment })
        // order-independent
        assertEquals(merged, onRight.piece(onLeft))
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
        val ledger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "genesis").piece(
            EntitlementLedger.of(
                records = mapOf(e1 to setOf(AttachmentRecord(e1, root, GroupId("child"), Weight.of(3, 1)))),
                issued = mapOf(e1 to GCounter.of(alice to 40L)),
                transfers = mapOf(PathKey.of(e1) to mapOf(alice to GCounter.of(bob to 5L))),
            ),
        )
        val encoded = Json.encodeToString(EntitlementLedger.serializer(), ledger)
        assertEquals(ledger, Json.decodeFromString(EntitlementLedger.serializer(), encoded))
    }
}
