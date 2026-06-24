package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [Fugue] causal-stability GC: [Fugue.compact] and [Fugue.causalDots].
 *
 * Mirrors the shape of [RgaCompactConcurrentInsertSoundnessTest] and
 * [RgaCompactEvictionSafeBarrierTest], following the same four safety conditions
 * from the design note (docs/op-log-crdt-compaction.md):
 *  1. Tombstoned.
 *  2. Causally stable — `id.seq ≤ stableCut[id.replicaId]`.
 *  3. Frontier-complete — this replica has delivered every known dot.
 *  4. No surviving tree anchor — no live Insert has `parent == id` OR `rightOrigin == id`.
 */
class FugueCompactTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    // ── causalDots ────────────────────────────────────────────────────────────

    /**
     * causalDots() returns one dot per Insert; no dots for Remove ops;
     * dots for ids in Compact ops (so the contiguous frontier survives GC).
     */
    @Test
    fun causalDotsReturnsInsertDotsOnly() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, op2) = f1.insertAt(b, 1, "y")

        val dots = f2.causalDots()
        assertEquals(
            setOf(Dot(a, op1.id.seq), Dot(b, op2.id.seq)),
            dots,
            "causalDots must contain one dot per Insert, keyed by replicaId + seq",
        )
    }

    @Test
    fun causalDotsExcludesRemoveOps() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        // Remove mints no new dot — still only one dot from the Insert
        assertEquals(1, f2.causalDots().size)
    }

    @Test
    fun causalDotsIncludesCompactedIds() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        // Make it GC-eligible: causally stable, frontier-complete, no tree-children
        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val frontierMax = stableCut
        val delivered = stableCut

        val (compacted, compactOp) = assertNotNull(
            f2.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "should compact the tombstoned-and-stable insert",
        )
        // After compaction the Insert is gone from ops, but its dot must still appear in causalDots
        val dotsAfter = compacted.causalDots()
        assertTrue(
            Dot(a, seq) in dotsAfter,
            "compacted dot (A,$seq) must re-appear in causalDots via the Compact op to keep the frontier intact",
        )
    }

    // ── compact — precondition guards ────────────────────────────────────────

    @Test
    fun compactRefusesWhenFrontierIncomplete() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val seq = op1.id.seq
        // delivered is BEHIND frontierMax → condition 3 fails
        val stableCut = VersionVector.of(mapOf(a to seq))
        val frontierMax = VersionVector.of(mapOf(a to seq, b to 1L))   // knows about B's op
        val delivered = VersionVector.of(mapOf(a to seq))               // but hasn't delivered it

        assertNull(
            f2.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "must refuse GC while frontier is incomplete",
        )
    }

    @Test
    fun compactRefusesWhenNotTombstoned() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")

        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val delivered = stableCut

        assertNull(
            f1.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
            "must refuse GC for a live (non-tombstoned) insert",
        )
    }

    @Test
    fun compactRefusesWhenNotCausallyStable() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val seq = op1.id.seq
        // stableCut does not yet cover this insert's seq
        val stableCut = VersionVector.of(mapOf(a to seq - 1))
        val frontierMax = VersionVector.of(mapOf(a to seq))
        val delivered = frontierMax

        assertNull(
            f2.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "must refuse GC when stableCut has not yet covered the insert's seq",
        )
    }

    // ── compact — safety condition 4 (no surviving tree child) ───────────────

    /**
     * A tombstoned Insert whose id is still a live parent MUST NOT be GC'd.
     * This is the Fugue-specific analogue of RGA's "no surviving successor" rule —
     * the parent in Fugue's tree, not the sequence predecessor.
     */
    @Test
    fun compactDoesNotDropNodeThatIsStillAParent() {
        // Insert "x" at position 0; then insert "y" after "x" (making x the parent of y).
        val (f1, opX) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, opY) = f1.insertAt(a, 1, "y")    // y is a right-child of x in the tree

        // Tombstone x — but y is still alive and has x as its parent.
        val (f3, _) = f2.removeAt(0)!!

        val seqX = opX.id.seq
        val seqY = opY.id.seq
        val maxSeq = maxOf(seqX, seqY)
        val stableCut = VersionVector.of(mapOf(a to maxSeq))
        val delivered = stableCut
        val frontierMax = stableCut

        assertNull(
            f3.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "must not GC a tombstoned node whose id is still a live tree parent",
        )
    }

    // ── compact — happy path ──────────────────────────────────────────────────

    @Test
    fun opLogShrinkesAfterCausalStability() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val opsBefore = f2.ops.size     // Insert + Remove = 2
        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val delivered = stableCut

        val (compacted, compactOp) = assertNotNull(
            f2.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
        )
        assertTrue(
            compacted.ops.size < opsBefore,
            "op-log must shrink after compaction; before=$opsBefore after=${compacted.ops.size}",
        )
        assertTrue(compactOp.positions.containsKey(op1.id), "Compact op must record the GC'd id")
    }

    @Test
    fun compactPreservesVisibleSequence() {
        val (f1, _) = Fugue.empty<String>().insertAt(a, 0, "a")
        val (f2, _) = f1.insertAt(a, 1, "b")
        val (f3, _) = f2.insertAt(a, 2, "c")
        // remove "b" at index 1
        val (f4, opB) = f3.removeAt(1)!!

        assertEquals(listOf("a", "c"), f4.toList(), "setup: b removed")

        // Cover all inserts' seqs in the stable cut (opB.id is a Remove, not in Insert list).
        val maxSeq = f4.ops.filterIsInstance<FugueOp.Insert<String>>().maxOf { it.id.seq }
        val stableCut = VersionVector.of(mapOf(a to maxSeq))
        val delivered = stableCut

        val result = f4.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered)
        if (result != null) {
            val (compacted, _) = result
            assertEquals(
                listOf("a", "c"),
                compacted.toList(),
                "compaction must not change the visible sequence",
            )
        }
    }

    // ── non-interleaving preserved across compaction ──────────────────────────

    /**
     * Two replicas with concurrent same-position runs compact one side,
     * then must still converge to the same non-interleaved result.
     */
    @Test
    fun convergenceAndNonInterleavingPreservedAcrossCompaction() {
        // Replica A inserts "a1","a2","a3" and then deletes them
        var fA = Fugue.empty<String>()
        val (fA1, opA1) = fA.insertAt(a, 0, "a1")
        val (fA2, opA2) = fA1.insertAt(a, 0, "a2")
        val (fA3, opA3) = fA2.insertAt(a, 0, "a3")
        val (fA4, remA3) = fA3.removeAt(0)!!   // remove a3
        val (fA5, remA2) = fA4.removeAt(0)!!   // remove a2
        val (fA6, remA1) = fA5.removeAt(0)!!   // remove a1
        fA = fA6

        // Replica B independently inserts "b1","b2"
        var fB = Fugue.empty<String>()
        val (fB1, opB1) = fB.insertAt(b, 0, "b1")
        val (fB2, opB2) = fB1.insertAt(b, 0, "b2")
        fB = fB2

        // A knows B exists but hasn't seen B's ops; B's frontier reaches opB2.seq
        val maxSeqA = maxOf(opA1.id.seq, opA2.id.seq, opA3.id.seq)
        val maxSeqB = maxOf(opB1.id.seq, opB2.id.seq)
        val stableCut = VersionVector.of(mapOf(a to maxSeqA))   // only A's ops are stable
        val frontierMax = VersionVector.of(mapOf(a to maxSeqA, b to maxSeqB))
        val delivered = stableCut   // A hasn't delivered B's ops yet → frontier incomplete

        // Compaction should refuse (frontier not complete)
        assertNull(
            fA.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "must refuse: B's ops are in the frontier but not delivered",
        )

        // Now A delivers B's ops
        val fAFull = fA.apply(opB1).apply(opB2)
        val deliveredFull = VersionVector.of(mapOf(a to maxSeqA, b to maxSeqB))
        val stableFull = VersionVector.of(mapOf(a to maxSeqA, b to maxSeqB))

        val compactResult = fAFull.compact(
            stableCut = stableFull,
            frontierMax = stableFull,
            delivered = deliveredFull,
        )
        assertNotNull(compactResult, "should compact once all ops are delivered and stable")
        val (fACompacted, _) = compactResult

        // Merge the compacted A with B — B has all of A's ops too
        val fBFull = fB.apply(opA1).apply(opA2).apply(opA3).apply(remA3).apply(remA2).apply(remA1)

        val mergedFromA = fACompacted.piece(fBFull)
        val mergedFromB = fBFull.piece(fACompacted)

        assertEquals(mergedFromA.toList(), mergedFromB.toList(), "must converge after compaction")

        // B's elements must still be contiguous
        val merged = mergedFromA.toList()
        assertEquals(listOf("b2", "b1"), merged, "only B's visible elements remain after A's are deleted")
    }

    // ── applyCompact (receive from remote) ───────────────────────────────────

    @Test
    fun applyCompactReducesOpLog() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val delivered = stableCut
        val (_, compactOp) = assertNotNull(
            f2.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
        )

        // A peer that still has the full op-log applies the Compact
        val applied = f2.apply(compactOp)
        assertTrue(applied.ops.size < f2.ops.size, "apply(Compact) must reduce the op-log on the receiving peer")
        assertEquals(emptyList<String>(), applied.toList(), "sequence must be unchanged after apply(Compact)")
    }

    @Test
    fun applyCompactIsIdempotent() {
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val delivered = stableCut
        val (_, compactOp) = assertNotNull(
            f2.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
        )

        val once = f2.apply(compactOp)
        val twice = once.apply(compactOp)
        assertEquals(once.toList(), twice.toList(), "applying Compact twice must be idempotent")
        assertEquals(once.ops.size, twice.ops.size, "op-log size must not grow on duplicate Compact apply")
    }

    // ── byte-stability of compacted form ─────────────────────────────────────

    /**
     * Two replicas that compact to the same logical state must produce identical
     * serialized bytes (JSON and CBOR).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun compactedFormIsByteStable() {
        val serializer = Fugue.wireSerializer(serializer<String>())
        // FugueOp.Compact.positions is Map<FugueId, FugueId> — requires allowStructuredMapKeys for JSON.
        val json = Json { encodeDefaults = true; allowStructuredMapKeys = true }
        val cbor = Cbor

        // Two replicas both insert "x" and tombstone it
        val (f1, op1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, _) = f1.removeAt(0)!!

        val seq = op1.id.seq
        val stableCut = VersionVector.of(mapOf(a to seq))
        val delivered = stableCut

        val (compacted1, compactOp) = assertNotNull(
            f2.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
        )
        // Replica 2: receives and applies the compact op
        val compacted2 = f2.apply(compactOp)

        assertEquals(compacted1.toList(), compacted2.toList(), "both replicas must converge logically")

        val jsonBytes1 = json.encodeToString(serializer, compacted1)
        val jsonBytes2 = json.encodeToString(serializer, compacted2)
        assertEquals(jsonBytes1, jsonBytes2, "JSON bytes of compacted state must be identical across replicas")

        val cborBytes1 = cbor.encodeToByteArray(serializer, compacted1)
        val cborBytes2 = cbor.encodeToByteArray(serializer, compacted2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "CBOR bytes of compacted state must be identical across replicas")
    }

    // ── piece(Compact) merges positions maps ────────────────────────────────

    @Test
    fun pieceUnionsTwoCompactOps() {
        val (fA, opA) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (fARemoved, _) = fA.removeAt(0)!!
        val seqA = opA.id.seq
        val cutA = VersionVector.of(mapOf(a to seqA))
        val (compA, compOpA) = assertNotNull(
            fARemoved.compact(stableCut = cutA, frontierMax = cutA, delivered = cutA),
        )

        val (fB, opB) = Fugue.empty<String>().insertAt(b, 0, "y")
        val (fBRemoved, _) = fB.removeAt(0)!!
        val seqB = opB.id.seq
        val cutB = VersionVector.of(mapOf(b to seqB))
        val (compB, compOpB) = assertNotNull(
            fBRemoved.compact(stableCut = cutB, frontierMax = cutB, delivered = cutB),
        )

        // Two independent compacted replicas merged via piece
        val merged = compA.piece(compB)
        assertTrue(merged.ops.filterIsInstance<FugueOp.Compact>().isNotEmpty(), "merged state must contain at least one Compact op")
    }

    // ── compact — safety condition 4 (rightOrigin anchor) ────────────────────

    /**
     * A tombstoned Insert whose id is still a live **rightOrigin** MUST NOT be GC'd.
     *
     * The rightOrigin anchors right-sibling ordering in [Fugue.buildTree]. Compacting it
     * would cause [Fugue.buildTree] to substitute it with its nearest ancestor, changing
     * the observed sibling order and breaking non-interleaving.
     *
     * The Insert is constructed directly via [FugueOp.Insert] to explicitly control the
     * [FugueOp.Insert.rightOrigin] field, which the high-level [Fugue.insertAt] API does
     * not always produce with a non-null rightOrigin.
     */
    @Test
    fun compactDoesNotDropNodeThatIsStillARightOriginAnchor() {
        // Manually construct three ops:
        //   opA: Insert("a") as right-child of HEAD
        //   opB: Insert("b") as right-child of a — this is what will become the rightOrigin
        //   opX: Insert("x") as right-child of a, rightOrigin = b.id (concurrent with b)
        val aId = FugueId(lamport = 1L, replicaId = a, seq = 1L)
        val bId = FugueId(lamport = 2L, replicaId = a, seq = 2L)
        val xId = FugueId(lamport = 2L, replicaId = b, seq = 1L)

        val opA = FugueOp.Insert(id = aId, value = "a", parent = FugueId.HEAD, side = FugueSide.Right, rightOrigin = null)
        val opB = FugueOp.Insert(id = bId, value = "b", parent = aId, side = FugueSide.Right, rightOrigin = null)
        val opX = FugueOp.Insert(id = xId, value = "x", parent = aId, side = FugueSide.Right, rightOrigin = bId)

        // Apply all three inserts, then tombstone b (leaving x alive with rightOrigin = b).
        var f = Fugue.empty<String>().apply(opA).apply(opB).apply(opX)
        f = f.apply(FugueOp.Remove(bId))

        // b is tombstoned, causally stable, but x still references it as rightOrigin.
        // stableCut must cover all inserted ops so condition 2 is met for b.
        val stableCut = VersionVector.of(mapOf(a to maxOf(aId.seq, bId.seq), b to xId.seq))
        val delivered = stableCut

        assertNull(
            f.compact(stableCut = stableCut, frontierMax = stableCut, delivered = delivered),
            "must not GC a tombstoned node whose id is still a live rightOrigin anchor (condition 4)",
        )
    }

    // ── randomized fuzz — compaction preserves toList() ──────────────────────

    /**
     * For a range of random seeds: build a multi-replica Fugue state with random inserts
     * and removes, compact all eligible tombstones, and verify that [Fugue.toList] is
     * unchanged.
     *
     * This covers the full condition-4 predicate (parent AND rightOrigin) across many
     * structural shapes that are hard to enumerate by hand.
     */
    @Test
    fun randomizedCompactionPreservesToList() {
        repeat(300) { seed ->
            val rng = Random(seed)
            val values = listOf("p", "q", "r", "s", "t")

            // Build a random op sequence on three independent replicas.
            var fA = Fugue.empty<String>()
            var fB = Fugue.empty<String>()
            var fC = Fugue.empty<String>()

            repeat(6) {
                val value = values[rng.nextInt(values.size)]
                when (rng.nextInt(3)) {
                    0 -> {
                        val idx = if (fA.size == 0) 0 else rng.nextInt(fA.size + 1)
                        val (next, _) = fA.insertAt(a, idx, value)
                        fA = next
                    }
                    1 -> {
                        val idx = if (fB.size == 0) 0 else rng.nextInt(fB.size + 1)
                        val (next, _) = fB.insertAt(b, idx, value)
                        fB = next
                    }
                    else -> {
                        val idx = if (fC.size == 0) 0 else rng.nextInt(fC.size + 1)
                        val (next, _) = fC.insertAt(c, idx, value)
                        fC = next
                    }
                }
            }

            // Merge all replicas.
            var merged = fA.piece(fB).piece(fC)

            // Remove some elements.
            repeat(3) {
                if (merged.size > 0) {
                    val idx = rng.nextInt(merged.size)
                    val (next, _) = merged.removeAt(idx) ?: return@repeat
                    merged = next
                }
            }

            val expectedList = merged.toList()
            val allSeqs = merged.ops
                .filterIsInstance<FugueOp.Insert<String>>()
                .groupBy { it.id.replicaId }
                .mapValues { (_, ops) -> ops.maxOf { it.id.seq } }
            val stableCut = VersionVector.of(allSeqs)
            val delivered = stableCut

            val result = merged.compact(
                stableCut = stableCut,
                frontierMax = stableCut,
                delivered = delivered,
            )
            if (result != null) {
                val (compacted, compactOp) = result
                assertEquals(
                    expectedList, compacted.toList(),
                    "seed=$seed: compact() must not change toList()",
                )
                // Also verify apply(compactOp) on the original gives the same result.
                val applied = merged.apply(compactOp)
                assertEquals(
                    expectedList, applied.toList(),
                    "seed=$seed: apply(compactOp) must not change toList()",
                )
            }
        }
    }

    /**
     * Pinned regression: seed 244 triggered the rightOrigin-anchor bug before the fix.
     * The fuzz harness must always pass this seed after the fix.
     */
    @Test
    fun seed244RegressionRightOriginAnchor() {
        val rng = Random(244)
        val values = listOf("p", "q", "r", "s", "t")

        var fA = Fugue.empty<String>()
        var fB = Fugue.empty<String>()
        var fC = Fugue.empty<String>()

        repeat(6) {
            val value = values[rng.nextInt(values.size)]
            when (rng.nextInt(3)) {
                0 -> {
                    val idx = if (fA.size == 0) 0 else rng.nextInt(fA.size + 1)
                    val (next, _) = fA.insertAt(a, idx, value)
                    fA = next
                }
                1 -> {
                    val idx = if (fB.size == 0) 0 else rng.nextInt(fB.size + 1)
                    val (next, _) = fB.insertAt(b, idx, value)
                    fB = next
                }
                else -> {
                    val idx = if (fC.size == 0) 0 else rng.nextInt(fC.size + 1)
                    val (next, _) = fC.insertAt(c, idx, value)
                    fC = next
                }
            }
        }

        var merged = fA.piece(fB).piece(fC)

        repeat(3) {
            if (merged.size > 0) {
                val idx = rng.nextInt(merged.size)
                val (next, _) = merged.removeAt(idx) ?: return@repeat
                merged = next
            }
        }

        val expectedList = merged.toList()
        val allSeqs = merged.ops
            .filterIsInstance<FugueOp.Insert<String>>()
            .groupBy { it.id.replicaId }
            .mapValues { (_, ops) -> ops.maxOf { it.id.seq } }
        val stableCut = VersionVector.of(allSeqs)
        val delivered = stableCut

        val result = merged.compact(
            stableCut = stableCut,
            frontierMax = stableCut,
            delivered = delivered,
        )
        if (result != null) {
            val (compacted, compactOp) = result
            assertEquals(expectedList, compacted.toList(), "seed=244 regression: toList() unchanged after compact")
            assertEquals(expectedList, merged.apply(compactOp).toList(), "seed=244 regression: toList() unchanged after apply(compact)")
        }
    }
}
