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

    /**
     * The `Remove` arm of the same guard. It cannot be pinned through [Rga.toList] — the record
     * is already invisible either way — so this asserts on the **state**: admitting the `Remove`
     * would put an op at-or-below the floor back into [Rga.ops], and the very next [Rga.piece]
     * would purge it again, so `a.piece(a) != a` and idempotence is gone.
     */
    @Test
    fun aRemoveBeneathTheFloorIsNotReAdmitted() {
        val (rga, ops) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        val reapplied = floored.apply(RgaOp.Remove<String>(ops[0].id))

        assertAll(
            { assertEquals(floored, reapplied, "a late raw Remove must not re-enter the op-log") },
            { assertEquals(floored.ops, reapplied.ops, "and specifically must not grow ops") },
            { assertEquals(reapplied, reapplied.piece(reapplied), "so piece stays idempotent") },
        )
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

    /**
     * Pins the **accepted** cost documented on [Rga.compactedBelow]: a floor records no
     * positions, so `computeSequence`'s #293 reroot has nothing to walk and a survivor whose
     * predecessor was floored away lands on [RgaId.HEAD]. HEAD's child list is sorted by id
     * descending, so the high-lamport survivor `b1` overtakes `b0` — a record it used to trail.
     *
     * This is a reordering, not a divergence: the order is still a function of `(ops, floor)`.
     * The test exists so that if anyone later *fixes* the reroot, they see the cost they paid
     * (a per-element positions map is exactly what the floor removes) rather than a silent
     * behaviour change.
     */
    @Test
    fun aSurvivorWhosePredecessorWasFlooredRerootsToHeadAndCanOvertake() {
        val (s1, a1) = Rga.empty<String>().insertAfter(me, RgaId.HEAD, "a1")
        val (s2, _) = s1.insertAfter(peer, RgaId.HEAD, "b0")
        val (s3, a2) = s2.insertAfter(me, a1.id, "a2")
        val (rga, _) = s3.insertAfter(peer, a2.id, "b1")

        assertEquals(listOf("b0", "a1", "a2", "b1"), rga.toList(), "b1 trails b0 while its ancestor lives")

        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))

        assertEquals(
            listOf("b1", "b0"),
            floored.toList(),
            "with a1/a2 floored, b1 re-roots to HEAD and outranks the older b0",
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

    /**
     * [Rga.fromOps] purges on construction, so a state whose op-set contradicts its own floor
     * cannot exist. This is the path a wire decode takes: without the purge the decoded value
     * would hold ops at-or-below its floor, the first [Rga.piece] would drop them, and
     * `a.piece(a) != a`. Asserts the op-set as well as the sequence — the ops are the state,
     * and a decoded blob could carry a *tombstoned* floored id that [Rga.toList] never shows.
     */
    @Test
    fun fromOpsPurgesAnOpSetThatContradictsItsOwnFloor() {
        val (rga, _) = chain(5)
        val floor = VersionVector.of(mapOf(me to 3L))

        val decoded = Rga.fromOps(rga.ops, rga.lamport, floor)

        assertAll(
            { assertEquals(listOf("r3", "r4"), decoded.toList(), "the floored records stay hidden") },
            {
                assertTrue(
                    decoded.ops.none { it is RgaOp.Insert && it.id.seq <= 3L },
                    "and their ops never enter the log",
                )
            },
            { assertEquals(decoded, decoded.piece(decoded), "so piece is idempotent on a decoded value") },
            { assertEquals(rga.withCompactedBelow(floor), decoded, "same value as the locally-floored state") },
        )
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
