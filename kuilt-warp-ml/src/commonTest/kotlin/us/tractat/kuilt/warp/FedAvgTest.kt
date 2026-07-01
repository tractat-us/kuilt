package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Lattice-law and convergence tests for [FedAvg].
 *
 * These tests prove the CRDT contract holds under adversarial delivery: any ordering,
 * duplication, or gap-and-redeliver of contributions converges to the same federated
 * average. No networking or Seam is required — convergence here is purely join-semilattice
 * math.
 */
class FedAvgTest {

    // ── Hand-checked numeric example ─────────────────────────────────────────────

    @Test
    fun `hand-checked numeric example — two peers converge to known average`() {
        // Peer A: 1 sample, local weight 10.0 → contributes (n=1, weighted=[10.0])
        // Peer B: 3 samples, local weight  2.0 → contributes (n=3, weighted=[6.0])
        // average[0] = (1·10 + 3·2) / (1 + 3) = 16 / 4 = 4.0
        val peerA = ReplicaId("alice")
        val peerB = ReplicaId("bob")

        val contribA = FedAvg.contribution(peerA, sampleCount = 1L, localWeights = listOf(10.0))
        val contribB = FedAvg.contribution(peerB, sampleCount = 3L, localWeights = listOf(2.0))

        val merged = FedAvg.ZERO.piece(contribA).piece(contribB)

        assertEquals(4.0, merged.weights[0], absoluteTolerance = 1e-9)
    }

    @Test
    fun `hand-checked multi-dimensional example`() {
        // Peer A: n=2, w=[1.0, 4.0] → weighted=[2.0, 8.0]
        // Peer B: n=3, w=[3.0, 2.0] → weighted=[9.0, 6.0]
        // average[0] = (2 + 9) / (2 + 3) = 11/5 = 2.2
        // average[1] = (8 + 6) / (2 + 3) = 14/5 = 2.8
        val peerA = ReplicaId("a")
        val peerB = ReplicaId("b")

        val merged = FedAvg.ZERO
            .piece(FedAvg.contribution(peerA, sampleCount = 2L, localWeights = listOf(1.0, 4.0)))
            .piece(FedAvg.contribution(peerB, sampleCount = 3L, localWeights = listOf(3.0, 2.0)))

        assertAll(
            { assertEquals(2.2, merged.weights[0], absoluteTolerance = 1e-9) },
            { assertEquals(2.8, merged.weights[1], absoluteTolerance = 1e-9) },
        )
    }

    // ── Lattice laws ─────────────────────────────────────────────────────────────

    @Test
    fun `piece is idempotent`() {
        val peer = ReplicaId("solo")
        val contrib = FedAvg.contribution(peer, sampleCount = 5L, localWeights = listOf(1.0, 2.0, 3.0))

        val once = FedAvg.ZERO.piece(contrib)
        val twice = once.piece(contrib)

        assertEquals(once, twice)
    }

    @Test
    fun `piece is commutative`() {
        val peerA = ReplicaId("a")
        val peerB = ReplicaId("b")
        val contribA = FedAvg.contribution(peerA, sampleCount = 2L, localWeights = listOf(1.0, 3.0))
        val contribB = FedAvg.contribution(peerB, sampleCount = 4L, localWeights = listOf(5.0, 7.0))

        val ab = FedAvg.ZERO.piece(contribA).piece(contribB)
        val ba = FedAvg.ZERO.piece(contribB).piece(contribA)

        assertEquals(ab, ba)
    }

    @Test
    fun `piece is associative`() {
        val peerA = ReplicaId("a")
        val peerB = ReplicaId("b")
        val peerC = ReplicaId("c")
        val contribA = FedAvg.contribution(peerA, sampleCount = 1L, localWeights = listOf(10.0))
        val contribB = FedAvg.contribution(peerB, sampleCount = 2L, localWeights = listOf(20.0))
        val contribC = FedAvg.contribution(peerC, sampleCount = 3L, localWeights = listOf(30.0))

        val leftGrouped = FedAvg.ZERO.piece(contribA.piece(contribB)).piece(contribC)
        val rightGrouped = FedAvg.ZERO.piece(contribA).piece(contribB.piece(contribC))

        assertEquals(leftGrouped, rightGrouped)
    }

    @Test
    fun `ZERO is the identity element`() {
        val peer = ReplicaId("p")
        val contrib = FedAvg.contribution(peer, sampleCount = 7L, localWeights = listOf(3.14))

        assertAll(
            { assertEquals(contrib, FedAvg.ZERO.piece(contrib)) },
            { assertEquals(contrib, contrib.piece(FedAvg.ZERO)) },
        )
    }

    // ── Same-peer, same-epoch collision: commutativity must hold ─────────────────
    //
    // If a peer retrained within the same round and rebroadcast a different slot,
    // the join must still be commutative and associative — replicas must not diverge
    // depending on delivery order. Fix: max over a total order on slot content.

