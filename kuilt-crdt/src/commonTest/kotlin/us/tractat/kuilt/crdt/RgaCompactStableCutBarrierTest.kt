package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Model-level validation of the **causal-stability GC barrier** for RGA
 * (ADR-003 addendum v2, #262).
 *
 * The barrier is the established stable-version-vector construction for op-based
 * CRDT garbage collection. Each replica gossips its full *delivered* version
 * vector (a matrix clock); the **stable cut** `S` is the elementwise minimum over
 * every replica's delivered VV. An op is *causally stable* once its dot is
 * dominated by `S`: at that point every replica — including the compactor — has
 * delivered it, and no op concurrent with it remains in flight.
 *
 * The decisive correction over the per-author-floor design (#266, falsified by
 * #272): the compactor cannot purge a tombstoned `I` on the basis of `I`'s own
 * author alone, nor on a successor check against its **local** op-log, because a
 * concurrent `Insert(J, after = I)` minted by a *different* author can be in
 * flight and invisible to the compactor. The matrix-clock frontiers make that
 * invisibility impossible: the compactor learns the **existence** of J's dot from
 * C's gossiped frontier even before delivering J's payload, and refuses to purge
 * `I` until every op below every known frontier has been delivered locally.
 *
 * `I`'s purge is authorised iff:
 *   1. `I` is tombstoned, AND
 *   2. `S.dominates(I.dot)` — `I` is causally stable, AND
 *   3. the compactor has delivered every op below every peer's reported frontier
 *      (no dot is known-to-exist-yet-undelivered) — so its local op-log is
 *      complete up to the frontier and the successor check is sound, AND
 *   4. no surviving local `Insert(_, _, after = I)`.
 *
 * Condition 3 is what the prior designs lacked. Without it the compactor cannot
 * tell "C has nothing new" from "C minted a J I haven't delivered". With it, the
 * #272 interleaving is refused by construction.
 */
class RgaCompactStableCutBarrierTest {

    private val a = ReplicaId("a")
    private val c = ReplicaId("c")

    /** Maps each RgaId to its causal dot (the per-author seq the design adds to RgaId). */
    private class Ctx {
        private val next = mutableMapOf<ReplicaId, Long>()
        val dotOf = mutableMapOf<RgaId, Dot>()
        fun assign(id: RgaId): Dot {
            val seq = (next[id.replicaId] ?: 0L) + 1L
            next[id.replicaId] = seq
            return Dot(id.replicaId, seq).also { dotOf[id] = it }
        }
    }

    /**
     * The sound barrier. [delivered] is the compactor's own contiguous delivered
     * VV. [frontiers] is the compactor's matrix-clock view: `peer -> that peer's
     * last-gossiped delivered VV` (including the compactor's own). The stable cut
     * `S` is the elementwise min over [frontiers]. Purge requires `S` to dominate
     * the tombstone's dot AND the compactor to be caught up to every known frontier
     * (condition 3) AND the local successor check to pass.
     */
    private fun <V> compactStable(
        rga: Rga<V>,
        ctx: Ctx,
        delivered: Map<ReplicaId, Long>,
        frontiers: Map<ReplicaId, Map<ReplicaId, Long>>,
    ): Pair<Rga<V>, RgaOp.Compact>? {
        val authors = frontiers.values.flatMap { it.keys }.toSet()
        val stableCut = authors.associateWith { author ->
            frontiers.values.minOf { vv -> vv[author] ?: 0L }
        }
        // Condition 3: the compactor must have delivered everything any peer claims
        // to have. If a known frontier sits above the compactor's delivered VV, a
        // dot exists that the compactor has not yet delivered — refuse all GC.
        val caughtUp = authors.all { author ->
            val maxKnown = frontiers.values.maxOf { vv -> vv[author] ?: 0L }
            (delivered[author] ?: 0L) >= maxKnown
        }
        if (!caughtUp) return null

        val survivingPredecessors: Set<RgaId> = rga.ops
            .filterIsInstance<RgaOp.Insert<V>>()
            .mapTo(mutableSetOf()) { it.after }
        val gcIds = rga.tombstones
            .filter { id ->
                val dot = ctx.dotOf.getValue(id)
                dot.seq <= (stableCut[dot.replica] ?: 0L) && id !in survivingPredecessors
            }
            .toSet()
        if (gcIds.isEmpty()) return null
        val compactOp = RgaOp.Compact(gcIds)
        return rga.apply(compactOp) to compactOp
    }

    /**
     * PR #272's exact interleaving. A: Insert(I)@HEAD, Remove(I). C: Insert(J,
     * after=I), concurrent with Remove(I), NOT delivered to A. A is the compactor.
     *
     * A's matrix clock includes C's gossiped frontier `{a: seq(I-as-seen-by-C),
     * c: seq(J)}`. So A KNOWS a dot `(c, seq(J))` exists, even though A has not
     * delivered J. Condition 3 fires: A is not caught up to C's frontier over
     * author c. GC is refused — by construction, not by luck.
     */
    @Test
    fun refusesGc_whileConcurrentSuccessor_J_known_but_undelivered() {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, remI) = a0.removeAt(0)!!

        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        ctx.assign(opJ.id)

        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq

        // A delivered I (its own) but not J. C delivered I and minted J.
        val deliveredA = mapOf(a to seqI, c to 0L)
        val frontierC = mapOf(a to seqI, c to seqJ)
        val frontiers = mapOf(a to deliveredA, c to frontierC)

        val result = compactStable(aTombstoned, ctx, deliveredA, frontiers)

        assertNull(
            result,
            "SOUND: A knows dot (c,$seqJ) exists (C's frontier) but has not delivered it — " +
                "GC of I refused until A delivers J",
        )
        // When J finally arrives, I's predecessor is intact and J lands. No loss.
        assertEquals(listOf("J"), aTombstoned.apply(opJ).toList())
    }

    /**
     * Once J is globally delivered, A's delivered VV catches up to every frontier,
     * condition 3 passes, but now A's local op-log contains Insert(J, after=I), so
     * condition 4 (local successor check) refuses GC for the correct reason. I is
     * retained while J references it; the chain stays connected; no loss ever.
     */
    @Test
    fun onceJ_globallyDelivered_localSuccessorCheckRefuses_forTheRightReason() {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, _) = a0.removeAt(0)!!
        val (aWithJ, opJ) = aTombstoned.insertAfter(c, opI.id, "J")
        ctx.assign(opJ.id)

        val seqI = ctx.dotOf.getValue(opI.id).seq
        val seqJ = ctx.dotOf.getValue(opJ.id).seq
        val deliveredA = mapOf(a to seqI, c to seqJ)
        val frontiers = mapOf(a to deliveredA, c to deliveredA)

        val result = compactStable(aWithJ, ctx, deliveredA, frontiers)

        assertNull(result, "caught up, but Insert(J, after=I) survives locally — successor check refuses")
        assertEquals(listOf("J"), aWithJ.toList(), "J reachable through retained-but-tombstoned I")
    }

    /**
     * Baseline: a tombstone with no successor anywhere, fully stable and caught up,
     * IS collected. The barrier is not vacuous.
     */
    @Test
    fun collectsTombstone_whenStable_caughtUp_andNoSuccessor() {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, _) = a0.removeAt(0)!!

        val seqI = ctx.dotOf.getValue(opI.id).seq
        val deliveredA = mapOf(a to seqI)
        val frontiers = mapOf(a to deliveredA)

        val result = compactStable(aTombstoned, ctx, deliveredA, frontiers)

        assertNotNull(result, "a stable tombstone, fully caught up, with no successor is collected")
        assertEquals(setOf(opI.id), result.second.ids)
        assertEquals(emptyList(), result.first.toList())
        assertEquals(emptySet(), result.first.tombstones)
    }
}
