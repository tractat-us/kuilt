package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
