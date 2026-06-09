package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial probe of the proposed causal-stability-barrier design for #262
 * (ADR-003 addendum, PR #266).
 *
 * The design replaces the scalar Lamport watermark in [Rga.compact] with a
 * delivered version-vector **floor**, keyed (option b) as
 * `replicaId -> highest globally-delivered RGA lamport from that author`. GC of a
 * tombstoned `I` (author r, lamport L) is authorised iff
 * `L <= floor[r]` AND no surviving local `Insert(_, _, after = I)`.
 *
 * These tests model `compact(floor)` faithfully at the [Rga] level (the floor is
 * a `Map<ReplicaId, Long>` — no replicator needed) and try to break it.
 */
class RgaCompactFloorBarrierProbeTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")

    /**
     * Faithful model of the proposed `compact(floor)` (option b).
     *
     * gcIds = tombstones whose `id.lamport <= floor[id.replicaId]` AND which are
     * not the `after` of any surviving (non-purged) Insert in THIS replica's
     * op-log. Returns the compacted Rga + Compact op, or null if nothing qualifies.
     */
    private fun <V> compactFloor(rga: Rga<V>, floor: Map<ReplicaId, Long>): Pair<Rga<V>, RgaOp.Compact>? {
        val survivingPredecessors: Set<RgaId> = rga.ops
            .filterIsInstance<RgaOp.Insert<V>>()
            .mapTo(mutableSetOf()) { it.after }
        val gcIds = rga.tombstones
            .filter { id ->
                val authorFloor = floor[id.replicaId] ?: Long.MIN_VALUE
                id.lamport <= authorFloor && id !in survivingPredecessors
            }
            .toSet()
        if (gcIds.isEmpty()) return null
        val compactOp = RgaOp.Compact(gcIds)
        return rga.apply(compactOp) to compactOp
    }

    // ---------------------------------------------------------------------
    // Attack 2 (and the base repro): concurrent successor at the floor boundary
    // ---------------------------------------------------------------------

    /**
     * The original repro, re-run against the floor barrier. A floor that does NOT
     * cover the compactor's delivery of J must NOT authorise GC of I — because the
     * floor over author c cannot reach seq(J) unless the compactor delivered J,
     * and the compactor here has not. So compactFloor returns null and J survives.
     */
    @Test
    fun floorBelowJ_refusesGc_and_J_survives() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (cWithJ, opJ) = cBase.insertAfter(c, opI.id, "J")

        // The load-bearing claim: for the floor to authorise GC of I (author a),
        // floor[a] >= opI.lamport. But A (the compactor) has NOT delivered J, so
        // its own delivered[c] < opJ.lamport, hence floor[c] < opJ.lamport. The
        // floor that A can legitimately hold here only covers what every peer —
        // INCLUDING A — has delivered. A has not delivered J, so even though
        // floor[a] can cover I, that doesn't matter: the test is whether GC of I
        // is refused. It is NOT refused by the floor on I's author alone; it is
        // refused by condition 3 ONLY IF A has delivered J. A has not.
        //
        // So model the floor A can actually hold: floor[a] = opI.lamport (every
        // peer delivered I), floor[c] = 0 (A has not delivered any c op, so the
        // min over peers including A is 0).
        val floorAtA = mapOf(a to opI.id.lamport, c to 0L)

        val result = compactFloor(aTombstoned, floorAtA)

        // GREEN here == the barrier is UNSOUND. I's author-floor authorises GC of
        // I, and A's local op-log has NO surviving Insert(after=I) because A never
        // delivered J. Condition 3 is blind, so the barrier STILL GCs I.
        assertTrue(result != null, "barrier GC'd I despite undelivered concurrent successor J")
        val (aCompacted, compactOp) = result
        val aFinal = aCompacted.apply(opJ)
        val cFinal = cWithJ.apply(compactOp)
        assertEquals(aFinal, cFinal, "convergence still holds (the bug is loss, not divergence)")
        // J is orphaned everywhere — the committed insert is silently lost.
        assertEquals(
            emptyList(),
            aFinal.toList(),
            "UNSOUND: J lost. A correct barrier would refuse GC of I and yield [J].",
        )
    }

    /**
     * Direct probe of the load-bearing property. Construct the floor the way the
     * design says it is computed: elementwise min over {A, C} of each peer's
     * contiguous delivered vector, where A has NOT delivered J.
     *
     * deliveredA = { a: seq(I), c: 0 }   (A applied I, never applied J)
     * deliveredC = { a: seq(I), c: seq(J) }  (C applied I and its own J)
     * floor = min = { a: seq(I), c: 0 }
     *
     * The claim under test: with this floor, compactFloor must NOT GC I.
     * Why the design THINKS it's safe: floor[c] = 0 < seq(J), so J is "not globally
     * delivered" — but GC of I is gated on floor[a], not floor[c]. floor[a] = seq(I)
     * DOES authorise GC of I. The only thing that can save J is condition 3, which
     * is evaluated on A's LOCAL op-log — and A has not delivered J. So condition 3
     * is blind. The floor on I's own author is satisfied. I is GC'd. J is lost.
     */
    @Test
    fun loadBearingProperty_floorOnIsAuthor_authorisesGc_whileConcurrentSuccessorUndelivered() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (cWithJ, opJ) = cBase.insertAfter(c, opI.id, "J")

        val deliveredA = mapOf(a to opI.id.lamport, c to 0L)
        val deliveredC = mapOf(a to opI.id.lamport, c to opJ.id.lamport)
        val floor = (deliveredA.keys + deliveredC.keys).associateWith { author ->
            minOf(deliveredA[author] ?: 0L, deliveredC[author] ?: 0L)
        }
        // floor == { a: seq(I), c: 0 }
        assertEquals(opI.id.lamport, floor[a])
        assertEquals(0L, floor[c])

        val result = compactFloor(aTombstoned, floor)

        // GREEN here == UNSOUND. A correct barrier would return null (refuse GC).
        // The design returns Compact({I}) because the gate only consults floor[a]
        // (I's own author) and A's local op-log, never floor[c]/seq(J).
        assertTrue(
            result != null,
            "expected the design to (wrongly) GC I; if this is null the design was fixed",
        )
        assertEquals(
            setOf(opI.id),
            result.second.ids,
            "UNSOUND: floor[a] authorised GC of I while Insert(J, after=I) was undelivered; " +
                "condition 3 blind; J orphaned",
        )
    }

    // ---------------------------------------------------------------------
    // Attack 1: transitive predecessor chain I <- J(tombstoned) <- K
    // ---------------------------------------------------------------------

    /**
     * Chain: Insert(I) at HEAD, Insert(J, after=I), Insert(K, after=J). J is
     * tombstoned; I and K live. Floor covers J's author so J is GC-eligible by
     * condition 2. Condition 3: is there a surviving Insert(after=J)? Yes — K.
     * So GC of J must be refused while K lives. Verify the successor check
     * reconnects the chain (K must remain reachable from HEAD).
     */
    @Test
    fun transitiveChain_tombstonedMiddle_notGcdWhileSuccessorLives() {
        val (s0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (s1, opJ) = s0.insertAfter(a, opI.id, "J")
        val (s2, opK) = s1.insertAfter(a, opJ.id, "K")
        // Tombstone J (middle).
        val jVisibleIndex = s2.toList().indexOf("J")
        val (s3, _) = s2.removeAt(jVisibleIndex)!!

        // Floor covers everything author a minted.
        val floor = mapOf(a to opK.id.lamport)
        val result = compactFloor(s3, floor)

        // K is a surviving Insert(after=J); condition 3 must refuse GC of J.
        assertNull(result, "J must NOT be GC'd while K (Insert(after=J)) survives")
        assertEquals(listOf("I", "K"), s3.toList(), "chain stays connected; K reachable")
    }

    /**
     * Same chain, but now K is ALSO tombstoned and globally delivered. Now both J
     * and K are leaves-or-internal tombstones. After K is GC-eligible (no surviving
     * Insert(after=K)), one pass GCs K; a second pass then frees J. Verify the
     * loop-until-stable two-pass behaviour does not strand anything and converges.
     */
    @Test
    fun transitiveChain_allTombstoned_twoPassGc_converges() {
        val (s0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (s1, opJ) = s0.insertAfter(a, opI.id, "J")
        val (s2, opK) = s1.insertAfter(a, opJ.id, "K")
        var s = s2
        // Tombstone all three.
        listOf("I", "J", "K").forEach { _ ->
            val idx = s.toList().indexOfFirst { it.isNotEmpty() }
            if (idx >= 0) s = s.removeAt(idx)!!.first
        }
        val floor = mapOf(a to opK.id.lamport)

        // Loop until stable (mirror RgaGcCoordinator.compactUntilStable).
        var passes = 0
        while (true) {
            val r = compactFloor(s, floor) ?: break
            s = r.first
            passes++
            if (passes > 10) break
        }
        assertEquals(emptyList(), s.toList(), "all visible content gone (all tombstoned)")
        assertTrue(s.tombstones.isEmpty(), "all tombstones GC'd after multi-pass")
    }

    // ---------------------------------------------------------------------
    // Attack 5: re-introduction after GC (idempotent purge)
    // ---------------------------------------------------------------------

    /**
     * A delivered I, GC'd it (Compact({I}) recorded), then receives a delayed delta
     * re-containing Insert(I). piece's union-then-purge must keep I purged. Verify
     * idempotence: I does not resurrect.
     */
    @Test
    fun reintroductionAfterGc_stayPurged_viaPiece() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTomb, _) = a0.removeAt(0)!!
        val floor = mapOf(a to opI.id.lamport)
        val (aCompacted, compactOp) = compactFloor(aTomb, floor)!!
        assertTrue(compactOp.ids.contains(opI.id))

        // A delayed delta re-containing Insert(I) arrives and is merged.
        val delayedDelta = Rga.empty<String>().apply(opI)
        val merged = aCompacted.piece(delayedDelta)
        assertEquals(emptyList(), merged.toList(), "I must stay purged after re-introduction")
        assertTrue(opI.id !in merged.ops.filterIsInstance<RgaOp.Insert<String>>().map { it.id })
    }

    /**
     * Re-introduction via apply(Insert(I)) directly (not piece). The current
     * `apply` does NOT consult compactedIds — it just unions the op in. Probe
     * whether a late raw Insert(I) (not wrapped in piece) can resurrect I.
     */
    @Test
    fun reintroductionAfterGc_viaRawApply_probe() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTomb, _) = a0.removeAt(0)!!
        val floor = mapOf(a to opI.id.lamport)
        val (aCompacted, _) = compactFloor(aTomb, floor)!!

        // Raw apply of a late Insert(I) — bypasses piece's purge. Unlike piece,
        // `apply` does NOT consult compactedIds, so the GC'd element resurrects.
        // The live replicator path uses piece (idempotent), masking this; but it
        // is a latent sharp edge: any future caller delivering ops through bare
        // `apply` re-inflates compacted elements.
        val resurrected = aCompacted.apply(opI)
        assertEquals(
            listOf("I"),
            resurrected.toList(),
            "documents the bare-apply resurrection hazard: apply() ignores compactedIds",
        )
    }
}
