package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stableCut(vararg pairs: Pair<ReplicaId, Long>): VersionVector =
    VersionVector.of(mapOf(*pairs))

private fun frontier(vararg pairs: Pair<ReplicaId, Long>): VersionVector =
    VersionVector.of(mapOf(*pairs))

/**
 * Tests for [MovableTree] — the Kleppmann move-op replicated tree.
 *
 * Merge law tests validate the three semilattice laws. Algorithm-specific tests
 * confirm concurrent-move convergence and cycle prevention.
 */
class MovableTreeTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    // ── Basic tree operations ─────────────────────────────────────────────────

    @Test
    fun rootIsAlwaysPresent() {
        val tree = MovableTree.empty<String>()
        assertTrue(tree.contains(MovableTree.ROOT_ID))
    }

    @Test
    fun addNodeUnderRoot() {
        val tree = MovableTree.empty<String>()
        val (next, _) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "docs")
        assertTrue(next.contains("docs:alice:1"))
        assertEquals(MovableTree.ROOT_ID, next.parentOf("docs:alice:1"))
    }

    @Test
    fun moveNodeToNewParent() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        assertEquals(idB, t3.parentOf(idA))
    }

    @Test
    fun childrenOf() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = idA, value = "B")
        val (t3, idC) = t2.addNode(alice, ts = 3L, parent = idA, value = "C")
        assertEquals(setOf(idB, idC), t3.childrenOf(idA))
    }

    @Test
    fun cannotMoveRoot() {
        val tree = MovableTree.empty<String>()
        val (_, patch) = tree.move(alice, ts = 1L, node = MovableTree.ROOT_ID, newParent = MovableTree.ROOT_ID)
        // Applying a move of root is a no-op — root stays without parent.
        val applied = tree.piece(patch)
        assertNull(applied.parentOf(MovableTree.ROOT_ID))
    }

    @Test
    fun parentOfRootIsNull() {
        // Root has no parent — parentOf(ROOT_ID) always returns null regardless of ops.
        val tree = MovableTree.empty<String>()
        assertNull(tree.parentOf(MovableTree.ROOT_ID))
    }

    @Test
    fun childrenOfWithNoChildren() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        // A has no children yet.
        assertEquals(emptySet(), t1.childrenOf(idA))
    }

    @Test
    fun isAncestorReturnsFalseForSelf() {
        // isAncestor documents "proper ancestor … excluding descendant itself".
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        assertFalse(t1.isAncestor(ancestor = idA, descendant = idA))
    }

    // ── Serialization round-trip ──────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "docs")
        val (t2, _) = t1.addNode(alice, ts = 2L, parent = idA, value = "readme")
        val ser = MovableTree.serializer(String.serializer())
        val encoded = Json.encodeToString(ser, t2)
        val decoded = Json.decodeFromString(ser, encoded)
        assertEquals(t2, decoded)
    }

    // ── Lattice / merge laws ──────────────────────────────────────────────────

    @Test
    fun mergeIsIdempotent() {
        val tree = MovableTree.empty<String>()
        val (t1, _) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "x")
        assertEquals(t1, t1.piece(t1))
    }

    @Test
    fun mergeIsCommutative() {
        val base = MovableTree.empty<String>()
        val (tA, _) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "x")
        val (tB, _) = base.addNode(bob, ts = 2L, parent = MovableTree.ROOT_ID, value = "y")
        assertEquals(tA.piece(tB), tB.piece(tA))
    }

    @Test
    fun mergeIsAssociative() {
        val base = MovableTree.empty<String>()
        val (tA, _) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "a")
        val (tB, _) = base.addNode(bob, ts = 2L, parent = MovableTree.ROOT_ID, value = "b")
        val (tC, _) = base.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "c")
        assertEquals(
            tA.piece(tB).piece(tC),
            tA.piece(tB.piece(tC)),
        )
    }

    @Test
    fun mergingDivergedReplicasConverges() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(bob, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")

        // Alice and Bob diverge from t1 state.
        val (aliceLocal, _) = t1.addNode(alice, ts = 3L, parent = idA, value = "aliceChild")
        val (bobLocal, _) = t2.addNode(bob, ts = 4L, parent = idB, value = "bobChild")

        val merged1 = aliceLocal.piece(bobLocal)
        val merged2 = bobLocal.piece(aliceLocal)
        assertEquals(merged1, merged2)
    }

    // ── Algorithm-specific: concurrent-move convergence ──────────────────────

    /**
     * Alice moves node A under B; Bob concurrently moves node A under C.
     * Both replicas must converge to the same placement — exactly one of B or C,
     * determined by the highest timestamp (tie-broken by replicaId).
     */
    @Test
    fun concurrentMoveSameNodeConvergesToOneParent() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, idC) = t2.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "C")

        // Both diverge from t3.
        val (aliceState, alicePatch) = t3.move(alice, ts = 4L, node = idA, newParent = idB)
        val (bobState, bobPatch)     = t3.move(bob,   ts = 5L, node = idA, newParent = idC)

        val mergedByAlice = aliceState.piece(bobPatch)
        val mergedByBob   = bobState.piece(alicePatch)

        // Commutative: both arrive at the same tree.
        assertEquals(mergedByAlice, mergedByBob)

        // Bob's ts=5 wins (higher), so A ends up under C.
        val winningParent = mergedByAlice.parentOf(idA)
        assertNotNull(winningParent)
        assertEquals(idC, winningParent)
    }

    /**
     * When timestamps tie, the higher replicaId wins — deterministic everywhere.
     */
    @Test
    fun concurrentMoveTieBreaksOnReplicaId() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, idC) = t2.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "C")

        // Same timestamp — replicaId decides.
        val (aliceState, alicePatch) = t3.move(alice, ts = 4L, node = idA, newParent = idB)
        val (bobState, bobPatch)     = t3.move(bob,   ts = 4L, node = idA, newParent = idC)

        val mergedByAlice = aliceState.piece(bobPatch)
        val mergedByBob   = bobState.piece(alicePatch)

        assertEquals(mergedByAlice, mergedByBob)
        // "bob" > "alice" lexicographically, so bob's op wins.
        assertEquals(idC, mergedByAlice.parentOf(idA))
    }

    // ── Algorithm-specific: cycle prevention ─────────────────────────────────

    /**
     * Alice moves A under B; Bob concurrently moves B under A.
     * If both applied naively, A→B→A would be a cycle. The algorithm must
     * resolve to a valid acyclic state where exactly one move stands.
     */
    @Test
    fun concurrentMovesFormingCycleResolveAcyclically() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")

        // Alice moves A under B; Bob moves B under A — a potential cycle.
        val (aliceState, alicePatch) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (bobState, bobPatch)     = t2.move(bob,   ts = 4L, node = idB, newParent = idA)

        val mergedByAlice = aliceState.piece(bobPatch)
        val mergedByBob   = bobState.piece(alicePatch)

        // Convergence.
        assertEquals(mergedByAlice, mergedByBob)

        // No node appears as its own ancestor.
        assertFalse(mergedByAlice.isAncestor(ancestor = idA, descendant = idA))
        assertFalse(mergedByAlice.isAncestor(ancestor = idB, descendant = idB))

        // The tree is actually a valid tree (every non-root has a path to root).
        assertTrue(mergedByAlice.hasPathToRoot(idA))
        assertTrue(mergedByAlice.hasPathToRoot(idB))
    }

    /**
     * Three-node cycle attempt: A→B→C→A is prevented.
     */
    @Test
    fun threeNodeCycleAttemptIsResolved() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = idA, value = "B")
        val (t3, idC) = t2.addNode(alice, ts = 3L, parent = idB, value = "C")

        // A→B→C already. Now concurrently:
        // Alice moves A under C (would create A→B→C→A cycle).
        val (_, alicePatch) = t3.move(alice, ts = 4L, node = idA, newParent = idC)
        val merged = t3.piece(alicePatch)

        assertFalse(merged.isAncestor(ancestor = idA, descendant = idA))
        assertTrue(merged.hasPathToRoot(idA))
        assertTrue(merged.hasPathToRoot(idB))
        assertTrue(merged.hasPathToRoot(idC))
    }

    // ── GC documentation coverage ─────────────────────────────────────────────

    @Test
    fun moveLogGrowsWithEachOperation() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _)   = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        assertEquals(3, t3.moveLogSize)
    }

    // ── addNode Patch convergence ─────────────────────────────────────────────

    /**
     * The patch returned by [MovableTree.addNode] must produce the same tree when
     * applied via [MovableTree.piece] as receiving the full-state update directly.
     *
     * This is the Quilter integration path: a replica applies addNode locally and
     * ships the patch to peers; peers absorb via piece(patch) and must converge.
     */
    @Test
    fun addNodePatchConvergesWithFullStatePath() {
        val base = MovableTree.empty<String>()
        val (t1, idA, patch) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")

        // Peer absorbs via the patch delta (Quilter path).
        val peerAfterPatch = base.piece(patch)

        assertAll(
            { assertEquals(t1, peerAfterPatch) },
            { assertTrue(peerAfterPatch.contains(idA)) },
            { assertEquals(MovableTree.ROOT_ID, peerAfterPatch.parentOf(idA)) },
        )
    }

    /**
     * Multiple addNode patches compose correctly: applying them individually or in
     * batch produces the same result as the full-state path.
     */
    @Test
    fun multipleAddNodePatchesComposeProperly() {
        val base = MovableTree.empty<String>()
        val (t1, idA, patchA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB, patchB) = t1.addNode(alice, ts = 2L, parent = idA, value = "B")

        // Peer starts from base and absorbs patches in order.
        val peerAfterBoth = base.piece(patchA).piece(patchB)

        assertAll(
            { assertEquals(t2, peerAfterBoth) },
            { assertTrue(peerAfterBoth.contains(idA)) },
            { assertTrue(peerAfterBoth.contains(idB)) },
            { assertEquals(idA, peerAfterBoth.parentOf(idB)) },
        )
    }

    /**
     * addNode patch and move patch compose together: a peer can apply an addNode
     * delta followed by a move delta and arrive at the same state as the originator.
     */
    @Test
    fun addNodePatchComposesWithMovePatch() {
        val base = MovableTree.empty<String>()
        val (t1, idA, addPatch) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB, _) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, movePatch) = t2.move(alice, ts = 3L, node = idA, newParent = idB)

        // Peer receives the addNode delta for A, then the move delta.
        val peer = base.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B").tree
            .piece(addPatch)
            .piece(movePatch)

        assertEquals(t3, peer)
    }
}

