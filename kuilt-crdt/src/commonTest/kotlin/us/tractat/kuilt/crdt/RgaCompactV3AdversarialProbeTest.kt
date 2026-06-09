package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FINAL adversarial probe of the v3 eviction-safe GC barrier (#262, PR #276).
 *
 * This reuses the **exact** v3 harness semantics from
 * [RgaCompactEvictionSafeBarrierTest] verbatim — `ceilWith` (retain merge),
 * `evict` (retain rule §4.3), `normalisedRetained` (release rule §4.4 R1/R2),
 * `frontierMax`, `stableCut`, and the `compactV3` predicate — so these probes
 * test the v3 MECHANISM, not a re-modelled approximation. If a probe GCs the
 * tombstone while a concurrent successor exists undelivered-to-self, the
 * mechanism is unsound.
 *
 * Attacks:
 *  - chained eviction C->B->D with R2 discharge in between (the lead attack).
 *  - stale live gossip discharging R2 on a peer that no longer witnesses the dot.
 *  - rejoin-with-data-loss: C crashes having lost J, rejoins with a FullState
 *    that does NOT cover seq(J).
 */
class RgaCompactV3AdversarialProbeTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")
    private val d = ReplicaId("d")

    private class Ctx {
        private val next = mutableMapOf<ReplicaId, Long>()
        val dotOf = mutableMapOf<RgaId, Dot>()
        fun assign(id: RgaId): Dot {
            val seq = (next[id.replicaId] ?: 0L) + 1L
            next[id.replicaId] = seq
            return Dot(id.replicaId, seq).also { dotOf[id] = it }
        }
    }

    // ---- v3 harness, copied verbatim from RgaCompactEvictionSafeBarrierTest ----

    private fun ceilWith(x: Map<ReplicaId, Long>, y: Map<ReplicaId, Long>): Map<ReplicaId, Long> =
        (x.keys + y.keys).associateWith { k -> maxOf(x[k] ?: 0L, y[k] ?: 0L) }

    private class Matrix(
        val delivered: Map<ReplicaId, Long>,
        val frontiers: Map<ReplicaId, Map<ReplicaId, Long>>,
        val retainedFrontier: Map<ReplicaId, Long> = emptyMap(),
    )

    private fun fLive(m: Matrix): Map<ReplicaId, Long> {
        val authors = m.frontiers.values.flatMap { it.keys }.toSet()
        return authors.associateWith { x -> m.frontiers.values.maxOf { vv -> vv[x] ?: 0L } }
    }

    private fun evict(m: Matrix, peer: ReplicaId): Matrix {
        val evictedRow = m.frontiers.getValue(peer)
        return Matrix(
            delivered = m.delivered,
            frontiers = m.frontiers - peer,
            retainedFrontier = ceilWith(m.retainedFrontier, evictedRow),
        )
    }

    private fun normalisedRetained(m: Matrix): Map<ReplicaId, Long> {
        val live = fLive(m)
        return m.retainedFrontier.filter { (x, s) ->
            s > maxOf(m.delivered[x] ?: 0L, live[x] ?: 0L)
        }
    }

    private fun frontierMax(m: Matrix): Map<ReplicaId, Long> =
        ceilWith(fLive(m), normalisedRetained(m))

    private fun stableCut(m: Matrix): Map<ReplicaId, Long> {
        val authors = m.frontiers.values.flatMap { it.keys }.toSet()
        return authors.associateWith { x -> m.frontiers.values.minOf { vv -> vv[x] ?: 0L } }
    }

    private fun <V> compactV3(rga: Rga<V>, ctx: Ctx, m: Matrix): Pair<Rga<V>, RgaOp.Compact>? {
        val f = frontierMax(m)
        val caughtUp = f.all { (x, fx) -> (m.delivered[x] ?: 0L) >= fx }
        if (!caughtUp) return null
        val s = stableCut(m)
        val survivingPredecessors: Set<RgaId> = rga.ops
            .filterIsInstance<RgaOp.Insert<V>>()
            .mapTo(mutableSetOf()) { it.after }
        val gcIds = rga.tombstones
            .filter { id ->
                val dot = ctx.dotOf.getValue(id)
                dot.seq <= (s[dot.replica] ?: 0L) && id !in survivingPredecessors
            }
            .toSet()
        if (gcIds.isEmpty()) return null
        val op = RgaOp.Compact(gcIds)
        return rga.apply(op) to op
    }

    /** A inserts I@HEAD + removes it; C mints J=Insert(J, after=I) concurrent, undelivered to A. */
    private fun scenario(): Triple<Ctx, Rga<String>, RgaOp.Insert<String>> {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        ctx.assign(opJ.id)
        return Triple(ctx, aTombstoned, opJ)
    }

    // ---------------------------------------------------------------------
    // LEAD ATTACK: chained eviction C -> B -> D with R2 discharge between.
    //
    // C minted J. B had delivered J (F_live[c]=seqJ via B), so R2 makes the
    // retained[c] entry redundant. Now evict B *before A delivers J*. Does B's
    // eviction re-capture seqJ into retained BEFORE F_live[c] drops?
    // ---------------------------------------------------------------------
    @Test
    fun chainedEviction_R2discharged_thenWitnessEvicted_mustStillRefuse() {
        val (ctx, aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        // A has NOT delivered J. B HAS delivered J. C is the author of J, live.
        val deliveredA = mapOf(a to seqI, b to 0L, c to 0L, d to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(
                a to deliveredA,
                b to mapOf(a to seqI, c to seqJ),      // B witnesses (c,seqJ)
                c to mapOf(a to seqI, c to seqJ),      // C (author) witnesses it
                d to mapOf(a to seqI, c to 0L),        // D never saw J
            ),
        )
        assertNull(compactV3(aTomb, ctx, live), "all live, undelivered J: refused (sanity)")

        // Evict C first. Retain rule folds C's row -> retained[c]=seqJ. But R2
        // immediately discharges it because B (live) still witnesses (c,seqJ).
        val afterC = evict(live, c)
        assertEquals(emptyMap(), normalisedRetained(afterC), "R2: B live-witnesses (c,seqJ); retained[c] discharged")
        assertNull(compactV3(aTomb, ctx, afterC), "still refused: F_live[c]=seqJ via B")

        // NOW evict B (the sole remaining live witness of (c,seqJ)) before A delivers J.
        // The lead-attack question: does evict() re-fold B's row into retained
        // BEFORE F_live drops, or is there a window where F[c] dips below seqJ?
        val afterCB = evict(afterC, b)
        // evict() captures the EVICTED peer's full row, including (c,seqJ).
        assertEquals(seqJ, afterCB.retainedFrontier[c], "evict(B) re-captured (c,seqJ) into retained")
        assertEquals(seqJ, frontierMax(afterCB)[c], "F[c] held at seqJ across the B eviction")
        assertNull(
            compactV3(aTomb, ctx, afterCB),
            "LEAD ATTACK DEFEATED: chained eviction keeps F[c]=seqJ; GC refused; I survives",
        )

        // Chain further: evict D too. D's row had c:0, so it adds nothing; retained still holds seqJ.
        val afterCBD = evict(afterCB, d)
        assertEquals(seqJ, frontierMax(afterCBD)[c], "F[c] still seqJ after D eviction")
        assertNull(compactV3(aTomb, ctx, afterCBD), "still refused after full chain C->B->D")

        assertEquals(listOf("J"), aTomb.apply(opJ).toList(), "no loss: J lands on the never-purged I")
    }

    // ---------------------------------------------------------------------
    // STALE-R2 ATTACK: B's gossiped frontier reflects J (so R2 fires), but the
    // design's R2 discharge is justified ONLY because the retain rule re-captures
    // on the next eviction. Probe the order *within a single combined update*:
    // what if we discharge retained via R2 against B, and B's row is REMOVED in
    // the SAME logical step (the normalise-then-evict ordering)?
    //
    // We model the adversarial ordering: normalise (R2 drops retained[c]) THEN
    // evict B *without* re-running the retain rule capture. This is the WRONG
    // wiring; we show it loses, to pin the required invariant.
    // ---------------------------------------------------------------------
    @Test
    fun staleOrdering_normaliseThenEvictWithoutRecapture_LOSES_pinsRequiredInvariant() {
        val (ctx, aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, b to 0L, c to 0L)
        // After C already evicted-and-retained, but B live-witnesses -> retained[c] discharged.
        val retainedDischarged = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(
                a to deliveredA,
                b to mapOf(a to seqI, c to seqJ),
            ),
            retainedFrontier = emptyMap(), // already normalised away by R2 against B
        )
        assertNull(compactV3(aTomb, ctx, retainedDischarged), "refused: B live carries seqJ")

        // CORRECT wiring: evicting B runs the retain rule -> re-captures (c,seqJ).
        val correct = evict(retainedDischarged, b)
        assertEquals(seqJ, frontierMax(correct)[c], "correct wiring re-captures on eviction")
        assertNull(compactV3(aTomb, ctx, correct), "correct wiring still refuses")

        // WRONG wiring (the invariant we must forbid): drop B's row WITHOUT the
        // retain-rule capture (e.g. an impl that normalises retained, observes it
        // empty, then removes the row in a path that skips ceilWith).
        val wrong = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA), // B removed, NO retain capture
            retainedFrontier = emptyMap(),
        )
        val result = compactV3(aTomb, ctx, wrong)
        assertTrue(
            result != null,
            "WRONG wiring purges I — proves retain-capture-on-eviction is a REQUIRED invariant",
        )
        assertEquals(emptyList(), aTomb.apply(opJ).let { it.apply(result.second) }.toList(),
            "under wrong wiring J is orphaned — this is what #267-270 must not implement")
    }

    // ---------------------------------------------------------------------
    // REJOIN WITH DATA LOSS: C crashes having LOST J (no peer ever got it),
    // rejoins with a fresh FullState whose VV does NOT cover seqJ. retained[c]
    // must PERSIST (safe liveness pin), never be wrongly cleared.
    // ---------------------------------------------------------------------
    @Test
    fun rejoinWithDataLoss_freshVVdoesNotCoverJ_retainedPersists_safe() {
        val (ctx, aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
        )
        val evicted = evict(live, c)
        assertEquals(seqJ, evicted.retainedFrontier[c], "retained[c]=seqJ after eviction")

        // C rejoins but had LOST J (case b): its fresh delivered VV has c:0, not seqJ.
        val rejoinedLossy = Matrix(
            delivered = deliveredA, // A still hasn't delivered J (no one has it)
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to 0L)), // fresh, J-less
            retainedFrontier = evicted.retainedFrontier,
        )
        // R2 must NOT fire: fresh live row has c:0 < seqJ, does not dominate retained.
        assertEquals(
            mapOf(c to seqJ),
            normalisedRetained(rejoinedLossy),
            "retained[c]=seqJ PERSISTS — lossy rejoin does NOT discharge it (no live witness)",
        )
        assertEquals(seqJ, frontierMax(rejoinedLossy)[c], "F[c] still seqJ")
        assertNull(
            compactV3(aTomb, ctx, rejoinedLossy),
            "SAFE: I refused even after lossy rejoin — this is the (b) unbounded liveness pin, not data loss",
        )
    }

    // ---------------------------------------------------------------------
    // Re-run #275 against v3 + confirm the differential: revert F to F_live and
    // the eviction probe must re-fail (purge), pinning eviction safety.
    // ---------------------------------------------------------------------
    @Test
    fun differential_revertToFLive_repurgesUnderEviction() {
        val (ctx, aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = ctx.dotOf.getValue(opJ.id).seq
        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
        )
        val evicted = evict(live, c)

        // v3 (F = max(F_live, retained)): refused.
        assertNull(compactV3(aTomb, ctx, evicted), "v3 refuses post-eviction")

        // Differential: F = F_live only (the v2 bug). Re-run the predicate by hand.
        val fLiveOnly = fLive(evicted)
        val caughtUp = fLiveOnly.all { (x, fx) -> (evicted.delivered[x] ?: 0L) >= fx }
        assertTrue(caughtUp, "v2 F_live is blind to (c,seqJ) post-eviction -> condition 3 passes")
        assertEquals(0L, fLiveOnly[c] ?: 0L, "F_live[c] fell to 0 — the eviction hole")
        // So v2 would purge; v3's retained floor is exactly what closes it.
        assertTrue(seqJ > 0L)
    }
}
