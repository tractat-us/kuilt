@file:Suppress("DEPRECATION") // Documents the unsound scalar compact(Long) the new predicate replaces.

package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The canonical #260/#262 soundness repro, made real against the **new** causal-
 * stability [Rga.compact] predicate (ADR-003 addendum v3).
 *
 * ## The hazard the scalar watermark had
 *
 * A concurrent `Insert(J, after = I)` minted by a **different** author `c` is lost
 * if peer `a` GCs the tombstoned predecessor `I` before delivering `J`: `I` looks
 * like a safe leaf on `a`'s local op-log (condition: "no surviving op has
 * `after == id`"), `a` purges it, and when `J` later arrives its structural
 * predecessor is gone everywhere → `J` orphaned, silently and permanently lost.
 * The watermark never proved every peer had *delivered* all ops ≤ watermark; that
 * author-independent barrier is exactly what the scalar `compact` silently assumed.
 *
 * ## What the new predicate guarantees
 *
 * [Rga.compact]`(stableCut, frontierMax, delivered)` refuses to GC `I` precisely
 * because `a` knows dot `(c, seq(J))` *exists* (via `c`'s gossiped frontier — or,
 * post-eviction, the retained frontier) but has not delivered `J`: condition 3
 * (frontier-complete) fails. The committed insert survives. This test pins that —
 * it was RED on the scalar path (`[]`) and is GREEN on the new predicate (`[J]`).
 */
class RgaCompactConcurrentInsertSoundnessTest {

    private val a = ReplicaId("a")
    private val c = ReplicaId("c")

    /**
     * A concurrent `Insert(J, after = I)` is NOT lost: under a frontier that knows
     * `(c, seq(J))` exists while `a` has not delivered `J`, the new predicate
     * refuses GC, so `J` survives when it lands.
     */
    @Test
    fun concurrentInsertSurvivesGcOfItsTombstonedPredecessor() {
        // Shared prefix: A inserts I, then tombstones it. C has the same op-log.
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)

        // Concurrency: C inserts J after I; A has NOT delivered this op yet.
        val (_, opJ) = cBase.insertAfter(c, opI.id, "J")
        val seqI = opI.id.seq
        val seqJ = opJ.id.seq

        // A's matrix: C is a live peer; its gossiped frontier witnesses (c, seqJ).
        // A has delivered I but not J.
        val delivered = VersionVector.of(mapOf(a to seqI, c to 0L))
        val stableCut = VersionVector.of(mapOf(a to seqI, c to 0L)) // min over {A, C}
        val frontierMax = VersionVector.of(mapOf(a to seqI, c to seqJ)) // C's frontier reaches J

        assertNull(
            aTombstoned.compact(stableCut = stableCut, frontierMax = frontierMax, delivered = delivered),
            "SOUND: A knows (c,$seqJ) exists but has not delivered J — condition 3 refuses GC",
        )

        // J lands; its predecessor I was never purged, so J is reachable.
        assertEquals(
            listOf("J"),
            aTombstoned.apply(opJ).toList(),
            "concurrent Insert(J, after=I) survives — not silently lost when I would-have-been GC'd",
        )
    }

    /**
     * Differential: the **deprecated** scalar watermark still exhibits the loss —
     * documents *why* the predicate had to change (and guards against anyone
     * re-routing onto the scalar path).
     */
    @Test
    fun scalarWatermark_stillLosesJ_documentsTheBugTheNewPredicateFixes() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (cWithJ, opJ) = cBase.insertAfter(c, opI.id, "J")

        // The scalar watermark GCs I (it cannot see C's undelivered J).
        val (aCompacted, compactOp) = aTombstoned.compact(watermark = opI.id.lamport)!!
        val aFinal = aCompacted.apply(opJ)
        val cFinal = cWithJ.apply(compactOp)

        assertEquals(aFinal, cFinal, "convergence holds — the bug is loss, not divergence")
        assertEquals(emptyList(), aFinal.toList(), "scalar path silently drops J — the bug v3 fixes")
    }
}