/**
 * Tests for [MovableTree] incremental-replay optimization (#728).
 *
 * The contract: piece() must produce byte-identical results to a from-scratch
 * replayLog across all interleavings of concurrent ops (convergence) while doing
 * less work in the common case.
 *
 * These tests fuzz random concurrent-move interleavings and assert that:
 * - The effective parent map from an incremental merge equals the parent map from
 *   a full from-scratch replay of the same merged log.
 * - Cycle prevention is preserved (no node is its own ancestor).
 * - Compaction compatibility: compact() + applyCompact() after incremental merges
 *   must still converge.
 */
class MovableTreeIncrementalReplayTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val carol = ReplicaId("carol")

    /**
     * After every piece() call, the effective parent map must equal what a
     * from-scratch replayLog of the merged log would produce. We verify this
     * by exposing the internal parent map via [MovableTree.effectiveParentsForTest]
     * and comparing it to [replayLog] applied to the same log.
     *
     * Fuzz: 50 random interleavings of 20 ops across 3 replicas.
     */
    @Test
    fun incrementalPieceMapsMatchFromScratchReplay() {
        val random = kotlin.random.Random(42L)
        repeat(50) { seed ->
            val rng = kotlin.random.Random(seed.toLong())
            var alice0 = MovableTree.empty<String>()
            var bob0 = MovableTree.empty<String>()
            var carol0 = MovableTree.empty<String>()

            // Build a shared base: 5 nodes known to all replicas.
            val nodes = mutableListOf<String>()
            for (i in 1..5) {
                val parent = if (nodes.isEmpty()) MovableTree.ROOT_ID else nodes[rng.nextInt(nodes.size)]
                val (nextAlice, nodeId) = alice0.addNode(alice, ts = i.toLong(), parent = parent, value = "node$i")
                alice0 = nextAlice
                nodes += nodeId
            }
            // Share the base with all replicas.
            bob0 = alice0
            carol0 = alice0

            var ts = 6L
            // Perform 15 random independent moves across the 3 replicas.
            repeat(15) {
                val node = nodes[rng.nextInt(nodes.size)]
                val parent = if (rng.nextBoolean()) MovableTree.ROOT_ID else nodes[rng.nextInt(nodes.size)]
                val replica = when (rng.nextInt(3)) {
                    0 -> { val (next, _) = alice0.move(alice, ts = ts, node = node, newParent = parent); alice0 = next; alice }
                    1 -> { val (next, _) = bob0.move(bob, ts = ts, node = node, newParent = parent); bob0 = next; bob }
                    else -> { val (next, _) = carol0.move(carol, ts = ts, node = node, newParent = parent); carol0 = next; carol }
                }
                ts++
                // suppress unused warning
                replica.hashCode()
            }

            // Merge all three in all orderings.
            val ab = alice0.piece(bob0)
            val abc = ab.piece(carol0)
            val bac = bob0.piece(alice0).piece(carol0)
            val cab = carol0.piece(alice0).piece(bob0)

            // All orderings must produce the same tree (convergence).
            assertEquals(abc, bac, "ordering abc vs bac failed at seed=$seed")
            assertEquals(abc, cab, "ordering abc vs cab failed at seed=$seed")

            // Each merged tree's effective parent map must equal a from-scratch replay
            // of its log — this is the key contract for the incremental optimization.
            for ((label, merged) in listOf("abc" to abc, "bac" to bac, "cab" to cab)) {
                val fromScratch: Map<String, String> = replayLog(merged.logForTest())
                val incremental: Map<String, String> = merged.effectiveParentsForTest()
                assertTrue(
                    fromScratch == incremental,
                    "effectiveParents mismatch (seed=$seed, ordering=$label): incremental != from-scratch",
                )

                // No node is its own ancestor (cycle prevention).
                for (node in nodes) {
                    assertFalse(
                        merged.isAncestor(ancestor = node, descendant = node),
                        "cycle detected at node=$node (seed=$seed, ordering=$label)",
                    )
                }
            }
        }
    }

    /** Compaction + incremental replay: compact on one replica, then merge with another. */
    @Test
    fun incrementalReplayAfterCompactionIsConsistent() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val delivered = VersionVector.of(mapOf(alice to 4L))
        val (aliceCompacted, compactOp) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact must succeed")

        // Bob applies the compact op and then merges with alice's compacted tree.
        val bobAfterCompact = t4.applyCompact(compactOp)
        val merged = aliceCompacted.piece(bobAfterCompact)

        val fromScratch: Map<String, String> = replayLog(merged.logForTest())
        val incremental: Map<String, String> = merged.effectiveParentsForTest()
        assertTrue(fromScratch == incremental, "effectiveParents mismatch after compaction: incremental != from-scratch")
        assertEquals(MovableTree.ROOT_ID, merged.parentOf(idA))
        assertEquals(MovableTree.ROOT_ID, merged.parentOf(idB))
    }

    /**
     * The common case: a small delta (1-2 new ops, all later than any existing op)
     * is merged into a large base. The incremental path must produce the same
     * result as from-scratch replay.
     */
    @Test
    fun appendOnlyDeltaMergeMatchesFromScratch() {
        var base = MovableTree.empty<String>()
        val nodes = mutableListOf<String>()
        // Build a tree with 30 ops (simulates a log that has grown over time).
        for (i in 1..30) {
            val parent = if (nodes.isEmpty()) MovableTree.ROOT_ID else nodes[(i - 1) % nodes.size]
            val (next, nodeId) = base.addNode(alice, ts = i.toLong(), parent = parent, value = "n$i")
            base = next
            nodes += nodeId
        }

        // Bob only has the first 10 ops.
        var smallDelta = MovableTree.empty<String>()
        for (i in 31..33) {
            val parent = nodes[i % nodes.size]
            val (next, nodeId) = smallDelta.addNode(bob, ts = i.toLong(), parent = parent, value = "b$i")
            smallDelta = next
            nodes += nodeId
        }

        val merged = base.piece(smallDelta)
        val fromScratch: Map<String, String> = replayLog(merged.logForTest())
        val incremental: Map<String, String> = merged.effectiveParentsForTest()
        assertTrue(fromScratch == incremental, "incremental merge of append-only delta must match from-scratch replay")
    }
}

