package us.tractat.kuilt.crdt

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differential regression test for #1206: the fixed, iterative production
 * traversal ([Rga.toList], driven by the iterative [Rga.appendChildren]) must
 * produce the exact same order as the pre-#1206 recursive oracle
 * ([Rga.computeSequenceViaRecursiveOracle]) across many randomized, genuinely
 * *bushy* trees — concurrent inserts sharing an `after`, exercising the
 * descending-id sibling tiebreak documented on [Rga.computeSequence].
 *
 * A plain linear chain — every node has exactly one child — cannot catch a
 * LIFO-stack push-order-reversal bug: every traversal order agrees once each
 * node has at most one child. This test is the complementary check:
 * [DeepChainStackSafetyTest] proves the rewrite doesn't crash on deep chains;
 * this proves it doesn't silently reorder bushy (multi-child) trees.
 */
class RgaBushyTreeOrderingDualTrackTest {

    @Test
    fun iterativeTraversalMatchesRecursiveOracleAcrossRandomBushyTrees() {
        for (seed in 0L until 200L) {
            val ops = randomBushyOps(random = Random(seed), nodeCount = 300, replicaCount = 5, maxDepth = 6)
            val rga = Rga.fromOps(ops, lamport = ops.size.toLong())

            val production = rga.toList()
            val oracle = rga.computeSequenceViaRecursiveOracle()

            assertEquals(oracle, production, "Divergence at seed=$seed")
        }
    }

    /**
     * Builds a randomized, shallow (depth capped at [maxDepth]), high-fanout
     * insertion tree: each new op's `after` is drawn from existing ids whose
     * depth is still under the cap (falling back to [RgaId.HEAD]), so many ops
     * share the same `after` — a bushy tree, not a chain. Lamports are
     * sometimes forced to tie with an existing sibling under the same `after`
     * to exercise the concurrent-insert descending-id tiebreak ([RgaId] sorts
     * by lamport, then replica id).
     */
    private fun randomBushyOps(
        random: Random,
        nodeCount: Int,
        replicaCount: Int,
        maxDepth: Int,
    ): Set<RgaOp.Insert<Int>> {
        val replicas = (0 until replicaCount).map { ReplicaId("R$it") }
        val depthOf = mutableMapOf(RgaId.HEAD to 0)
        val allIds = mutableListOf(RgaId.HEAD)
        val siblingLamportsByAfter = mutableMapOf<RgaId, MutableList<Long>>()
        val ops = mutableListOf<RgaOp.Insert<Int>>()

        for (i in 0 until nodeCount) {
            val candidates = allIds.filter { (depthOf[it] ?: 0) < maxDepth }
            val after = if (candidates.isNotEmpty()) candidates.random(random) else RgaId.HEAD
            val replica = replicas.random(random)
            val existingSiblingLamports = siblingLamportsByAfter[after]
            val lamport = if (!existingSiblingLamports.isNullOrEmpty() && random.nextBoolean()) {
                existingSiblingLamports.random(random)
            } else {
                (i + 1).toLong()
            }

            val id = RgaId(lamport = lamport, replicaId = replica, seq = (i + 1).toLong())
            ops += RgaOp.Insert(id = id, value = i, after = after)
            allIds += id
            depthOf[id] = (depthOf[after] ?: 0) + 1
            siblingLamportsByAfter.getOrPut(after) { mutableListOf() }.add(lamport)
        }
        return ops.toSet()
    }
}
