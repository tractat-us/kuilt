package us.tractat.kuilt.crdt

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differential regression test for #1207: the fixed, iterative production
 * traversal ([Fugue.toList], driven by the iterative
 * `sortChildrenRecursive`/`traverseSubtree`) must produce the exact same order
 * as the pre-#1207 recursive oracle ([Fugue.computeSequenceViaRecursiveOracle])
 * across many randomized, genuinely *bushy* trees — mixed left/right children,
 * with `rightOrigin` ties, so the right-sibling comparator's
 * "tiebreak by sender-id descending" branch is actually exercised.
 *
 * A plain linear chain — every node has exactly one child — cannot catch a
 * LIFO-stack push-order-reversal bug: every traversal order agrees once each
 * node has at most one child. This test is the complementary check:
 * [DeepChainStackSafetyTest] proves the rewrite doesn't crash on deep chains;
 * this proves it doesn't silently reorder bushy (multi-child) trees.
 */
class FugueBushyTreeOrderingDualTrackTest {

    @Test
    fun iterativeTraversalMatchesRecursiveOracleAcrossRandomBushyTrees() {
        for (seed in 0L until 200L) {
            val ops = randomBushyOps(random = Random(seed), nodeCount = 300, replicaCount = 5, maxDepth = 6)
            val fugue = Fugue.fromOps(ops, lamport = ops.size.toLong())

            val production = fugue.toList()
            val oracle = fugue.computeSequenceViaRecursiveOracle()

            assertEquals(oracle, production, "Divergence at seed=$seed")
        }
    }

    /**
     * Builds a randomized, shallow (depth capped at [maxDepth]), high-fanout
     * tree: each new op's `parent` is drawn from existing ids whose depth is
     * still under the cap (falling back to [FugueId.HEAD]), with a random
     * left/right [FugueSide] — many ops share the same parent and side, so a
     * node commonly has several left AND several right children (a bushy
     * tree, not a chain). `rightOrigin` is frequently repeated across distinct
     * right-siblings to force the "same rightOrigin" tiebreak-by-sender-id
     * path in the right-sibling comparator, and otherwise drawn from an
     * arbitrary existing id to exercise the general structural comparison.
     *
     * This does not reproduce [Fugue.insertAt]'s actual placement algorithm —
     * it only needs *valid* (id-referencing, acyclic) trees to stress the
     * sort/traverse rewrite; realistic insertion semantics are already covered
     * by the existing [FugueTest] suite.
     */
    private fun randomBushyOps(
        random: Random,
        nodeCount: Int,
        replicaCount: Int,
        maxDepth: Int,
    ): Set<FugueOp.Insert<Int>> {
        val replicas = (0 until replicaCount).map { ReplicaId("R$it") }
        val depthOf = mutableMapOf(FugueId.HEAD to 0)
        val allIds = mutableListOf(FugueId.HEAD)
        val usedRightOrigins = mutableListOf<FugueId?>()
        val ops = mutableListOf<FugueOp.Insert<Int>>()

        for (i in 0 until nodeCount) {
            val candidates = allIds.filter { (depthOf[it] ?: 0) < maxDepth }
            val parent = if (candidates.isNotEmpty()) candidates.random(random) else FugueId.HEAD
            val replica = replicas.random(random)
            // Strictly increasing lamport: buildTree's topological sort requires a
            // parent's lamport to precede its child's, which this trivially satisfies
            // since `parent` is always drawn from ids generated in earlier iterations.
            val id = FugueId(lamport = (i + 1).toLong(), replicaId = replica, seq = (i + 1).toLong())
            val side = if (random.nextBoolean()) FugueSide.Left else FugueSide.Right
            val rightOrigin = if (side == FugueSide.Right) randomRightOrigin(random, allIds, usedRightOrigins) else null

            ops += FugueOp.Insert(id = id, value = i, parent = parent, side = side, rightOrigin = rightOrigin)
            allIds += id
            depthOf[id] = (depthOf[parent] ?: 0) + 1
            if (side == FugueSide.Right) usedRightOrigins += rightOrigin
        }
        return ops.toSet()
    }

    private fun randomRightOrigin(
        random: Random,
        allIds: List<FugueId>,
        usedRightOrigins: List<FugueId?>,
    ): FugueId? = when {
        usedRightOrigins.isNotEmpty() && random.nextInt(0, 10) < 4 -> usedRightOrigins.random(random)
        random.nextBoolean() -> null
        else -> allIds.random(random)
    }
}
