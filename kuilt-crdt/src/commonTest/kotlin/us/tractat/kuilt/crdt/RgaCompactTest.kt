@file:Suppress("DEPRECATION") // Exercises the deprecated scalar compact(Long) until #270 removes it.

package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [Rga.compact] and [RgaOp.Compact].
 *
 * compact(watermark) is a tombstone-GC primitive: it removes tombstoned Insert ops
 * whose id.lamport ≤ watermark AND whose id is not referenced as `after` by any
 * surviving insert.
 */
class RgaCompactTest {

    private val alice = ReplicaId("alice")

    // ---- compact: basic removal ----

    @Test
    fun compactRemovesTombstonedOpsBelowWatermark() {
        // Insert "a", insert "b" after HEAD (not after "a"), remove "a".
        // "a" has no successor insert (b.after == HEAD), so it qualifies for GC.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.insertAfter(alice, RgaId.HEAD, "b") // b.after = HEAD
        val (s2, _) = s1.removeAt(1)!! // remove "a" (b wins slot at HEAD, a is after)

        // Actually with b.after=HEAD and a.after=HEAD, tiebreak by id.
        // Let's use a simpler structure: just insert "a" and remove it.
        val (t0, opX) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "x")
        val (t1, _) = t0.removeAt(0)!!

        val (compacted, delta) = t1.compact(watermark = opX.id.lamport)!!

