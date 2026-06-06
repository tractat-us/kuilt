package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoundedCounterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private fun apply(state: BoundedCounter, patch: Patch<BoundedCounter>): BoundedCounter =
        state.piece(patch)

    @Test
    fun initSetsPerReplicaQuotas() {
        val bc = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        assertEquals(10L, bc.totalBudget)
        assertEquals(0L, bc.totalSpent)
        assertEquals(5L, bc.quota(a))
        assertEquals(5L, bc.quota(b))
        // unknown replica has zero quota — not negative
        assertEquals(0L, bc.quota(ReplicaId("nobody")))
    }

    @Test
    fun initRejectsNegativeQuota() {
        assertFailsWith<IllegalArgumentException> { BoundedCounter.init(mapOf(a to -1L)) }
    }

    @Test
    fun trySpendWithinQuotaProducesADeltaThatDebitsTheReplica() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        val delta = bc.trySpend(a, 3L)
        assertNotNull(delta)
        val next = apply(bc, delta)
        assertEquals(2L, next.quota(a))
        assertEquals(3L, next.totalSpent)
        assertEquals(2L, next.totalBudget) // 5 received - 3 spent
    }

    @Test
    fun trySpendOverQuotaIsDenied() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        assertNull(bc.trySpend(a, 6L))
        // and the state is unchanged
        assertEquals(5L, bc.quota(a))
        assertEquals(0L, bc.totalSpent)
    }

    @Test
    fun trySpendMustBePositive() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        assertFailsWith<IllegalArgumentException> { bc.trySpend(a, 0L) }
    }

    @Test
    fun trySpendDefaultsToOne() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        val delta = bc.trySpend(a)
        assertNotNull(delta)
        assertEquals(4L, apply(bc, delta).quota(a))
    }

    @Test
    fun reproducesTheSlideOnePnCounterBug_butDeniesInstead() {
        // Budget 10 split 5/5. With a plain PN-counter, two independent 7-spends
        // would converge to a balance of -4. With BoundedCounter, each 7-spend
        // is denied at its own replica — invariant held.
        val start = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val aliceBranch = start  // alice's local view
        val bobBranch = start    // bob's local view
        assertNull(aliceBranch.trySpend(a, 7L))   // denied — only 5 quota
        assertNull(bobBranch.trySpend(b, 7L))     // denied — only 5 quota
        // (No deltas → state unchanged; merge is trivial; total spent stays 0.)
    }

    @Test
    fun bothPeersSpendingTheirFullQuotaConvergesToZeroBalanceNotOverdraw() {
        val start = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val alicePatch = start.trySpend(a, 5L)!!
        val bobPatch = start.trySpend(b, 5L)!!
        // merge order doesn't matter — laws guarantee that
        val merged = apply(start, alicePatch).piece(apply(start, bobPatch))
        assertEquals(10L, merged.totalSpent)
        assertEquals(0L, merged.totalBudget)
        // and any further spend on either is denied
        assertNull(merged.trySpend(a, 1L))
        assertNull(merged.trySpend(b, 1L))
    }

    @Test
    fun transferMovesQuotaFromSenderToReceiver() {
        val bc = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val delta = bc.transfer(from = a, to = b, amount = 3L)
        assertNotNull(delta)
        val next = apply(bc, delta)
        assertEquals(2L, next.quota(a)) // 5 - 3
        assertEquals(8L, next.quota(b)) // 5 + 3
        assertEquals(10L, next.totalBudget) // unchanged — it's just redistribution
        assertEquals(0L, next.totalSpent)   // transfers do not bump spent
    }

    @Test
    fun transferOverSenderQuotaIsDenied() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        assertNull(bc.transfer(from = a, to = b, amount = 6L))
    }

    @Test
    fun transferToSelfIsRejected() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        assertFailsWith<IllegalArgumentException> { bc.transfer(from = a, to = a, amount = 1L) }
    }

    @Test
    fun transferMustBePositive() {
        val bc = BoundedCounter.init(mapOf(a to 5L))
        assertFailsWith<IllegalArgumentException> { bc.transfer(from = a, to = b, amount = 0L) }
    }

    @Test
    fun afterTransferTheAccountingMatches() {
        val bc = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val transferred = apply(bc, bc.transfer(a, b, 3L)!!)
        val final = apply(transferred, transferred.trySpend(b, 8L)!!)
        assertEquals(2L, final.quota(a))
        assertEquals(0L, final.quota(b))
        assertEquals(8L, final.totalSpent)  // only consumption; transfers conserved
        assertEquals(2L, final.totalBudget) // 10 initial - 8 spent
    }

    @Test
    fun mergeIsTheProductOfTheTwoGCounters() {
        // Two replicas applied each its own spend; merging combines via elementwise max
        val start = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val aliceLocal = apply(start, start.trySpend(a, 2L)!!)
        val bobLocal = apply(start, start.trySpend(b, 3L)!!)
        val merged = aliceLocal.piece(bobLocal)
        assertEquals(3L, merged.quota(a)) // 5-2
        assertEquals(2L, merged.quota(b)) // 5-3
        assertEquals(5L, merged.totalSpent)
    }

    @Test
    fun roundTripsThroughJson() {
        val bc = apply(
            BoundedCounter.init(mapOf(a to 5L, b to 5L)),
            BoundedCounter.init(mapOf(a to 5L, b to 5L)).trySpend(a, 2L)!!,
        )
        val encoded = Json.encodeToString(BoundedCounter.serializer(), bc)
        assertEquals(bc, Json.decodeFromString(BoundedCounter.serializer(), encoded))
    }

    @Test
    fun concurrentMultiDonorTransfersConverge() {
        // Alice and Charles each hold 5 quota; both concurrently transfer 3 to Bob.
        // The 2D matrix preserves both transfers — Bob ends with 6 new quota; donors keep 2 each.
        // The 1D model (received: GCounter) collides on `received[bob]` and silently loses one transfer.
        val a = ReplicaId("A"); val c = ReplicaId("C"); val b = ReplicaId("B")
        val start = BoundedCounter.init(mapOf(a to 5L, c to 5L))

        val aliceBranch = start.piece(start.transfer(from = a, to = b, amount = 3L)!!)
        val charlesBranch = start.piece(start.transfer(from = c, to = b, amount = 3L)!!)
        val merged = aliceBranch.piece(charlesBranch)

        assertEquals(6L, merged.quota(b))           // both 3-transfers survived
        assertEquals(2L, merged.quota(a))
        assertEquals(2L, merged.quota(c))
        assertEquals(10L, merged.totalBudget)       // 10 initial, 0 spent — transfers conserved
        assertEquals(0L, merged.totalSpent)         // no consumption yet
    }
}
