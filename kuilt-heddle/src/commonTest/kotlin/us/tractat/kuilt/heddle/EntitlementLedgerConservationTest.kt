package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The load-bearing economics invariants (design §10.1 / §10.7): conservation across
 * randomized mutator sequences AND partial-delivery merges, and overspend refusal.
 *
 * A throwaway Python-style model already validated this math over 400 seeded runs;
 * this reproduces it as Kotlin property tests over the real [EntitlementLedger].
 */
class EntitlementLedgerConservationTest {

    // A fixed static ACTIVE tree:  root → g1 → g3(leaf), root → g2(leaf).
    private val root = GroupId("root")
    private val g1 = GroupId("g1")
    private val g2 = GroupId("g2")
    private val g3 = GroupId("g3")
    private val e1 = AttachmentId("e1") // root → g1
    private val e2 = AttachmentId("e2") // root → g2
    private val e3 = AttachmentId("e3") // g1  → g3
    private val replicas = listOf("alice", "bob", "carol").map(::ReplicaId)
    private val allGroups = listOf(root, g1, g2, g3)

    private fun topology(): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(
            e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L)),
            e2 to setOf(AttachmentRecord(e2, root, g2, Weight.ONE, 0L)),
            e3 to setOf(AttachmentRecord(e3, g1, g3, Weight.ONE, 0L)),
        ),
    )

    private fun seeded(mint: Long): EntitlementLedger =
        topology().piece(EntitlementLedger.bootstrap(root, mapOf(replicas[0] to mint), nonce = "genesis"))

    /** minted == Σ holdings + Σ leafSpent, over every group and replica. */
    private fun assertConservation(ledger: EntitlementLedger, minted: Long) {
        var sumHoldings = 0L
        for (g in allGroups) for (r in replicas) sumHoldings += ledger.holdings(g, r)
        assertEquals(minted, sumHoldings + ledger.leafSpentTotal(), "conservation broke")
        assertEquals(minted, ledger.mintedTotal(), "minted total drifted")
    }

    @Test
    fun conservationHoldsAcrossRandomizedMutatorSequences() {
        val rnd = Random(0xC0FFEE)
        repeat(80) {
            val minted = 1_000L
            var ledger = seeded(minted)
            assertConservation(ledger, minted)
            repeat(60) {
                val r = replicas.random(rnd)
                val patch = when (rnd.nextInt(5)) {
                    0 -> ledger.delegate(r, listOf(e1, e2, e3).random(rnd), rnd.nextLong(1L, 40L))
                    1 -> ledger.release(r, listOf(e1, e2, e3).random(rnd), rnd.nextLong(1L, 40L))
                    2 -> {
                        val to = replicas.filter { it != r }.random(rnd)
                        ledger.transfer(allGroups.random(rnd), r, to, rnd.nextLong(1L, 40L))
                    }
                    3 -> ledger.spend(r, listOf(g2, g3).random(rnd), rnd.nextLong(1L, 40L))
                    else -> ledger.spend(r, listOf(g2, g3).random(rnd), rnd.nextLong(1L, 40L))
                }
                if (patch != null) ledger = ledger.piece(patch)
                assertConservation(ledger, minted)
            }
            // No honest sequence produces an integrity conflict.
            assertTrue(ledger.validate().isEmpty(), "honest run flagged a conflict: ${ledger.validate()}")
        }
    }

    @Test
    fun conservationHoldsUnderPartialDeliveryMerges() {
        val rnd = Random(0xBEEF)
        repeat(60) {
            val minted = 500L
            val base = seeded(minted)
            // Two peers each apply an independent, individually-valid mutator to base,
            // then merge in both orders — conservation holds on every resulting state.
            val p1 = base.delegate(replicas[0], e1, rnd.nextLong(1L, 100L))
            val fundedG1 = if (p1 != null) base.piece(p1) else base
            val p2 = fundedG1.spend(replicas[0], g3, 0L) // no-op cancel; still valid
            val a = fundedG1.delegate(replicas[0], e3, 10L)
            val b = fundedG1.transfer(g1, replicas[0], replicas[1], 5L)
            val left = if (a != null) fundedG1.piece(a) else fundedG1
            val leftRight = if (b != null) left.piece(b) else left
            val right = if (b != null) fundedG1.piece(b) else fundedG1
            val rightLeft = if (a != null) right.piece(a) else right
            assertEquals(leftRight, rightLeft, "merge not order-independent")
            assertConservation(leftRight, minted)
            assertConservation(fundedG1, minted)
            if (p2 != null) assertConservation(fundedG1.piece(p2), minted)
        }
    }

    @Test
    fun overspendPastHoldingsRefusesAndLeavesStateUntouched() {
        val ledger = seeded(100L)
        // alice holds exactly 100 at root; nobody else holds anything.
        assertEquals(100L, ledger.holdings(root, replicas[0]))
        assertEquals(0L, ledger.holdings(root, replicas[1]))

        // delegate/transfer/spend beyond holdings all return null, state untouched.
        assertNull(ledger.delegate(replicas[0], e1, 101L))
        assertNull(ledger.transfer(root, replicas[0], replicas[1], 101L))
        assertNull(ledger.delegate(replicas[1], e1, 1L)) // bob holds nothing
        // release past a child's holdings (nothing delegated yet) refuses.
        assertNull(ledger.release(replicas[0], e1, 1L))

        // A leaf with no funding cannot spend.
        val funded = ledger.piece(ledger.delegate(replicas[0], e2, 30L)!!)
        assertNull(funded.spend(replicas[0], g2, 31L))
        // spending exactly holdings succeeds.
        assertTrue(funded.spend(replicas[0], g2, 30L) != null)
    }
}