        assertEquals(emptyList(), compacted.toList())
        assertTrue(opX.id in delta.ids)
    }

    @Test
    fun compactLeavesLiveElementsIntact() {
        // Insert "a" then "b" after HEAD (b concurrent with a, different predecessor).
        // Remove "b". "a" is still visible.
        // "b" qualifies for GC: tombstoned, below watermark, and b.after=HEAD (not a.id).
        // "a" must survive: it's not tombstoned.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        // Insert "b" after "a" in a new replica so lamport > opA.lamport
        val (s1, opB) = s0.insertAfter(alice, opA.id, "b")
        // Remove "b" (index 1 in visible list [a, b])
        val (s2, _) = s1.removeAt(1)!!
        // "a" is still alive, "b" is tombstoned.
        // b.after = opA.id, so opA.id IS in predecessors — b.id is GC-able, a.id is live.
        // At watermark = opB.id.lamport, b qualifies: tombstoned, lamport <= watermark,
        // b.id is not in predecessors (no insert has after=b.id).
        val (compacted, delta) = s2.compact(watermark = opB.id.lamport)!!

        assertEquals(listOf("a"), compacted.toList())
        assertTrue(opB.id in delta.ids)
        assertTrue(opA.id !in delta.ids)
    }

    @Test
    fun compactDoesNotRemoveAboveWatermark() {
        // Insert "a", insert "b" (b.after = a.id), remove "a" and "b".
        // Watermark only covers opA.
        // opA cannot be GC'd: b.after == opA.id (structural predecessor).
        // opB cannot be GC'd: lamport > watermark.
        // Result: null (nothing qualifies).
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, opA.id, "b")
        val (s2, _) = s1.removeAt(0)!! // remove "a"
        val (s3, _) = s2.removeAt(0)!! // remove "b"

        assertNull(s3.compact(watermark = opA.id.lamport),
            "Expected null: opA is structural predecessor of opB; opB is above watermark")
    }

    @Test
    fun compactAtHighWatermarkGcsChainAfterAllSuccessorsRemoved() {
        // Insert "a", insert "b" (b.after=a.id), remove both.
        // At watermark covering both: first round GCs opB (no successor),
        // then the caller compacts again and GCs opA.
        // This test confirms a single compact call at high watermark can GC opB
        // (opA still blocked as structural predecessor of opB).
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, opA.id, "b")
        val (s2, _) = s1.removeAt(0)!! // remove "a"
        val (s3, _) = s2.removeAt(0)!! // remove "b"

        // One compact call at high watermark: only opB qualifies (no successor).
        val (s4, delta1) = s3.compact(watermark = opB.id.lamport)!!
        assertTrue(opB.id in delta1.ids)
        assertTrue(opA.id !in delta1.ids, "opA is still a structural predecessor of opB's Insert")

        // Second compact on s4: opA is now free (opB's Insert gone from log).
        val (s5, delta2) = s4.compact(watermark = opB.id.lamport)!!
        assertTrue(opA.id in delta2.ids)
        assertEquals(emptyList(), s5.toList())
    }

    @Test
    fun compactReturnsNullWhenNothingToGc() {
        val (s0, _) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        // No tombstones at all
        assertNull(s0.compact(watermark = 999L))
    }

    @Test
    fun compactReturnsNullWhenNoTombstonesQualify() {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        // Watermark below opA.lamport
        assertNull(s1.compact(watermark = opA.id.lamport - 1L))
    }

    // ---- structural predecessor invariant ----

    @Test
    fun compactBlockedWhenIdIsStructuralPredecessor() {
        // "a" is tombstoned and below watermark but b.after == a.id — cannot GC.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.insertAfter(alice, opA.id, "b")
        val (s2, _) = s1.removeAt(0)!! // remove "a"

        assertNull(s2.compact(watermark = opA.id.lamport),
            "Expected null: opA is structural predecessor of opB's Insert")
    }

    // ---- Compact op: apply / merge ----

    @Test
    fun applyCompactStripsOpsFromLog() {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val (_, delta) = s1.compact(watermark = opA.id.lamport)!!

        val applied = s1.apply(delta)

        assertEquals(emptyList(), applied.toList())
    }

    @Test
    fun applyCompactIsIdempotent() {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val (_, delta) = s1.compact(watermark = opA.id.lamport)!!

        val once = s1.apply(delta)
        val twice = once.apply(delta)

        assertEquals(once, twice)
    }

    @Test
    fun pieceCompactOpsUnionIds() {
        // Two tombstoned elements with no structural predecessors.
        // Peer A compacts only opA (lower watermark), Peer B compacts both.
        // Piece should union the ids so both are gone.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, RgaId.HEAD, "b") // b.after = HEAD
        val (s2, _) = s1.removeAt(s1.toList().indexOf("a"))!! // remove "a"
        val (s3, _) = s2.removeAt(s2.toList().indexOf("b"))!! // remove "b"

        // Both a and b are tombstoned with no successors — both GC-able.
        val (s4A, _) = s3.compact(watermark = opA.id.lamport)!!   // only GCs opA
        val (_, deltaB) = s3.compact(watermark = opB.id.lamport)!! // GCs both

        val merged = s4A.piece(s3.apply(deltaB))

        assertEquals(emptyList(), merged.toList())
    }

    @Test
    fun compactConvergesWhenOnePeerHasCompactedAndOtherHasnt() {
        // Alice: insert "a", remove "a", compact.
        // Bob: same pre-compact state (no compact yet).
        // After piece both directions, both converge to empty and same state.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val (s2A, _) = s1.compact(watermark = opA.id.lamport)!!

        val s2B = s1 // Bob hasn't compacted

        val mergedAB = s2A.piece(s2B)
        val mergedBA = s2B.piece(s2A)

        assertEquals(emptyList(), mergedAB.toList())
        assertEquals(emptyList(), mergedBA.toList())
        assertEquals(mergedAB, mergedBA)
    }

    @Test
    fun pieceTwoCompactedPeersConverge() {
        // Both peers compact to different depths, then merge — result has all GC applied.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, RgaId.HEAD, "b") // b.after = HEAD, concurrent with a
        val (s2, _) = s1.removeAt(s1.toList().indexOf("a"))!!
        val (s3, _) = s2.removeAt(s2.toList().indexOf("b"))!!

        val (s4A, _) = s3.compact(watermark = opA.id.lamport)!! // GCs opA only
        val (s4B, _) = s3.compact(watermark = opB.id.lamport)!! // GCs both

        val merged = s4A.piece(s4B)

        assertEquals(emptyList(), merged.toList())
    }

    // ---- Compact op: serialization round-trip ----

    @Test
    fun compactOpRoundTripsThroughJson() {
        val id1 = RgaId(lamport = 1L, replicaId = ReplicaId("alice"), seq = 1L)
        val id2 = RgaId(lamport = 2L, replicaId = ReplicaId("bob"), seq = 1L)
        val compact = RgaOp.Compact(setOf(id1, id2))

        val rgaOpSerializer = RgaOp.serializer(serializer<String>())
        val json = Json.encodeToString(rgaOpSerializer, compact)
        val decoded = Json.decodeFromString(rgaOpSerializer, json)

        assertEquals(compact, decoded)
    }
}
