package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reroot-to-HEAD materialization (#254 PART 1).
 *
 * `computeSequence` renders the RGA by walking `after`-pointers from [RgaId.HEAD]. History
 * windowing drops a *leading prefix* of live inserts; in the dominant chat-append pattern the
 * surviving elements chain off their dropped predecessor (`after = previous`), so the first
 * survivor anchors to a now-purged id and the whole window collapses to `[]`.
 *
 * The fix: an [RgaOp.Insert] whose `after` is **neither [RgaId.HEAD] nor a present (non-compacted)
 * Insert id** materializes as a child of [RgaId.HEAD], deterministically ordered with HEAD's other
 * children (descending id). The retained window re-roots at HEAD instead of vanishing.
 *
 * This is **benign for tombstone GC** (a causally-stable GC'd element has no surviving local
 * successor — barrier condition 4 — so nothing ever reroots there) and **essential for windowing**.
 */
class RgaRerootTest {

    private val p = ReplicaId("p")

    /** Build `[0,1,2,3,4,5]` as a tail-appended chain (each element `after` the previous). */
    private fun chainOfSix(): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var rga = Rga.empty<String>()
        var after = RgaId.HEAD
        val ops = (0..5).map { i ->
            val (next, op) = rga.insertAfter(p, after, "$i")
            rga = next
            after = op.id
            op
        }
        return rga to ops
    }

    @Test
    fun droppingLeadingPrefixRerootsRetainedWindowAtHead() {
        val (rga, ops) = chainOfSix()
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), rga.toList(), "baseline chain renders in order")

        // Window-drop the leading three live inserts via a Compact (purges Insert(0..2)).
        val dropped = mapOf(
            ops[0].id to RgaId.HEAD,
            ops[1].id to ops[0].id,
            ops[2].id to ops[1].id,
        )
        val windowed = rga.apply(RgaOp.Compact(dropped))

        assertEquals(
            listOf("3", "4", "5"),
            windowed.toList(),
            "retained window re-roots at HEAD — does not collapse to [] when its predecessor is purged",
        )
    }

    @Test
    fun divergentWindowsConvergeMostAggressiveWins() {
        val (rga, ops) = chainOfSix()

        // Peer X drops {0,1,2}; peer Y drops {0,1}. Different windows, same op-log otherwise.
        val x = rga.apply(RgaOp.Compact(mapOf(
            ops[0].id to RgaId.HEAD,
            ops[1].id to ops[0].id,
            ops[2].id to ops[1].id,
        )))
        val y = rga.apply(RgaOp.Compact(mapOf(
            ops[0].id to RgaId.HEAD,
            ops[1].id to ops[0].id,
        )))

        // Set-union merge in both directions must converge to the more-aggressive window [3,4,5].
        val xy = x.piece(y)
        val yx = y.piece(x)

        assertEquals(listOf("3", "4", "5"), xy.toList(), "X∪Y converges to the most-aggressive window")
        assertEquals(xy.toList(), yx.toList(), "merge is commutative — both orders agree")
    }

    @Test
    fun rerootedSiblingsOrderDescendingById() {
        // Two independent chains rooted at HEAD whose roots get purged: both survivors reroot to
        // HEAD and must order deterministically (descending id), exactly like native HEAD children.
        val (r0, a) = Rga.empty<String>().insertAfter(p, RgaId.HEAD, "a-root")
        val (r1, aChild) = r0.insertAfter(p, a.id, "a-child")
        val (r2, b) = r1.insertAfter(p, RgaId.HEAD, "b-root")
        val (r3, bChild) = r2.insertAfter(p, b.id, "b-child")

        // Purge both roots; a-child and b-child reroot to HEAD.
        val windowed = r3.apply(RgaOp.Compact(mapOf(a.id to RgaId.HEAD, b.id to RgaId.HEAD)))

        val expectedFirst = if (aChild.id > bChild.id) "a-child" else "b-child"
        val expectedSecond = if (aChild.id > bChild.id) "b-child" else "a-child"
        assertEquals(
            listOf(expectedFirst, expectedSecond),
            windowed.toList(),
            "rerooted orphans order by descending id among HEAD's children",
        )
    }

    /**
     * Positional reroot (#293): an eviction-orphan reattaches to the nearest surviving ancestor,
     * not HEAD. Build `[M, I, J]` (I removed, then GC'd). Before this fix J rerooted to HEAD
     * and sorted above M (`[J, M]`). With positional reroot, `Compact({I→M})` records I's
     * position; J chain-follows I→M and lands below M → `[M, J]`.
     */
    @Test
    fun evictionOrphanPositionalReroot() {
        val (r0, opM) = Rga.empty<String>().insertAfter(p, RgaId.HEAD, "M")
        val (r1, opI) = r0.insertAfter(p, opM.id, "I")
        val (r2, opJ) = r1.insertAfter(p, opI.id, "J")
        assertEquals(listOf("M", "I", "J"), r2.toList(), "baseline renders in insertion order")

        // Tombstone I (the eviction target), then GC it via Compact — J's `after` is purged.
        val tombstoned = r2.removeAt(1)!!.first
        assertEquals(listOf("M", "J"), tombstoned.toList(), "I tombstoned, J still chains off it")

        val windowed = tombstoned.apply(RgaOp.Compact(mapOf(opI.id to opM.id)))

        assertEquals(
            listOf("M", "J"),
            windowed.toList(),
            "positional reroot: Compact records I.after=M, so J chain-follows I→M and stays below M",
        )
    }
}
