package us.tractat.kuilt.quilter

import us.tractat.kuilt.crdt.*

import us.tractat.kuilt.quilter.contiguousFrontier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial audit of the RGA GC **delivery-tracking** layer (audit/delivery-tracking-262).
 *
 * Sibling-hunt for the #284 class of bug (`causalDots` dropping `Compact`'d dots and
 * permanently pinning the contiguous frontier). Each probe targets a delivery-tracking
 * invariant the #284 fix did **not** directly exercise:
 *
 *  - H1 merge of two DIFFERENT `Compact`s (each GC'd a different dot of the same author).
 *  - H2 FullState round-trip frontier equality (sender vs reconstructed peer).
 *  - H3 Remove + Compact counted exactly once, incl. orphan-Remove after a merge that
 *       brings a Compact for a third author.
 *  - H5 idempotency / convergence of `causalDots` under re-delivery (Compact twice;
 *       Insert raw-applied after its Compact already arrived).
 *  - H6 compact predicate edges — compact-twice (loop-until-stable) never double-counts
 *       or loses a dot.
 *
 * Every probe here is expected to PASS — it pins a sound invariant. A failure means a
 * latent #284 sibling. The frontier helper [contiguousFrontier] is the same fold the
 * [us.tractat.kuilt.crdt.replicator.SeamReplicator] uses for `deliveredLocal`.
 */
class RgaDeliveryTrackingAuditTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")

    private fun frontier(rga: Rga<*>): VersionVector = contiguousFrontier(rga.causalDots())

    /** Three inserts by [a] at HEAD → dense dots (a,1),(a,2),(a,3). Returns the rga + ids. */
    private fun threeByA(): Triple<Rga<String>, List<RgaId>, Rga<String>> {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, RgaId.HEAD, "y")
        val (r3, op3) = r2.insertAfter(a, RgaId.HEAD, "z")
        return Triple(r3, listOf(op1.id, op2.id, op3.id), r1)
    }

    private fun gcOne(rga: Rga<String>, id: RgaId): Rga<String> {
        val tomb = rga.removeAt(rga.sequence.filter { it !in rga.tombstones }.indexOf(id))!!.first
        val full = VersionVector.of(mapOf(a to 3L, b to 9L, c to 9L))
        return tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!.first
    }

    // ---- H1: merge of two DIFFERENT Compacts of the same author ----

    @Test
    fun h1_mergeOfDistinctCompactsPreservesGapFreeFrontier() {
        // Two replicas branch from the same 3-insert base; A GCs (a,1), B GCs (a,3).
        val (base, ids, _) = threeByA()
        val aSide = gcOne(base, ids[0]) // Compact{(a,1)}
        val bSide = gcOne(base, ids[2]) // Compact{(a,3)}

        val merged = aSide.piece(bSide)

        // Both Compact'd dots re-emit; the two surviving Inserts cover the rest. Frontier = 3.
        assertEquals(
            setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)),
            merged.causalDots(),
            "merge of distinct Compacts must union both GC'd dots — no dropped dot",
        )
        assertEquals(3L, frontier(merged)[a], "gap-free frontier survives a merge of distinct Compacts")
        // commutativity of the merge on the delivery surface
        assertEquals(merged.causalDots(), bSide.piece(aSide).causalDots(), "piece is commutative on causalDots")
    }

    @Test
    fun h1_mergeWhereOnlyOneSideCompactedAStillConverges() {
        // A GC'd (a,2); B never compacted (still holds all three Inserts).
        val (base, ids, _) = threeByA()
        val aSide = gcOne(base, ids[1]) // Compact{(a,2)}, Insert(a,2) purged
        val merged = aSide.piece(base)

        // piece purges (a,2)'s Insert from the union, but the Compact re-emits the dot.
        assertEquals(setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)), merged.causalDots())
        assertEquals(3L, frontier(merged)[a], "uncompacted side must not re-inflate nor lose the frontier")
    }

    // ---- H2: FullState round-trip frontier equality ----

    @Test
    fun h2_fullStateReconstructionMatchesSenderFrontier() {
        // Sender: 3 inserts, middle GC'd. A peer reconstructs from the FullState (== the
        // whole Rga value the replicator ships in ReplicatorMessage.FullState).
        val (base, ids, _) = threeByA()
        val sender = gcOne(base, ids[1])

        // A FullState is just `state` — a value handed across. Reconstruct via empty.piece(state)
        // (the merge path onFullState takes) AND via raw value identity.
        val viaMerge = Rga.empty<String>().piece(sender)

        assertEquals(
            frontier(sender),
            frontier(viaMerge),
            "a peer reconstructing the already-compacted FullState must derive the SAME frontier",
        )
        assertEquals(sender.causalDots(), viaMerge.causalDots(), "FullState round-trip preserves causalDots exactly")
        assertEquals(3L, frontier(viaMerge)[a])
    }

    @Test
    fun h2_lateJoinerFullStateThenLiveCompactConverges() {
        // Joiner takes FullState BEFORE a GC, then receives the Compact delta live.
        val (base, ids, _) = threeByA()
        val joiner = Rga.empty<String>().piece(base) // full state, uncompacted
        assertEquals(3L, frontier(joiner)[a])

        // Sender GCs (a,1) and broadcasts the Compact delta; joiner applies it.
        val tomb = base.removeAt(base.sequence.indexOf(ids[0]))!!.first
        val full = VersionVector.of(mapOf(a to 3L))
        val (_, compactOp) = tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!
        val joinerAfter = joiner.apply(compactOp)

        assertEquals(3L, frontier(joinerAfter)[a], "live Compact after a full-state join keeps the frontier")
        assertEquals(setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)), joinerAfter.causalDots())
    }

    // ---- H3: Remove + Compact counted exactly once ----

    @Test
    fun h3_removeThenCompactCountsDotOnceNeverDouble() {
        val (base, ids, _) = threeByA()
        val compacted = gcOne(base, ids[0]) // (a,1) tombstoned then GC'd
        val dots = compacted.causalDots()
        // exactly one occurrence of (a,1) — set semantics guarantee no double, presence guarantees no zero
        assertTrue(Dot(a, 1L) in dots, "GC'd-then-removed dot still present (via Compact)")
        assertEquals(3, dots.size, "no extra dot introduced by the Remove+Compact pair")
    }

    @Test
    fun h3_orphanRemoveStillExcludedAfterMergeBringingThirdAuthorCompact() {
        // Holder has an orphan Remove(a,1) (Insert absent) AND merges a state that carries a
        // Compact for a DIFFERENT author c. The orphan dot must STILL be excluded — the
        // unrelated Compact must not resurrect (a,1) into causalDots.
        val (r1, _) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x") // (a,1)
        val (_, remA) = r1.removeAt(0)!!
        val orphan = Rga.empty<String>().apply(remA) // Remove(a,1), no Insert
        assertEquals(emptySet(), orphan.causalDots(), "baseline: orphan Remove excluded")

        // Build a third-author Compact: c inserts, removes, GCs (c,1).
        val (c0, cIns) = Rga.empty<String>().insertAfter(c, RgaId.HEAD, "C")
        val (c1, _) = c0.removeAt(0)!!
        val cFull = VersionVector.of(mapOf(c to 1L))
        val cCompacted = c1.compact(stableCut = cFull, frontierMax = cFull, delivered = cFull)!!.first

        val merged = orphan.piece(cCompacted)
        assertEquals(
            setOf(Dot(c, 1L)),
            merged.causalDots(),
            "merging an unrelated third-author Compact must not resurrect the orphan-Remove dot (a,1)",
        )
        assertTrue(Dot(a, 1L) !in merged.causalDots(), "(a,1) absent from the merged delivery surface")
    }

    @Test
    fun h3_orphanRemoveThenLateInsertArrivesClaimsDotOnce() {
        // Orphan Remove(a,1) first, then the late Insert(a,1) arrives. Now the dot IS delivered.
        val (r1, insA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (_, remA) = r1.removeAt(0)!!
        val afterRemoveThenInsert = Rga.empty<String>().apply(remA).apply(insA)
        assertEquals(
            setOf(Dot(a, 1L)),
            afterRemoveThenInsert.causalDots(),
            "once the Insert lands, the dot is claimed exactly once regardless of Remove ordering",
        )
        assertEquals(1L, frontier(afterRemoveThenInsert)[a])
    }

    // ---- H5: idempotency / convergence under re-delivery ----

    @Test
    fun h5_applyingCompactTwiceIsIdempotentOnCausalDots() {
        val (base, ids, _) = threeByA()
        val tomb = base.removeAt(base.sequence.indexOf(ids[1]))!!.first
        val full = VersionVector.of(mapOf(a to 3L))
        val (compacted, op) = tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!

        val once = compacted.causalDots()
        val twice = compacted.apply(op).causalDots() // re-deliver the same Compact
        assertEquals(once, twice, "re-delivering a Compact must not change causalDots")
        assertEquals(3L, frontier(compacted.apply(op))[a])
    }

    @Test
    fun h5_insertRawAppliedAfterItsCompactDoesNotResurrectButStaysCounted() {
        // The #267 apply-consults-compactedIds path: a late raw Insert of an already-GC'd id
        // must NOT be re-added; the Compact already re-emits its dot, so the frontier holds.
        val (base, ids, _) = threeByA()
        val tomb = base.removeAt(base.sequence.indexOf(ids[1]))!!.first
        val full = VersionVector.of(mapOf(a to 3L))
        val (compacted, _) = tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!

        // The original Insert(a,2) op, redelivered late.
        val lateInsert = RgaOp.Insert(ids[1], value = "y", after = RgaId.HEAD)
        val resurrected = compacted.apply(lateInsert)

        assertEquals(
            compacted.causalDots(),
            resurrected.causalDots(),
            "late raw apply of a compacted Insert must not change the delivery surface",
        )
        assertEquals(3L, frontier(resurrected)[a], "frontier unchanged by the late raw Insert")
        assertEquals(compacted.toList(), resurrected.toList(), "value not resurrected")
    }

    // ---- H6: compact predicate edges — loop-until-stable ----

    @Test
    fun h6_compactTwiceNeverDoubleCountsOrLosesADot() {
        // GC (a,1) in round one, then (a,3) in round two on the result. Each Compact accrues.
        val (base, ids, _) = threeByA()
        val full = VersionVector.of(mapOf(a to 3L))

        val tomb1 = base.removeAt(base.sequence.indexOf(ids[0]))!!.first
        val (r1, _) = tomb1.compact(stableCut = full, frontierMax = full, delivered = full)!!
        assertEquals(3L, frontier(r1)[a])

        val tomb2 = r1.removeAt(r1.sequence.filter { it !in r1.tombstones }.indexOf(ids[2]))!!.first
        val (r2, _) = tomb2.compact(stableCut = full, frontierMax = full, delivered = full)!!

        assertEquals(
            setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)),
            r2.causalDots(),
            "two GC rounds accrue both dots — neither lost, neither doubled",
        )
        assertEquals(3L, frontier(r2)[a], "frontier stable across two compaction rounds")
    }

    // ---- Sharp corners surfaced during the audit ----

    @Test
    fun deliveredLocalNeverRegressesAcrossAPurgingMerge() {
        // The #284 sibling to fear: a merge that PURGES Insert dots while lowering the frontier.
        // piece only purges ids covered by a Compact in the union, and causalDots re-emits those
        // Compact dots — so the contiguous frontier is monotonic across any merge. Pin it.
        val (base, ids, _) = threeByA()
        val full = VersionVector.of(mapOf(a to 3L))

        val before = frontier(base)[a]
        // A peer that has GC'd ALL THREE of a's dots (Compact{(a,1),(a,2),(a,3)}), all Inserts purged.
        var tomb = base
        listOf(0, 1, 2).forEach { i ->
            val idx = tomb.sequence.filter { it !in tomb.tombstones }.indexOf(ids[i])
            tomb = tomb.removeAt(idx)!!.first
        }
        val fullyCompacted = tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!.first
        assertEquals(emptyList(), fullyCompacted.toList(), "all visible elements GC'd")

        val merged = base.piece(fullyCompacted)
        assertTrue(frontier(merged)[a] >= before, "frontier must not regress when a fully-compacted peer merges in")
        assertEquals(3L, frontier(merged)[a], "and it holds at the full high-water (3), not below")
    }

    @Test
    fun compactDotForAnUnseenAuthorDoesNotOverClaimWithAGap() {
        // A Compact can carry dots for an author the receiver never delivered an Insert from
        // (a late joiner absorbs a peer's already-compacted state). causalDots emits those dots
        // verbatim — and contiguousFrontier must apply the SAME gap rule to them. If b's only
        // Compact'd dot is (b,2) with no (b,1), the receiver must read b as 0, never 2.
        val (b0, bIns1) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "b1") // (b,1)
        val (b1, bIns2) = b0.insertAfter(b, RgaId.HEAD, "b2")                  // (b,2)
        // Tombstone + GC ONLY (b,2): a non-contiguous Compact relative to a holder missing (b,1).
        val idx = b1.sequence.filter { it !in b1.tombstones }.indexOf(bIns2.id)
        val tomb = b1.removeAt(idx)!!.first
        val full = VersionVector.of(mapOf(b to 2L))
        val (_, compactOp) = tomb.compact(stableCut = full, frontierMax = full, delivered = full)!!
        assertEquals(setOf(Dot(b, 2L)), compactOp.positions.keys.map { it.dot }.toSet(), "Compact carries only (b,2)")

        // A receiver that has NOT delivered (b,1) applies this Compact in isolation.
        val receiver = Rga.empty<String>().apply(compactOp)
        assertEquals(setOf(Dot(b, 2L)), receiver.causalDots(), "the Compact'd dot is exported")
        assertEquals(
            0L,
            frontier(receiver)[b],
            "a Compact'd dot at seq 2 with no seq 1 must NOT advance the frontier — gap rule applies to Compact dots too",
        )
    }

    @Test
    fun h6_compactReturnsNullWhenNothingQualifiesLeavingDeliverySurfaceIntact() {
        // No tombstones → compact yields null and the frontier is unchanged. A non-null result
        // that broke the frontier would be the bug; null here is the sound edge.
        val (base, _, _) = threeByA()
        val full = VersionVector.of(mapOf(a to 3L))
        assertNull(base.compact(stableCut = full, frontierMax = full, delivered = full), "nothing tombstoned ⇒ null")
        assertEquals(3L, frontier(base)[a])
    }
}
