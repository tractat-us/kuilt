package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Model-level validation of the **eviction-safe** causal-stability GC barrier for
 * RGA (ADR-003 addendum **v3**, #262).
 *
 * v2 (PR #274) was sound for fixed membership but unsound under eviction (PR #275):
 * `F = max over LIVE peers` drops the evicted peer's row, so a frontier that
 * witnessed a concurrent `Insert(J, after=I)` can fall below `seq(J)`, condition 3
 * passes blind, and `I` is purged → `J` orphans. v3 retains evicted peers' frontier
 * knowledge as a **monotonic floor on F** (`retainedFrontier`), with a precise
 * release rule (§4.4): a retained entry is discharged only when self has delivered
 * it (R1) or a live peer already witnesses it (R2).
 *
 * This harness models the **whole v3 mechanism** — not a hand-tuned `F`. It holds
 * the live matrix [Matrix.frontiers], a separate [Matrix.retainedFrontier], and the
 * compactor's [Matrix.delivered]; `evict` runs the retain rule; every query
 * normalises retained against (R1)/(R2). `F` consulted by condition 3 is
 * `max(F_live, retainedFrontier)`. If the mechanism is wrong, the probes fail.
 *
 * Probes, all asserting the v3 verdict:
 *  - #272 author-independence (fixed membership)  → GC refused.
 *  - #275 eviction                                → GC refused (the fix).
 *  - eviction-then-rejoin                         → safe; unpins for the right reason.
 *  - eviction where J reached a LIVE peer         → safe (retained not even needed).
 */
class RgaCompactEvictionSafeBarrierTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")

    /** Maps each [RgaId] to its causal dot (the per-author seq v3 adds to RgaId). */
    private class Ctx {
        private val next = mutableMapOf<ReplicaId, Long>()
        val dotOf = mutableMapOf<RgaId, Dot>()
        fun assign(id: RgaId): Dot {
            val seq = (next[id.replicaId] ?: 0L) + 1L
            next[id.replicaId] = seq
            return Dot(id.replicaId, seq).also { dotOf[id] = it }
        }
    }

    /** Elementwise max of two VV-shaped maps. */
    private fun ceilWith(x: Map<ReplicaId, Long>, y: Map<ReplicaId, Long>): Map<ReplicaId, Long> =
        (x.keys + y.keys).associateWith { k -> maxOf(x[k] ?: 0L, y[k] ?: 0L) }

    /**
     * The full v3 matrix-clock state of the compactor: the **live** rows, the
     * **retained** frontier accumulated over evictions, and the compactor's own
     * contiguous **delivered** VV.
     */
    private class Matrix(
        val delivered: Map<ReplicaId, Long>,
        val frontiers: Map<ReplicaId, Map<ReplicaId, Long>>,
        val retainedFrontier: Map<ReplicaId, Long> = emptyMap(),
    )

    /** `F_live[x] = max over live rows of frontier[x]`. */
    private fun fLive(m: Matrix): Map<ReplicaId, Long> {
        val authors = m.frontiers.values.flatMap { it.keys }.toSet()
        return authors.associateWith { x -> m.frontiers.values.maxOf { vv -> vv[x] ?: 0L } }
    }

    /**
     * Evict [peer] (§4.3 retain rule): drop its live row, fold its last-gossiped
     * frontier into [Matrix.retainedFrontier] by elementwise max.
     */
    private fun evict(m: Matrix, peer: ReplicaId): Matrix {
        val evictedRow = m.frontiers.getValue(peer)
        return Matrix(
            delivered = m.delivered,
            frontiers = m.frontiers - peer,
            retainedFrontier = ceilWith(m.retainedFrontier, evictedRow),
        )
    }

    /**
     * §4.4 release rule. A retained entry for author `x` survives **only if** it
     * exceeds both `delivered_self[x]` (R1) and `F_live[x]` (R2); otherwise it is
     * discharged as covered/redundant.
     */
    private fun normalisedRetained(m: Matrix): Map<ReplicaId, Long> {
        val live = fLive(m)
        return m.retainedFrontier.filter { (x, s) ->
            s > maxOf(m.delivered[x] ?: 0L, live[x] ?: 0L)
        }
    }

    /** `F[x] = max(F_live[x], normalisedRetained[x])` — the frontier condition 3 checks. */
    private fun frontierMax(m: Matrix): Map<ReplicaId, Long> =
        ceilWith(fLive(m), normalisedRetained(m))

    /** `S[x] = min over LIVE rows of frontier[x]` (unchanged from v2). */
    private fun stableCut(m: Matrix): Map<ReplicaId, Long> {
        val authors = m.frontiers.values.flatMap { it.keys }.toSet()
        return authors.associateWith { x -> m.frontiers.values.minOf { vv -> vv[x] ?: 0L } }
    }

    /**
     * Faithful v3 predicate. Conditions: (2) `sᵢ ≤ S[r]`, (3) `∀x: delivered[x] ≥
     * F[x]` with `F = max(F_live, retained)`, (4) no surviving local `Insert(_,
     * after=I)`. Returns the compacted RGA + Compact op, or null.
     */
    private fun <V> compactV3(rga: Rga<V>, ctx: Ctx, m: Matrix): Pair<Rga<V>, RgaOp.Compact>? {
        val f = frontierMax(m)
        val caughtUp = f.all { (x, fx) -> (m.delivered[x] ?: 0L) >= fx } // condition 3
        if (!caughtUp) return null

        val s = stableCut(m)
        val survivingPredecessors: Set<RgaId> = rga.ops
            .filterIsInstance<RgaOp.Insert<V>>()
            .mapTo(mutableSetOf()) { it.after }
        val gcIds = rga.tombstones
            .filter { id ->
                val dot = ctx.dotOf.getValue(id)
                dot.seq <= (s[dot.replica] ?: 0L) && id !in survivingPredecessors // (2) + (4)
            }
            .toSet()
        if (gcIds.isEmpty()) return null
        val op = RgaOp.Compact(gcIds)
        return rga.apply(op) to op
    }

    /**
     * Builds the canonical scenario: A inserts I@HEAD and removes it; C delivers
     * both and mints J=Insert(J, after=I), concurrent with Remove(I), undelivered
     * to A. Returns A's tombstoned RGA and the ops/ctx so each probe can vary the
     * matrix (live / evicted / rejoined).
     */
    private fun scenario(): Scenario {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        ctx.assign(opJ.id)
        return Scenario(ctx, aTombstoned, opI, opJ)
    }

    private data class Scenario(
        val ctx: Ctx,
        val aTombstoned: Rga<String>,
        val opI: RgaOp.Insert<String>,
        val opJ: RgaOp.Insert<String>,
    )

    // ---------------------------------------------------------------------
    // #272 — author-independence, fixed membership. GC refused (v2 already did).
    // ---------------------------------------------------------------------
    @Test
    fun probe272_authorIndependence_fixedMembership_refusesGc() {
        val (ctx, aTomb, opI, opJ) = scenario()
        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, c to 0L)
        val matrix = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(
                a to deliveredA,
                c to mapOf(a to seqI, c to seqJ),
            ),
        )

        assertNull(
            compactV3(aTomb, ctx, matrix),
            "SOUND: A knows dot (c,$seqJ) via C's live frontier but has not delivered J — GC refused",
        )
        assertEquals(listOf("J"), aTomb.apply(opJ).toList(), "J lands when delivered; I's predecessor intact")
    }

    // ---------------------------------------------------------------------
    // #275 — eviction. v2 purged I (J orphaned); v3 must REFUSE.
    // ---------------------------------------------------------------------
    @Test
    fun probe275_eviction_dropsLiveWitness_butRetainedFloorRefusesGc() {
        val (ctx, aTomb, opI, opJ) = scenario()
        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(
                a to deliveredA,
                c to mapOf(a to seqI, c to seqJ),
            ),
        )
        // Sanity: refused while C is live (== #272).
        assertNull(compactV3(aTomb, ctx, live), "before eviction: live witness refuses (sanity)")

        // C goes silent → evicted. v2 would now purge I. v3 retains C's frontier.
        val afterEviction = evict(live, c)
        assertEquals(
            seqJ,
            afterEviction.retainedFrontier[c],
            "retain rule: C's frontier folded in, retainedFrontier[c] = seq(J)",
        )
        assertNull(
            compactV3(aTomb, ctx, afterEviction),
            "v3 FIX: F = max(F_live, retained) still carries (c,$seqJ); condition 3 refuses; I survives",
        )

        // J finally resurfaces and lands — I's predecessor was never purged.
        assertEquals(listOf("J"), aTomb.apply(opJ).toList(), "no loss: J lands on the retained I")
    }

    // ---------------------------------------------------------------------
    // NEW — eviction-then-rejoin. Safe throughout; unpins for the RIGHT reason.
    // ---------------------------------------------------------------------
    @Test
    fun probeRejoin_evictThenReconnect_safe_thenUnpinsViaLocalSuccessor() {
        val (ctx, aTomb, opI, opJ) = scenario()
        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, c to 0L)
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
        )
        val evicted = evict(live, c)
        assertNull(compactV3(aTomb, ctx, evicted), "evicted: refused (retained floor)")

        // C reconnects: fresh live row dominates the retained entry → (R2) discharges it.
        val rejoined = Matrix(
            delivered = deliveredA, // A still hasn't delivered J
            frontiers = mapOf(a to deliveredA, c to mapOf(a to seqI, c to seqJ)),
            retainedFrontier = evicted.retainedFrontier,
        )
        assertEquals(
            emptyMap(),
            normalisedRetained(rejoined),
            "(R2): live row C witnesses (c,$seqJ); retained entry discharged as redundant",
        )
        assertNull(compactV3(aTomb, ctx, rejoined), "still refused — live frontier carries J, A hasn't delivered it")

        // A now delivers J (C's FullState forwards it). I's tombstone gains a LOCAL successor.
        val aWithJ = aTomb.apply(opJ)
        val caughtUp = Matrix(
            delivered = mapOf(a to seqI, c to seqJ),
            frontiers = mapOf(
                a to mapOf(a to seqI, c to seqJ),
                c to mapOf(a to seqI, c to seqJ),
            ),
        )
        assertNull(
            compactV3(aWithJ, ctx, caughtUp),
            "caught up, but Insert(J, after=I) survives locally — condition 4 refuses for the RIGHT reason",
        )
        assertEquals(listOf("J"), aWithJ.toList(), "J reachable through retained-but-tombstoned I; no loss ever")
    }

    // ---------------------------------------------------------------------
    // NEW — eviction where J reached a LIVE peer B. Safe (retained not even needed).
    // ---------------------------------------------------------------------
    @Test
    fun probeLiveWitness_jReachedB_evictC_refusesViaLiveFrontier() {
        val (ctx, aTomb, opI, opJ) = scenario()
        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        val deliveredA = mapOf(a to seqI, b to 0L, c to 0L)
        // B delivered J: its frontier witnesses (c, seq(J)). A has NOT delivered J.
        val live = Matrix(
            delivered = deliveredA,
            frontiers = mapOf(
                a to deliveredA,
                b to mapOf(a to seqI, b to 0L, c to seqJ),
                c to mapOf(a to seqI, c to seqJ),
            ),
        )
        // Evict C. Suppose (worst case) the retain rule still folds C in — but even
        // WITHOUT it, B's live row carries (c, seq(J)). Verify F is unchanged.
        val afterEviction = evict(live, c)
        assertEquals(
            seqJ,
            frontierMax(afterEviction)[c],
            "live witness B keeps F[c] = seq(J) regardless of retained frontier",
        )
        assertNull(
            compactV3(aTomb, ctx, afterEviction),
            "refused via live frontier B — the retained floor is not even needed here",
        )
        assertEquals(listOf("J"), aTomb.apply(opJ).toList(), "no loss when A delivers J")
    }

    // ---------------------------------------------------------------------
    // Baseline — fully stable, caught up, no successor: the barrier is NOT vacuous.
    // ---------------------------------------------------------------------
    @Test
    fun baseline_stableCaughtUpNoSuccessor_collects() {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTomb, _) = a0.removeAt(0)!!
        val seqI = ctx.dotOf.getValue(opI.id).seq

        val deliveredA = mapOf(a to seqI)
        val matrix = Matrix(delivered = deliveredA, frontiers = mapOf(a to deliveredA))

        val result = compactV3(aTomb, ctx, matrix)
        assertNotNull(result, "a stable, caught-up tombstone with no successor IS collected")
        assertEquals(setOf(opI.id), result.second.ids)
        assertEquals(emptyList(), result.first.toList())
        assertEquals(emptySet(), result.first.tombstones)
    }
}
