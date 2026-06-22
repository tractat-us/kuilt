package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Cache-threading correctness for [Rga].
 *
 * These tests pin the invariants that the cache-threading optimisation must uphold:
 *
 * 1. [Rga.insertsById] is available incrementally after each mutation — the map threaded
 *    forward by [Rga.insertAfter], [Rga.apply], and [Rga.piece] equals the map that would
 *    be computed by a full O(ops) scan of the op-log.
 *
 * 2. [Rga.maxSeqByReplica] is available O(1) after each insert — the per-replica seq
 *    ceiling threaded forward equals the maximum [RgaId.seq] seen in the op-log for
 *    that replica.
 *
 * 3. [Rga.size] reads the cached [Rga.sequence] rather than rebuilding via [Rga.toList].
 *    After a sequence of inserts the size must equal the count of non-tombstoned entries
 *    in the already-materialised [Rga.sequence].
 *
 * 4. [Rga.piece] (merge) correctly unions the [insertsById] maps from both sides, and
 *    correctly merges the [maxSeqByReplica] maps (taking the max per replica).
 *
 * These tests will NOT compile before the fix because [insertsById] is currently
 * `private` and [maxSeqByReplica] does not exist; promoting them to `internal` and
 * adding [maxSeqByReplica] is part of the fix.
 */
class RgaCacheTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    // ---- insertsById cache threading ----

    @Test
    fun insertsByIdContainsNewOpAfterInsertAfter() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val insert = r1.insertsById[op1.id]
        assertNotNull(insert)
        assertEquals("x", insert.value)
    }

    @Test
    fun insertsByIdContainsAllOpsAfterChainOfInserts() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, op3) = r2.insertAfter(b, op2.id, "z")
        val expected = mapOf(op1.id to op1, op2.id to op2, op3.id to op3)
        assertEquals(expected, r3.insertsById)
    }

    @Test
    fun insertsByIdMatchesFromScratchAfterApply() {
        val (r0, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r1, op2) = r0.insertAfter(b, RgaId.HEAD, "y")
        // Apply op2 to r0 — r0 was built without op2, so this exercises the apply path.
        val r0WithOp2 = r0.apply(op2)
        val fromScratch = Rga.empty<String>().apply(op1).apply(op2)
        assertEquals(fromScratch.insertsById, r0WithOp2.insertsById)
    }

    @Test
    fun insertsByIdIsUnionedCorrectlyByPiece() {
        val (rA, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "A1")
        val (rB, op2) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "B1")
        val merged = rA.piece(rB)
        assertEquals(mapOf(op1.id to op1, op2.id to op2), merged.insertsById)
    }

    @Test
    fun insertsByIdDropsGcIdOnCompactApply() {
        val (r0, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r1, _) = r0.removeAt(0)!!
        val stable = VersionVector.of(mapOf(a to op1.id.seq))
        val (compacted, compactOp) = r1.compact(stable, stable, stable)!!
        // The compacted Insert must no longer be in insertsById.
        assertEquals(emptyMap(), compacted.insertsById)
        // Applying the compact op to a sibling replica should also remove it.
        val sibling = r1.apply(compactOp)
        assertEquals(emptyMap(), sibling.insertsById)
    }

    // ---- maxSeqByReplica cache threading ----

    @Test
    fun maxSeqByReplicaIsOneAfterFirstInsert() {
        val (r1, _) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        assertEquals(1L, r1.maxSeqByReplica[a])
    }

    @Test
    fun maxSeqByReplicaIncrementsWithEachInsert() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, _) = r2.insertAfter(a, op2.id, "z")
        assertEquals(3L, r3.maxSeqByReplica[a])
    }

    @Test
    fun maxSeqByReplicaIsIndependentPerReplica() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a1")
        val (r2, _) = r1.insertAfter(b, op1.id, "b1")
        val (r3, _) = r2.insertAfter(a, op1.id, "a2")
        assertEquals(2L, r3.maxSeqByReplica[a])
        assertEquals(1L, r3.maxSeqByReplica[b])
    }

    @Test
    fun maxSeqByReplicaIsMergedByPiece() {
        val (rA1, _) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (rA2, _) = rA1.insertAfter(a, RgaId.HEAD, "y")
        val (rB1, _) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "z")
        val merged = rA2.piece(rB1)
        assertEquals(2L, merged.maxSeqByReplica[a])
        assertEquals(1L, merged.maxSeqByReplica[b])
    }

    @Test
    fun maxSeqByReplicaIsCorrectAfterApply() {
        val (_, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (_, op2) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "y") // same author, seq=1 from different root
        // Apply both ops to an empty Rga — max seq for `a` should be max(op1.id.seq, op2.id.seq).
        val r = Rga.empty<String>().apply(op1).apply(op2)
        val expected = maxOf(op1.id.seq, op2.id.seq)
        assertEquals(expected, r.maxSeqByReplica[a])
    }

    // ---- size uses cached sequence ----

    @Test
    fun sizeEqualsSequenceCountMinusTombstones() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, _) = r2.insertAfter(a, op2.id, "z")
        val (r4, _) = r3.removeAt(1)!! // remove "y"
        // size must equal the count of non-tombstoned ids in the cached sequence.
        val expected = r4.sequence.count { it !in r4.tombstones }
        assertEquals(expected, r4.size)
        assertEquals(2, r4.size)
    }

    // ---- end-to-end correctness: cached state equals from-scratch after sequences of ops ----

    @Test
    fun cachedInsertsEqualsFromScratchAfterLongChain() {
        val n = 50
        var rga = Rga.empty<String>()
        var after = RgaId.HEAD
        repeat(n) { i ->
            val (next, op) = rga.insertAfter(a, after, "v$i")
            rga = next
            after = op.id
        }
        // From-scratch: build a fresh Rga from the op-log.
        val fromScratch = Rga.fromOps(rga.ops, rga.lamport)
        assertEquals(fromScratch.insertsById, rga.insertsById)
        assertEquals(fromScratch.maxSeqByReplica[a], rga.maxSeqByReplica[a])
        assertEquals(fromScratch.toList(), rga.toList())
    }

    @Test
    fun cachedStateEqualsFromScratchAfterMixedOps() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a1")
        val (r2, op2) = r1.insertAfter(b, RgaId.HEAD, "b1")
        val (r3, op3) = r2.insertAfter(a, op1.id, "a2")
        val (r4, _) = r3.removeAt(0)!! // remove "b1" (highest id at HEAD)
        // piece two diverging replicas together
        val rA = Rga.empty<String>().apply(op1).apply(op3)
        val rB = Rga.empty<String>().apply(op2)
        val merged = rA.piece(rB)
        val fromScratch = Rga.fromOps(r4.ops, r4.lamport)
        assertEquals(fromScratch.insertsById, r4.insertsById)
        assertEquals(merged.toList(), merged.piece(merged).toList(), "piece is idempotent on cached state")
    }
}