    @Test
    fun `piece is commutative when same peer contributes same epoch different content`() {
        val peer = ReplicaId("p")
        // Both contributions use the default epoch=1; the second has a larger sampleCount.
        val slotA = FedAvg.contribution(peer, sampleCount = 1L, localWeights = listOf(1.0))
        val slotB = FedAvg.contribution(peer, sampleCount = 2L, localWeights = listOf(9.0))

        val ab = slotA.piece(slotB)
        val ba = slotB.piece(slotA)

        assertEquals(ab, ba, "same-peer same-epoch collision: a.piece(b) must equal b.piece(a)")
    }

    @Test
    fun `piece is associative when same peer contributes same epoch different content`() {
        val peer = ReplicaId("p")
        val slotA = FedAvg.contribution(peer, sampleCount = 1L, localWeights = listOf(1.0))
        val slotB = FedAvg.contribution(peer, sampleCount = 2L, localWeights = listOf(9.0))
        val slotC = FedAvg.contribution(peer, sampleCount = 3L, localWeights = listOf(5.0))

        val left = slotA.piece(slotB).piece(slotC)
        val right = slotA.piece(slotB.piece(slotC))

        assertEquals(left, right, "same-peer same-epoch collision: (a⊔b)⊔c must equal a⊔(b⊔c)")
    }

    // ── Convergence under adversarial delivery ───────────────────────────────────

    @Test
    fun `convergence — N peers delivered in random orders with duplicates produce identical average`() {
        val rng = Random(seed = 0xF1_CAFE)
        val peers = (1..6).map { ReplicaId("peer$it") }
        val dimension = 4

        // Generate random contributions for each peer.
        val contributions = peers.map { peer ->
            val n = rng.nextLong(1L, 100L)
            val weights = List(dimension) { rng.nextDouble(-10.0, 10.0) }
            FedAvg.contribution(peer, sampleCount = n, localWeights = weights)
        }

        // Direct reference: join in order.
        val reference = contributions.fold(FedAvg.ZERO) { acc, c -> acc.piece(c) }
        val expectedWeights = reference.weights

        // Build 5 independent replicas, each with a different adversarial delivery order
        // (shuffled, with duplicates and dropped-then-redelivered frames).
        repeat(5) { replicaIdx ->
            val shuffled = contributions.shuffled(Random(rng.nextInt()))
            // Include duplicates: add every contribution twice, plus the first again.
            val adversarial = shuffled + shuffled + listOf(shuffled.first())
            val replica = adversarial.fold(FedAvg.ZERO) { acc, c -> acc.piece(c) }
            val actualWeights = replica.weights

            actualWeights.forEachIndexed { i, actual ->
                assertEquals(
                    expected = expectedWeights[i],
                    actual = actual,
                    absoluteTolerance = 1e-9,
                    message = "replica $replicaIdx, dimension $i diverged",
                )
            }
        }
    }

    @Test
    fun `embroider through CoordinationFree converges identically`() {
        val rng = Random(seed = 0xB3CAFE)
        val peers = (1..4).map { ReplicaId("w$it") }

        val coordFreeContribs = peers.map { peer ->
            val n = rng.nextLong(1L, 50L)
            val w = List(2) { rng.nextDouble(0.0, 5.0) }
            CoordinationFree(FedAvg.contribution(peer, n, w))
        }

        val merged = coordFreeContribs.reduce { acc, c -> acc.embroider(c) }
        val reversed = coordFreeContribs.reversed().reduce { acc, c -> acc.embroider(c) }

        assertAll(
            // Order-independence
            { assertEquals(merged.state, reversed.state) },
            // Idempotent via embroider
            {
                val doubleApplied = coordFreeContribs.fold(merged) { acc, c -> acc.embroider(c) }
                assertEquals(merged.state, doubleApplied.state)
            },
        )
    }

    // ── Zero-total-count guard ────────────────────────────────────────────────────

    @Test
    fun `weights on ZERO throws — no contributions means no valid average`() {
        assertFailsWith<IllegalStateException> {
            FedAvg.ZERO.weights
        }
    }

    // ── Factory preconditions ─────────────────────────────────────────────────────

    @Test
    fun `contribution with zero sampleCount throws`() {
        assertFailsWith<IllegalArgumentException> {
            FedAvg.contribution(ReplicaId("bad"), sampleCount = 0L, localWeights = listOf(1.0))
        }
    }

    @Test
    fun `contribution with negative sampleCount throws`() {
        assertFailsWith<IllegalArgumentException> {
            FedAvg.contribution(ReplicaId("bad"), sampleCount = -1L, localWeights = listOf(1.0))
        }
    }

    // ── Distinct peers stay distinct ──────────────────────────────────────────────

    @Test
    fun `contributions from different peers accumulate independently`() {
        val peerA = ReplicaId("a")
        val peerB = ReplicaId("b")
        val contribA = FedAvg.contribution(peerA, sampleCount = 1L, localWeights = listOf(100.0))
        val contribB = FedAvg.contribution(peerB, sampleCount = 1L, localWeights = listOf(0.0))

        val merged = FedAvg.ZERO.piece(contribA).piece(contribB)

        assertAll(
            // average = (100 + 0) / 2 = 50
            { assertEquals(50.0, merged.weights[0], absoluteTolerance = 1e-9) },
            // Contribution A alone would give 100, not 50
            { assertNotEquals(contribA, merged) },
        )
    }
}
