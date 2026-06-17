package us.tractat.kuilt.quilter

import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression (#262): GC'ing a delivered dot must NOT regress — nor permanently pin — the
 * contiguous delivered frontier. The per-author seq space is dense and the high-water is
 * the longest gap-free prefix, so if a `Compact` dropped a GC'd Insert's dot, GC'ing a
 * non-tail dot would punch a permanent hole: the author's delivered high-water would stick
 * below the gap forever (no future Insert can fill it — they get higher seqs), stalling all
 * further GC for that author. The fix: `causalDots` re-emits `Compact`'d (delivered) dots.
 */
class DeliveredFrontierRegressionTest {
    private val a = ReplicaId("a")

    @Test
    fun gcOfMiddleDotDoesNotRegressOrPinDeliveredFrontier() {
        // author a: three inserts at HEAD → dense seqs 1,2,3 (independent of `after`)
        val (r1, _) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x") // (a,1)
        val (r2, opY) = r1.insertAfter(a, RgaId.HEAD, "y")                // (a,2)
        val (r3, _) = r2.insertAfter(a, RgaId.HEAD, "z")                  // (a,3)
        assertEquals(3L, contiguousFrontier(r3.causalDots())[a], "before GC: delivered[a] = 3")

        // tombstone the MIDDLE dot (a,2); x and z were inserted after HEAD, so y has no successor
        val (r4, _) = r3.removeAt(r3.sequence.indexOf(opY.id))!!
        val full = VersionVector.of(mapOf(a to 3L))
        val (r5, _) = r4.compact(stableCut = full, frontierMax = full, delivered = full)!!

        // No regression — the Compact re-emits (a,2), keeping the prefix gap-free.
        assertEquals(3L, contiguousFrontier(r5.causalDots())[a], "delivered[a] must NOT regress after GC")

        // …and the frontier still advances: a 4th insert (seq 4) reaches 4, not stuck at the gap.
        val (r6, _) = r5.insertAfter(a, RgaId.HEAD, "w") // (a,4)
        assertEquals(4L, contiguousFrontier(r6.causalDots())[a], "frontier advances past the GC'd dot")
    }
}
