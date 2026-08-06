package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class RgaCompactedFloorTest {

    private val me = ReplicaId("a")
    private val peer = ReplicaId("b")

    /** Append [n] records as [author], returning the state and the ops in mint order. */
    private fun chain(n: Int, author: ReplicaId = me): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var rga = Rga.empty<String>()
        var tail = RgaId.HEAD
        val ops = mutableListOf<RgaOp.Insert<String>>()
        repeat(n) { i ->
            val (next, op) = rga.insertAfter(author, tail, "r$i")
            rga = next
            tail = op.id
            ops += op
        }
        return rga to ops
    }

    @Test
    fun anEmptyRgaHasAnEmptyFloor() {
        assertEquals(VersionVector.EMPTY, Rga.empty<String>().compactedBelow)
    }

    @Test
    fun raisingTheFloorPurgesTheOpsBeneathItAndHidesTheirRecords() {
        val (rga, _) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        assertEquals(listOf("r3", "r4"), floored.toList(), "the first three seqs are gone")
        assertTrue(floored.ops.none { it is RgaOp.Insert && it.id.seq <= 3L }, "their ops are purged")
    }

    @Test
    fun anInsertBeneathTheFloorIsNotReAdmitted() {
        val (rga, ops) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        val reapplied = floored.apply(ops[0])

        assertEquals(listOf("r3", "r4"), reapplied.toList(), "a late raw apply must not resurrect")
    }

    @Test
    fun aMergeWithAPeerHoldingTheRawInsertsDoesNotResurrectThem() {
        val (rga, _) = chain(5)
        val peerHoldingEverything = rga
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        assertEquals(listOf("r3", "r4"), floored.piece(peerHoldingEverything).toList())
        assertEquals(listOf("r3", "r4"), peerHoldingEverything.piece(floored).toList(), "and commutatively")
    }

    @Test
    fun floorsMergeByElementwiseMax() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))
        val b = rga.withCompactedBelow(VersionVector.of(mapOf(me to 4L, peer to 7L)))

        assertEquals(VersionVector.of(mapOf(me to 4L, peer to 7L)), a.piece(b).compactedBelow)
        assertEquals(a.piece(b).compactedBelow, b.piece(a).compactedBelow, "commutative")
    }

    /**
     * The floor is now part of [Rga.equals], so the lattice laws have to be re-proved over
     * states that carry one — `QuiltedLawsTest` and the conformance bindings only reach the
     * unfloored constructors. Tasks 3–7 rest on the whole product being a join-semilattice,
     * not just on the floor component merging by max.
     */
    @Test
    fun theLatticeLawsHoldOverFlooredStates() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))
        val b = rga.withCompactedBelow(VersionVector.of(mapOf(me to 4L, peer to 7L)))
        val c = rga.withCompactedBelow(VersionVector.of(mapOf(peer to 3L)))

        assertAll(
            { assertEquals(a, a.piece(a), "idempotent") },
            { assertEquals(a.piece(b), b.piece(a), "commutative") },
            { assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)), "associative") },
            { assertEquals(a.piece(b).hashCode(), b.piece(a).hashCode(), "hashCode agrees with equals") },
        )
    }

    @Test
    fun twoStatesDifferingOnlyInTheirFloorAreNotEqual() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(peer to 9L)))

        assertTrue(a != rga, "the floor is part of the value, not a cache")
    }

    /**
     * A floor raised past every op this replica holds is the **only** surviving evidence that
     * the seqs it covers were ever minted — the ops that carried them are gone and, unlike
     * [RgaOp.Compact], a floor has no id-set to re-emit dots from. `cacheAfterFloor` must
     * therefore fold it into the seq high-water, or the next mint reuses a swallowed seq and
     * two distinct records share a dot (the #639 class).
     */
    @Test
    fun aFloorRaisedPastTheHeldOpsStillHoldsTheSeqHighWaterUp() {
        val (rga, _) = chain(2) // me has minted seqs 1..2 only
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 9L)))

        val (_, op) = floored.insertAfter(me, RgaId.HEAD, "fresh")

        assertEquals(10L, op.id.seq, "the floor is the only record that seqs 3..9 were minted")
    }

    /** The same evidence has to survive [Rga.piece] — a floor absorbed from a peer counts too. */
    @Test
    fun mergingInAFloorRaisesTheSeqHighWaterToo() {
        val (rga, _) = chain(2)
        // fromOps is the cacheless path: this state's seq high-water comes from the floor alone.
        val flooredPeer = Rga.fromOps<String>(emptySet(), 0L, VersionVector.of(mapOf(me to 9L)))

        val (_, op) = rga.piece(flooredPeer).insertAfter(me, RgaId.HEAD, "fresh")

        assertEquals(10L, op.id.seq, "piece must fold the merged floor into the seq high-water")
    }

    @Test
    fun aFloorCoveringEverythingLeavesAnEmptyButUsableSequence() {
        val (rga, _) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 5L)))

        assertEquals(emptyList(), floored.toList())
        val (grown, op) = floored.insertAfter(me, RgaId.HEAD, "fresh")
        assertEquals(listOf("fresh"), grown.toList())
        assertTrue(op.id.seq > 5L, "the next seq must not reuse a swallowed one")
    }
}
