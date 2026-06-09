package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Adversarial probe of the **stable-cut / matrix-clock GC barrier** for RGA
 * (ADR-003 addendum v2, #262, PR #274) — the EVICTION attack.
 *
 * The barrier purges a tombstoned `I` (dot `(r, sI)`) iff:
 *   1. tombstoned, AND
 *   2. `sI <= S[r]` where `S` = elementwise MIN over peers' (incl self) delivered VVs, AND
 *   3. `forall x: delivered_self[x] >= F[x]` where `F` = elementwise MAX over peers' VVs, AND
 *   4. no surviving local `Insert(_, after=I)`.
 *
 * The design's soundness story (§3): "the gossip from C that raises `S[r]` enough
 * to make `I` stable is the SAME message that raises `F[c]` to reveal
 * `J = Insert(J, after=I)`, so condition 3 refuses until self delivers J."
 *
 * The attack: §7 says `stableCut` and `frontierMax` are recomputed **on peer
 * eviction** ("eviction can legitimately raise the cut"). Eviction removes a
 * peer's row from the matrix. Both `S = min over peers` and `F = max over peers`
 * are recomputed over the SURVIVING rows. Removing the only row that carried
 * `frontier[c] = seq(J)` drops `F[c]` below `seq(J)` — so condition 3 can pass
 * blind to J — while `S` (a min) can only RISE, keeping `I` stable. The coupling
 * the soundness story relies on is severed by eviction.
 */
class RgaCompactStableCutEvictionProbeTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")
    private val c = ReplicaId("c")

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
     * Faithful model of the design's 3-condition predicate. [frontiers] is the
     * matrix clock AFTER any eviction (rows are dropped on eviction). `S` = min,
     * `F` = max, over exactly the rows present in [frontiers].
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
        val frontierMax = authors.associateWith { author ->
            frontiers.values.maxOf { vv -> vv[author] ?: 0L }
        }
        // Condition 3 — frontier-complete.
        val caughtUp = authors.all { author -> (delivered[author] ?: 0L) >= frontierMax[author]!! }
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
     * THE EVICTION ATTACK.
     *
     * 3 peers: A (compactor), B, C.
     * - A: Insert(I)@HEAD (dot (a,1)), Remove(I) (dot (a,2)).
     * - I and Remove(I) delivered to B and C.
     * - C mints Insert(J, after=I) (dot (c,1)), CONCURRENT with Remove(I).
     *   J broadcast; A has NOT delivered it; B has NOT delivered it (B's gossip predates J).
     *
     * Now C goes silent and is EVICTED (TTL). A drops C's row from the matrix.
     */
    @Test
    fun evictionDropsFrontierKnowledgeOfJ_purgesI_thenJ_orphaned() {
        val ctx = Ctx()
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        ctx.assign(opI.id)
        val (aTombstoned, remI) = a0.removeAt(0)!!

        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        ctx.assign(opJ.id)

        val seqI = ctx.dotOf.getValue(opI.id).seq // 1
        val seqJ = ctx.dotOf.getValue(opJ.id).seq // 1

        val deliveredA = mapOf(a to seqI, b to 0L, c to 0L)

        // BEFORE eviction: C's frontier carries knowledge of J. GC refused (sanity).
        val frontiersBefore = mapOf(
            a to deliveredA,
            b to mapOf(a to seqI, b to 0L, c to 0L),
            c to mapOf(a to seqI, b to 0L, c to seqJ),
        )
        assertNull(
            compactStable(aTombstoned, ctx, deliveredA, frontiersBefore),
            "before eviction: F[c] = seq(J) > delivered_A[c]; condition 3 refuses (correct)",
        )

        // AFTER eviction of C: C's row is dropped. S rises, F[c] falls to 0.
        val frontiersAfter = mapOf(
            a to deliveredA,
            b to mapOf(a to seqI, b to 0L, c to 0L),
        )
        val result = compactStable(aTombstoned, ctx, deliveredA, frontiersAfter)

        assertNotNull(
            result,
            "POST-EVICTION: with C evicted, F no longer reveals J; condition 3 passes; I purged",
        )
        assertEquals(setOf(opI.id), result.second.ids, "I was the purged tombstone")

        // J finally arrives at A (in flight, or B forwards it after C reconnects elsewhere).
        val aAfterJ = result.first.apply(opJ)
        assertEquals(
            emptyList(),
            aAfterJ.toList(),
            "UNSOUND: J orphaned — predecessor I purged by eviction-blinded barrier; committed insert lost",
        )
    }
}
