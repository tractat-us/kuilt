package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial regression probes for the v3 eviction-safe GC barrier (#262, PR #276;
 * adversarial review PR #277), exercising the **real** [Rga.compact] predicate.
 *
 * The matrix-clock bookkeeping ([Matrix], [evict], [normalisedRetained],
 * [frontierMax], [stableCut]) is modelled here — that is the replicator's job
 * (#268–270). The **predicate under test is the production [Rga.compact]**: each
 * probe derives `S`, `F`, `delivered` as [VersionVector]s and hands them to it.
 *
 * Attacks (all must REFUSE GC while a concurrent successor exists undelivered-to-self):
 *  - chained eviction C→B→D with R2 discharge in between (the lead attack).
 *  - `staleOrdering`: pins invariant (W1) — the WRONG wiring (drop a witness row
 *    WITHOUT the retain-rule capture) purges I and orphans J. This is the failure
 *    #267–270 must not implement; the probe makes it a live regression.
 *  - rejoin-with-data-loss: C crashes having lost J, rejoins J-less → retained
 *    persists (the §7(b) safe liveness pin), never wrongly cleared.
 *  - differential: revert F to `F_live` (the v2 bug) and the eviction hole reopens.
 */
class RgaCompactV3AdversarialProbeTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")
    private val d = ReplicaId("d")

    // ---- v3 matrix-clock model (replicator bookkeeping; predicate is real) ----

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

    private fun frontierMaxRaw(m: Matrix): Map<ReplicaId, Long> =
        ceilWith(fLive(m), normalisedRetained(m))

    private fun frontierMax(m: Matrix): VersionVector = VersionVector.of(frontierMaxRaw(m))

    private fun stableCut(m: Matrix): VersionVector {
        val authors = m.frontiers.values.flatMap { it.keys }.toSet()
        return VersionVector.of(authors.associateWith { x -> m.frontiers.values.minOf { vv -> vv[x] ?: 0L } })
    }

    /** Hands the matrix-derived VVs to the **real** [Rga.compact]. */
    private fun <V> compactV3(rga: Rga<V>, m: Matrix): Pair<Rga<V>, RgaOp.Compact>? =
        rga.compact(
            stableCut = stableCut(m),
            frontierMax = frontierMax(m),
            delivered = VersionVector.of(m.delivered),
        )

    /** A inserts I@HEAD + removes it; C mints J=Insert(J, after=I) concurrent, undelivered to A. */
    private fun scenario(): Pair<Rga<String>, RgaOp.Insert<String>> {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        return aTombstoned to opJ
    }

    // ---------------------------------------------------------------------
    // LEAD ATTACK: chained eviction C -> B -> D with R2 discharge between.
    // ---------------------------------------------------------------------
    @Test
    fun chainedEviction_R2discharged_thenWitnessEvicted_mustStillRefuse() {
        val (aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = opJ.id.seq

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
        assertNull(compactV3(aTomb, live), "all live, undelivered J: refused (sanity)")

        // Evict C first; R2 immediately discharges retained[c] because B still witnesses it.
        val afterC = evict(live, c)
        assertEquals(emptyMap(), normalisedRetained(afterC), "R2: B live-witnesses (c,seqJ); retained[c] discharged")
        assertNull(compactV3(aTomb, afterC), "still refused: F_live[c]=seqJ via B")

        // Evict B (sole remaining live witness) before A delivers J.
        val afterCB = evict(afterC, b)
        assertEquals(seqJ, afterCB.retainedFrontier[c], "evict(B) re-captured (c,seqJ) into retained")
        assertEquals(seqJ, frontierMax(afterCB)[c], "F[c] held at seqJ across the B eviction")
        assertNull(
            compactV3(aTomb, afterCB),
            "LEAD ATTACK DEFEATED: chained eviction keeps F[c]=seqJ; GC refused; I survives",
        )

        // Chain further: evict D (adds nothing); retained still holds seqJ.
        val afterCBD = evict(afterCB, d)
        assertEquals(seqJ, frontierMax(afterCBD)[c], "F[c] still seqJ after D eviction")
        assertNull(compactV3(aTomb, afterCBD), "still refused after full chain C->B->D")

        assertEquals(listOf("J"), aTomb.apply(opJ).toList(), "no loss: J lands on the never-purged I")
    }

    // ---------------------------------------------------------------------
    // STALE ORDERING (invariant (W1) pin): the WRONG wiring — drop a witness row
    // WITHOUT the retain-rule capture — purges I and orphans J. Live regression
    // for the retain-capture-before-drop atomicity #267-270 must implement.
    // ---------------------------------------------------------------------
    @Test
    fun staleOrdering_dropWitnessWithoutRecapture_LOSES_pinsRequiredInvariant() {
        val (aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = opJ.id.seq

        val deliveredA = mapOf(a to seqI, b to 0L, c to 0L)
        // B live-witnesses (c,seqJ); retained already discharged by R2 against B.
        val retainedDischarged = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, b to mapOf(a to seqI, c to seqJ)),
            retainedFrontier = emptyMap(),
        )
        assertNull(compactV3(aTomb, retainedDischarged), "refused: B live carries seqJ")

        // CORRECT wiring: evicting B runs the retain rule -> re-captures (c,seqJ).
        val correct = evict(retainedDischarged, b)
        assertEquals(seqJ, frontierMax(correct)[c], "correct wiring re-captures on eviction")
        assertNull(compactV3(aTomb, correct), "correct wiring still refuses")

        // WRONG wiring (the invariant we forbid): drop B's row WITHOUT retain capture.
        val wrong = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA), // B removed, NO retain capture
            retainedFrontier = emptyMap(),
        )
        val result = compactV3(aTomb, wrong)
        assertNotNull(
            result,
            "WRONG wiring purges I — proves retain-capture-on-eviction (W1) is a REQUIRED invariant",
        )
        // The purge is still the bug the (W1) invariant forbids — but with reroot-to-HEAD (#254)
        // the structural orphan is no longer *silent data loss*: J, whose purged predecessor I is
        // gone, resurfaces at HEAD rather than vanishing to []. The wrong wiring is still wrong
        // (it compacts a tombstone whose concurrent successor is undelivered-to-self), but reroot
        // makes the failure recoverable, not catastrophic.
        assertEquals(
            listOf("J"),
            aTomb.apply(opJ).apply(result.second).toList(),
            "even under wrong wiring J survives via reroot — the W1 violation is the spurious purge, not lost data",
        )
    }

    // ---------------------------------------------------------------------
    // REJOIN WITH DATA LOSS: C crashes having LOST J, rejoins J-less. retained[c]
    // must PERSIST (the §7(b) safe liveness pin), never wrongly cleared.
    // ---------------------------------------------------------------------
    @Test
    fun rejoinWithDataLoss_freshVVdoesNotCoverJ_retainedPersists_safe() {
        val (aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = opJ.id.seq

        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
        )
        val evicted = evict(live, c)
        assertEquals(seqJ, evicted.retainedFrontier[c], "retained[c]=seqJ after eviction")

        // C rejoins but had LOST J (case b): fresh delivered VV has c:0, not seqJ.
        val rejoinedLossy = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to 0L)), // fresh, J-less
            retainedFrontier = evicted.retainedFrontier,
        )
        assertEquals(
            mapOf(c to seqJ),
            normalisedRetained(rejoinedLossy),
            "retained[c]=seqJ PERSISTS — lossy rejoin does NOT discharge it (no live witness)",
        )
        assertEquals(seqJ, frontierMax(rejoinedLossy)[c], "F[c] still seqJ")
        assertNull(
            compactV3(aTomb, rejoinedLossy),
            "SAFE: I refused even after lossy rejoin — the (b) liveness pin, not data loss",
        )
    }

    // ---------------------------------------------------------------------
    // Differential: revert F to F_live (the v2 bug) and the eviction hole reopens.
    // ---------------------------------------------------------------------
    @Test
    fun differential_revertToFLive_repurgesUnderEviction() {
        val (aTomb, opJ) = scenario()
        val seqI = 1L
        val seqJ = opJ.id.seq
        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
        )
        val evicted = evict(live, c)

        // v3 (F = max(F_live, retained)): refused.
        assertNull(compactV3(aTomb, evicted), "v3 refuses post-eviction")

        // v2 differential: drop the retained floor → F = F_live only. The real
        // predicate now sees a frontier blind to (c,seqJ) and purges.
        val fLiveOnlyMatrix = Matrix(
            delivered = evicted.delivered,
            frontiers = evicted.frontiers,
            retainedFrontier = emptyMap(),
        )
        assertEquals(0L, fLive(fLiveOnlyMatrix)[c] ?: 0L, "F_live[c] fell to 0 — the eviction hole")
        val purged = compactV3(aTomb, fLiveOnlyMatrix)
        assertNotNull(purged, "v2 F_live is blind to (c,seqJ) post-eviction — predicate purges I")
        // The v2 hole still spuriously purges I (the #275 bug the retained floor closes). Reroot
        // (#254) means the resulting orphan is no longer silent loss — J resurfaces at HEAD — but
        // the predicate-level defect is identical: F_live blind to (c,seqJ) compacts I prematurely.
        assertEquals(
            listOf("J"),
            aTomb.apply(opJ).apply(purged.second).toList(),
            "J resurfaces via reroot — the v2 defect is the premature purge; v3's retained floor avoids it",
        )
        assertTrue(seqJ > 0L)
    }
}
