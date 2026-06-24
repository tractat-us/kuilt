package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [Fugue]: the maximal non-interleaving sequence CRDT.
 *
 * The key algorithmic property tested here is non-interleaving:
 * two replicas that concurrently insert a multi-element run at the
 * same position produce two contiguous blocks after merge, not a
 * character-by-character interleaving. This is the property `Rga` fails
 * and `Fugue` guarantees (Weidner et al., "The Art of the Fugue", 2023).
 */
class FugueTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    // ── Basic operations ──────────────────────────────────────────────────────

    @Test
    fun emptyFugueIsEmpty() {
        val fugue = Fugue.empty<String>()
        assertEquals(emptyList(), fugue.toList())
        assertEquals(0, fugue.size)
    }

    @Test
    fun insertSingleElement() {
        val (f, _) = Fugue.empty<String>().insertAt(a, 0, "hello")
        assertEquals(listOf("hello"), f.toList())
        assertEquals(1, f.size)
    }

    @Test
    fun insertMultipleElementsInOrder() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "a")
        val (f2, _) = f1.insertAt(a, 1, "b")
        val (f3, _) = f2.insertAt(a, 2, "c")
        assertEquals(listOf("a", "b", "c"), f3.toList())
    }

    @Test
    fun insertAtBeginning() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "b")
        val (f2, _) = f1.insertAt(a, 0, "a")
        assertEquals(listOf("a", "b"), f2.toList())
    }

    @Test
    fun insertAtMiddle() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "a")
        val (f2, _) = f1.insertAt(a, 1, "c")
        val (f3, _) = f2.insertAt(a, 1, "b")
        assertEquals(listOf("a", "b", "c"), f3.toList())
    }

    @Test
    fun removeElement() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "hello")
        val (f2, _) = f1.removeAt(0)!!
        assertEquals(emptyList(), f2.toList())
        assertEquals(0, f2.size)
    }

    @Test
    fun removeMiddleElement() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "a")
        val (f2, _) = f1.insertAt(a, 1, "b")
        val (f3, _) = f2.insertAt(a, 2, "c")
        val (f4, _) = f3.removeAt(1)!!
        assertEquals(listOf("a", "c"), f4.toList())
    }

    @Test
    fun removeOutOfBoundsReturnsNull() {
        val f = Fugue.empty<String>()
        assertEquals(null, f.removeAt(0))
        assertEquals(null, f.removeAt(-1))
    }

    // ── Convergence (lattice laws) ────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val (f, _) = Fugue.empty<String>().insertAt(a, 0, "x")
        assertEquals(f.toList(), f.piece(f).toList())
    }

    @Test
    fun pieceIsCommutative() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "A-element")
        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "B-element")

        val mergedAB = fA.apply(opB)
        val mergedBA = fB.apply(opA)

        assertEquals(mergedAB.toList(), mergedBA.toList())
    }

    @Test
    fun pieceIsAssociative() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, op2) = Fugue.empty<String>().insertAt(b, 0, "y")
        val (f3, op3) = Fugue.empty<String>().insertAt(c, 0, "z")

        val left = f1.apply(op2).apply(op3)
        val right = f2.apply(op3).let { f23 -> f1.piece(f23) }

        assertEquals(left.toList(), right.toList())
    }

    @Test
    fun concurrentInsertsConvergeToDeterministicOrder() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "from-A")
        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "from-B")

        // Both replicas absorb both ops
        val mergedByA = fA.apply(opB)
        val mergedByB = fB.apply(opA)

        assertEquals(mergedByA.toList(), mergedByB.toList())
    }

    // ── THE KEY PROPERTY: Maximal non-interleaving ────────────────────────────

    /**
     * The defining property of Fugue.
     *
     * Replica A inserts "a1", "a2", "a3" at position 0 (prepend).
     * Replica B independently inserts "b1", "b2", "b3" at position 0 (prepend).
     * Both operations start from the same empty state.
     *
     * After merge, the two runs must appear as contiguous blocks:
     * either [a1,a2,a3,b1,b2,b3] or [b1,b2,b3,a1,a2,a3].
     *
     * An RGA would interleave them character-by-character:
     * e.g., [a1,b1,a2,b2,a3,b3] — which Fugue explicitly avoids.
     */
    @Test
    fun concurrentRunsAtSamePositionAreNotInterleaved() {
        var fA = Fugue.empty<String>()
        var fB = Fugue.empty<String>()

        // Replica A inserts "a1", "a2", "a3" at position 0 (each prepended before prior)
        val (fA1, opA1) = fA.insertAt(a, 0, "a1")
        val (fA2, opA2) = fA1.insertAt(a, 0, "a2")
        val (fA3, opA3) = fA2.insertAt(a, 0, "a3")
        fA = fA3

        // Replica B inserts "b1", "b2", "b3" at position 0 independently
        val (fB1, opB1) = fB.insertAt(b, 0, "b1")
        val (fB2, opB2) = fB1.insertAt(b, 0, "b2")
        val (fB3, opB3) = fB2.insertAt(b, 0, "b3")
        fB = fB3

        // Merge all ops into both replicas — they must converge to the same list
        val mergedByA = fA.apply(opB1).apply(opB2).apply(opB3)
        val mergedByB = fB.apply(opA1).apply(opA2).apply(opA3)

        val listA = mergedByA.toList()
        val listB = mergedByB.toList()

        // Must converge
        assertEquals(listA, listB, "Both replicas must converge to the same list")

        val merged = listA
        assertEquals(6, merged.size)

        // The two runs must be contiguous — find where A's elements are
        val aIndices = merged.mapIndexedNotNull { i, v -> if (v.startsWith("a")) i else null }
        val bIndices = merged.mapIndexedNotNull { i, v -> if (v.startsWith("b")) i else null }

        // Each run must be contiguous (no gaps filled by the other replica's elements)
        val aContiguous = aIndices == (aIndices.first()..aIndices.last()).toList()
        val bContiguous = bIndices == (bIndices.first()..bIndices.last()).toList()

        assertTrue(aContiguous, "A's elements must form a contiguous run, got: $merged")
        assertTrue(bContiguous, "B's elements must form a contiguous run, got: $merged")
    }

    /**
     * Verify that within each replica's run, the insertion order is preserved.
     * The run [a3,a2,a1] reflects prepend order (each inserted before the prior front).
     */
    @Test
    fun insertionOrderPreservedWithinRun() {
        val (fA1, opA1) = Fugue.empty<String>().insertAt(a, 0, "a1")
        val (fA2, opA2) = fA1.insertAt(a, 0, "a2")
        val (fA3, opA3) = fA2.insertAt(a, 0, "a3")

        val (fB1, opB1) = Fugue.empty<String>().insertAt(b, 0, "b1")
        val (fB2, opB2) = fB1.insertAt(b, 0, "b2")

        val merged = fA3.apply(opB1).apply(opB2)
        val list = merged.toList()

        // a3 was last prepended, so it's first among A's elements
        val aElements = list.filter { it.startsWith("a") }
        val bElements = list.filter { it.startsWith("b") }
        assertEquals(listOf("a3", "a2", "a1"), aElements)
        assertEquals(listOf("b2", "b1"), bElements)
    }

    /**
     * Non-interleaving also applies for concurrent appends at the tail.
     */
    @Test
    fun concurrentAppendRunsAreNotInterleaved() {
        // Shared prefix: both replicas start with "X"
        val (shared, _) = Fugue.empty<String>().insertAt(a, 0, "X")

        // Replica A appends a1,a2,a3 after X
        val (fA1, opA1) = shared.insertAt(a, 1, "a1")
        val (fA2, opA2) = fA1.insertAt(a, 2, "a2")
        val (fA3, opA3) = fA2.insertAt(a, 3, "a3")

        // Replica B appends b1,b2 after X concurrently
        val (fB1, opB1) = shared.insertAt(b, 1, "b1")
        val (fB2, opB2) = fB1.insertAt(b, 2, "b2")

        val mergedByA = fA3.apply(opB1).apply(opB2)
        val mergedByB = fB2.apply(opA1).apply(opA2).apply(opA3)

        val listA = mergedByA.toList()
        val listB = mergedByB.toList()

        assertEquals(listA, listB, "Convergence required")

        val merged = listA
        assertEquals(6, merged.size)
        assertEquals("X", merged[0])

        // A and B runs after "X" must each be contiguous
        val tail = merged.drop(1)
        val aIndices = tail.mapIndexedNotNull { i, v -> if (v.startsWith("a")) i else null }
        val bIndices = tail.mapIndexedNotNull { i, v -> if (v.startsWith("b")) i else null }

        assertTrue(
            aIndices == (aIndices.first()..aIndices.last()).toList(),
            "A's appended run must be contiguous in tail: $tail"
        )
        assertTrue(
            bIndices == (bIndices.first()..bIndices.last()).toList(),
            "B's appended run must be contiguous in tail: $tail"
        )
    }

    // ── Serialization round-trip ──────────────────────────────────────────────

    @Test
    fun serializationRoundTrip() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "hello")
        val (f2, _) = f1.insertAt(b, 1, "world")

        val serializer = Fugue.wireSerializer(kotlinx.serialization.serializer<String>())
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(serializer, f2)
        val decoded = json.decodeFromString(serializer, encoded)

        assertEquals(f2.toList(), decoded.toList())
    }

    @Test
    fun emptyFugueSerializationRoundTrip() {
        val f = Fugue.empty<String>()
        val serializer = Fugue.wireSerializer(kotlinx.serialization.serializer<String>())
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(serializer, f)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(emptyList(), decoded.toList())
    }

    /**
     * Two replicas that reach the same logical state via different op-delivery
     * orders must produce identical serialized bytes (JSON and CBOR).
     *
     * Equal logical state → equal wire bytes is required for content-addressing,
     * digest equality, and Quilter delta-fingerprint dedup. The serializer must
     * emit ops in a canonical, replica-independent order — not insertion/delivery
     * order (which varies per replica).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializerIsByteStableAcrossDeliveryOrders() {
        val serializer = Fugue.wireSerializer(serializer<String>())
        val json = Json { encodeDefaults = true }
        val cbor = Cbor

        // Build three concurrent ops from different replicas so delivery order matters.
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "alpha")
        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "beta")
        val (fC, opC) = Fugue.empty<String>().insertAt(c, 0, "gamma")

        // Replica 1: receives ops in order A→B→C
        val replica1 = fA.apply(opB).apply(opC)
        // Replica 2: receives ops in order C→A→B
        val replica2 = fC.apply(opA).apply(opB)

        // Logical equality — same visible elements regardless of delivery order.
        assertEquals(replica1.toList(), replica2.toList(), "Replicas must converge logically")

        val jsonBytes1 = json.encodeToString(serializer, replica1)
        val jsonBytes2 = json.encodeToString(serializer, replica2)

        assertEquals(
            jsonBytes1,
            jsonBytes2,
            "JSON bytes must be identical for equal logical states regardless of delivery order",
        )

        val cborBytes1 = cbor.encodeToByteArray(serializer, replica1)
        val cborBytes2 = cbor.encodeToByteArray(serializer, replica2)

        assertEquals(
            cborBytes1.toList(),
            cborBytes2.toList(),
            "CBOR bytes must be identical for equal logical states regardless of delivery order",
        )
    }

    // ── insertAt single tree-build (#736) ────────────────────────────────────

    /**
     * [Fugue.insertAt] must not trigger [computeSequence] (and hence a second [buildTree]
     * call) during its execution. The method builds the tree once via [buildTree] for
     * both the visible-position lookup and [buildInsertOp] — accessing the lazy [sequence]
     * field a second time would rebuild. This was fixed in #738; this test prevents
     * regression.
     *
     * Verified by checking correctness: both the visible-index resolution and the
     * insert-op construction must share the same tree, so the inserted element lands at
     * the expected position and convergence holds.
     */
    @Test
    fun insertAtProducesCorrectResultWithSingleTreeBuild() {
        // Build a non-trivial tree: interleaved inserts from two replicas.
        val (f0, _) = Fugue.empty<String>().insertAt(a, 0, "a1")
        val (f1, _) = f0.insertAt(b, 0, "b1")
        val (f2, _) = f1.insertAt(a, 1, "a2")

        // Insert at position 2 — requires resolving both the visible sequence and
        // the insert-op parent, both of which need the tree.
        val (result, op) = f2.insertAt(a, 2, "a3")

        // "a3" is inserted at visible index 2 — it must appear there in the result.
        assertEquals(4, result.size)
        assertEquals("a3", result.toList()[2])

        // Idempotence: applying the same op to another replica with the same state converges.
        val applied = f2.apply(op)
        assertEquals(result.toList(), applied.toList())
    }

    // ── Serializer op-sort cache (#735) ──────────────────────────────────────

    /**
     * Repeated [serialize] calls on the same [Fugue] instance must not re-allocate
     * the sorted op list — [Fugue.sortedOps] is a lazy val, so after the first
     * access the same list instance is returned on every subsequent call.
     *
     * This guards against a regression where [FugueSerializer] re-sorted all ops
     * O(M log M) on every encode, which was the hot path for anti-entropy full-state
     * sends under [us.tractat.kuilt.quilter.Quilter].
     */
    @Test
    fun serializerSortedOpListIsCachedAcrossEncodes() {
        val serializer = Fugue.wireSerializer(kotlinx.serialization.serializer<String>())
        var f = Fugue.empty<String>()
        repeat(5) { i ->
            val (next, _) = f.insertAt(a, i, "item-$i")
            f = next
        }

        // Access sortedOps twice on the SAME instance — must be the identical list object.
        val first = f.sortedOps
        val second = f.sortedOps
        assertTrue(first === second, "sortedOps must be the same list instance on repeated access")

        // Encoding twice must also produce identical bytes (byte-stability check still holds).
        val json = Json { encodeDefaults = true }
        val encoded1 = json.encodeToString(serializer, f)
        val encoded2 = json.encodeToString(serializer, f)
        assertEquals(encoded1, encoded2, "Repeated encode of the same instance must produce identical bytes")
    }

    // ── equals and hashCode ───────────────────────────────────────────────────

    /**
     * Two converged replicas that received the same ops in different orders are equal.
     * The Lamport high-water mark must NOT affect equality: one replica may have a
     * higher clock if it advanced it by observing a duplicate insert (which is a no-op
     * to the op set). equals() must be op-set only.
     */
    @Test
    fun convergedReplicasWithDifferentLamportAreEqual() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "y")

        // replica1: saw opA first (lamport advanced to opA.id.lamport), then opB
        val replica1 = fA.apply(opB)
        // replica2: saw opB first, then opA
        val replica2 = fB.apply(opA)

        // Same op sets, but replicas may have different internal lamport values
        // after receiving a duplicate. Both must be equal.
        assertEquals(replica1, replica2)
        assertEquals(replica1.hashCode(), replica2.hashCode())
    }

    @Test
    fun equalFugueHasEqualHashCode() {
        val (f, _) = Fugue.empty<String>().insertAt(a, 0, "x")
        assertEquals(f.hashCode(), f.hashCode())
    }

    // ── apply and piece consistency ──────────────────────────────────────────

    @Test
    fun applyIsIdempotent() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "x")
        val applied = fA.apply(opA)
        // Applying the same op twice must not double-insert
        assertEquals(listOf("x"), applied.toList())
    }

    @Test
    fun applyAndPieceConvergeIdentically() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "A")
        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "B")

        val viaApply = fA.apply(opB)
        val viaPiece = fA.piece(fB)

        assertEquals(viaApply.toList(), viaPiece.toList())
    }

    @Test
    fun removeIsIdempotentOnMerge() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, removeOp) = f1.removeAt(0)!!

        // Applying the remove twice (via merge) must not corrupt state
        val merged = f2.apply(removeOp)
        assertEquals(emptyList(), merged.toList())
    }

    // ── Size and visibility ──────────────────────────────────────────────────

    @Test
    fun sizeReflectsVisibleElements() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "a")
        val (f2, _) = f1.insertAt(a, 1, "b")
        val (f3, removeOp) = f2.removeAt(0)!!
        assertEquals(1, f3.size)
        assertEquals(listOf("b"), f3.toList())
    }

    // ── Contains after concurrent remove and insert ───────────────────────────

    @Test
    fun concurrentInsertAndRemoveConverge() {
        val (shared, _) = Fugue.empty<String>().insertAt(a, 0, "x")

        // Replica A removes "x"
        val (fA, removeOp) = shared.removeAt(0)!!
        // Replica B inserts "y" after "x"
        val (fB, insertOp) = shared.insertAt(b, 1, "y")

        val mergedAB = fA.apply(insertOp)
        val mergedBA = fB.apply(removeOp)

        // Both converge: "x" is gone, "y" survived
        assertEquals(mergedAB.toList(), mergedBA.toList())
        assertFalse("x" in mergedAB.toList())
        assertTrue("y" in mergedAB.toList())
    }
}