/**
 * Tests for [MovableTree] causal GC / compaction (#725).
 *
 * TDD: these tests were written BEFORE the implementation. They verify:
 * 1. [MovableTree.causalDots] is non-empty (Quilter can drive GC).
 * 2. [MovableTree.compact] shrinks the move-log after ops become causally stable.
 * 3. Convergence is preserved across compaction.
 * 4. Cycle prevention still holds post-compaction.
 * 5. A creation op referenced by a live move is NOT dropped.
 */
class MovableTreeCompactionTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    // ── causalDots ────────────────────────────────────────────────────────────

    @Test
    fun causalDotsIsEmptyForEmptyTree() {
        assertEquals(emptySet(), MovableTree.empty<String>().causalDots())
    }

    @Test
    fun causalDotsExposesOneDotsPerOp() {
        val tree = MovableTree.empty<String>()
        val (t1, _) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, _) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        // Two ops by alice → two dots.
        val dots = t2.causalDots()
        assertEquals(2, dots.size)
        assertTrue(dots.all { it.replica == alice })
        // Seqs must be distinct (dense, no collision).
        assertEquals(2, dots.map { it.seq }.toSet().size)
    }

    @Test
    fun causalDotsSpansBothReplicas() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, _) = t1.addNode(bob, ts = 2L, parent = idA, value = "B")
        val dots = t2.causalDots()
        assertEquals(2, dots.size)
        assertTrue(dots.any { it.replica == alice })
        assertTrue(dots.any { it.replica == bob })
    }

    @Test
    fun causalDotsIncludesCompactedDots() {
        // After compaction, causalDots must still include the compacted ops' dots
        // so the Quilter's contiguous delivered frontier has no holes.
        // Scenario: addA, addB, move(A→B), move(A→ROOT). The third op [move A→B, seq=3]
        // is superseded by the fourth op [move A→ROOT, seq=4] and is droppable once stable.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val dotsBeforeGc = t4.causalDots()
        // Four ops, all alice.
        assertEquals(4, dotsBeforeGc.size)

        val delivered = stableCut(alice to 4L)
        val (compacted, _) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact() must return non-null — move(A→B) at seq=3 is superseded by move(A→ROOT) at seq=4")

        // After compaction the log shrinks, but causalDots must still cover all 4 delivered dots.
        assertTrue(compacted.moveLogSize < t4.moveLogSize, "log must shrink")
        assertEquals(4, compacted.causalDots().size, "all 4 dots must still be visible post-GC")
    }

    // ── compact — log shrinks ─────────────────────────────────────────────────

    @Test
    fun compactReturnsNullWhenFrontierNotComplete() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)

        val stableCutAll = stableCut(alice to 3L)
        // Frontier claims bob has op 1, but delivered doesn't include it → not frontier-complete.
        val frontierWithBob = frontier(alice to 3L, bob to 1L)
        val delivered = stableCut(alice to 3L) // only alice delivered — bob frontier not met

        assertNull(t3.compact(stableCut = stableCutAll, frontierMax = frontierWithBob, delivered = delivered))
    }

    @Test
    fun compactDropsSupersededNonCreationOp() {
        // Setup: Alice adds A (creation op ts=1), then moves A under ROOT (ts=2 move),
        // then moves A under itself-sibling B (ts=3 — the "winning" move).
        // After stability, the ts=2 move is superseded by ts=3 and should be droppable.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        // Move A under B at ts=3 — this is the winning op.
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        // Now move A again at ts=4 — supersedes ts=3, so ts=3 move becomes redundant.
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val logSizeBefore = t4.moveLogSize
        val delivered = stableCut(alice to 4L)

        val (compacted, compactOp) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact() must return non-null — ops are stable and ts=3 move is superseded")

        assertAll(
            { assertTrue(compacted.moveLogSize < logSizeBefore, "log must shrink after compaction") },
            { assertTrue(compactOp.droppedDots.isNotEmpty(), "compact op must carry dropped dots") },
            // Tree shape must be preserved.
            { assertEquals(MovableTree.ROOT_ID, compacted.parentOf(idA)) },
            { assertEquals(MovableTree.ROOT_ID, compacted.parentOf(idB)) },
        )
    }

    /**
     * Regression for the unstable-winner soundness bug (#764 review).
     *
     * Scenario: addA(seq1), addB(seq2), addC(seq3), move(A→B, seq4), move(A→C, seq5).
     * seq5 is A's winner (last applied). With stableCut = {alice→4} (seq5 NOT stable yet),
     * compact() must NOT drop seq4 — dropping it while a slow peer hasn't seen seq5 yet
     * would leave that peer with a phantom parent for A.
     */
    @Test
    fun compactDoesNotDropSupersededOpWhenWinnerIsUnstable() {
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, idC) = t2.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "C")
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = idB) // superseded candidate
        val (t5, _) = t4.move(alice, ts = 5L, node = idA, newParent = idC) // the winner (seq=5)

        // stableCut covers only through seq=4; seq=5 (the winner) is NOT yet stable.
        val partialCut = stableCut(alice to 4L)
        val fullFrontier = frontier(alice to 5L)

        // delivered dominates the full frontier — locally we have all 5 ops.
        val deliveredAll = stableCut(alice to 5L)

        // compact must return null — dropping the seq=4 move would be unsound
        // because its winner (seq=5) is not yet causally stable on all peers.
        assertNull(
            t5.compact(stableCut = partialCut, frontierMax = fullFrontier, delivered = deliveredAll),
            "compact must return null when the winner (seq=5) is not stable — dropping seq=4 would be unsound",
        )
    }

    @Test
    fun compactDoesNotDropCreationOpReferencedByLiveMove() {
        // Node A created at ts=1 (creation op). Then moved under B at ts=2 (live move).
        // The ts=1 creation op registers A's value — it must NOT be dropped even though
        // it is causally stable, because the ts=2 move still references node A.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)

        // All three ops are stable. But ts=1 is A's creation op — referenced by idA which is
        // still a live node in the tree.
        val delivered = stableCut(alice to 3L)

        // ts=3 is the winning (latest) move for node A, so ts=1 creation is the one that
        // "registered" the node. Since there's no later op on idA that supersedes ts=3,
        // nothing for idA is droppable. compact() must return null or not drop creation ops.
        val result = t3.compact(stableCut = delivered, frontierMax = delivered, delivered = delivered)

        // If compact returns non-null, the creation op for idA must still be present.
        if (result != null) {
            val (compacted, _) = result
            assertTrue(compacted.contains(idA), "node A must still exist after compaction")
            assertNotNull(compacted.parentOf(idA), "node A must still have a parent after compaction")
        }
        // Whether null or non-null, node A's identity must survive.
    }

    @Test
    fun compactDoesNotDropCreationOpOfParentNode() {
        // Node B is a creation op (ts=2). Node A is moved under B (ts=3, the winning move).
        // B's creation op must not be dropped while A's live parent is B.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)

        val delivered = stableCut(alice to 3L)
        val result = t3.compact(stableCut = delivered, frontierMax = delivered, delivered = delivered)

        if (result != null) {
            val (compacted, _) = result
            assertTrue(compacted.contains(idB), "node B must still exist — it is a live parent of A")
        }
    }

    // ── compact — convergence preserved ──────────────────────────────────────

    @Test
    fun twoReplicasStillConvergeAfterOneCompacts() {
        // Alice and Bob start with the same 4-op tree.
        // Alice compacts; Bob does not.
        // They then merge — both must arrive at the same effective parent map.
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        // Move A under B (ts=3) then move A back to ROOT (ts=4) — supersedes ts=3.
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val delivered = stableCut(alice to 4L)
        val (aliceCompacted, compactOp) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact must succeed — all ops stable, ts=3 move superseded")

        // Bob absorbs the Compact op.
        val bobAfterCompact = t4.piece(Patch(MovableTree.empty<String>().applyCompact(compactOp)))

        // Both replicas must have identical parent maps.
        assertAll(
            { assertEquals(aliceCompacted.parentOf(idA), bobAfterCompact.parentOf(idA)) },
            { assertEquals(aliceCompacted.parentOf(idB), bobAfterCompact.parentOf(idB)) },
        )
    }

    @Test
    fun cyclePreventionHoldsAfterCompaction() {
        // Two concurrent moves that would cycle. Compact the superseded move.
        // The resulting tree must still be acyclic and have path to root.
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")

        // Alice moves A→B, Bob moves B→A concurrently (would cycle).
        val (aliceTree, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (bobTree, bobPatch) = t2.move(bob, ts = 4L, node = idB, newParent = idA)

        // After merge: bob's ts=4 wins for B (higher ts). A→B, B stays under ROOT (cycle prevented).
        val merged = aliceTree.piece(bobPatch)

        // Now compact: alice's ts=3 (move A under B) is the winning op for A; bob's ts=4 wins
        // for B. In this specific scenario there are no superseded non-creation moves — the
        // creation ops and winning moves are the only ops. To make compaction fire, add a
        // superseded move: move A back to ROOT at ts=5 (supersedes alice's ts=3 move of A→B).
        val (merged2, _) = merged.move(alice, ts = 5L, node = idA, newParent = MovableTree.ROOT_ID)
        val delivered = stableCut(alice to 5L, bob to 4L)
        val (compacted, _) = merged2.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact must fire — alice's ts=3 move(A→B) is superseded by ts=5 move(A→ROOT)")

        // Compact must have actually shrunk the log.
        assertTrue(compacted.moveLogSize < merged2.moveLogSize, "log must shrink — not a vacuous pass")

        assertAll(
            { assertFalse(compacted.isAncestor(idA, idA), "A must not be its own ancestor") },
            { assertFalse(compacted.isAncestor(idB, idB), "B must not be its own ancestor") },
            { assertTrue(compacted.hasPathToRoot(idA), "A must have path to root") },
            { assertTrue(compacted.hasPathToRoot(idB), "B must have path to root") },
        )
    }

    // ── compact op broadcast — peers apply it ─────────────────────────────────

    @Test
    fun applyCompactShrinksPeerLog() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val delivered = stableCut(alice to 4L)
        val (_, compactOp) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact must succeed")

        // Peer (bob's replica) has the same full log — apply the compact op.
        val peerAfter = t4.applyCompact(compactOp)

        assertTrue(peerAfter.moveLogSize < t4.moveLogSize, "peer log must shrink after applying compact op")
    }

    // ── serialization round-trip including Compact op ─────────────────────────

    @Test
    fun compactedTreeRoundTripsThroughJson() {
        val base = MovableTree.empty<String>()
        val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val delivered = stableCut(alice to 4L)
        val (compacted, _) = t4.compact(
            stableCut = delivered,
            frontierMax = delivered,
            delivered = delivered,
        ) ?: error("compact must succeed")

        val ser = MovableTree.serializer(String.serializer())
        val encoded = Json.encodeToString(ser, compacted)
        val decoded = Json.decodeFromString(ser, encoded)
        assertEquals(compacted, decoded)
    }
}
