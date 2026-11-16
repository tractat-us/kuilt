package us.tractat.kuilt.crdt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [Rga.dropWindowPastUnheld]: the floor walk crosses a run of this replica's seqs that the
 * state holds no op of, when a seq being dropped (or already compacted) lies above the run, and
 * nowhere else (#3447).
 *
 * Every arm builds the hole the way a partial read does — six inserts minted, some applied to a
 * fresh state, which is then raised to the full state's counters — and asserts the hole is there
 * before it measures anything. A held op whose predecessor is in the hole re-roots to HEAD, so the
 * rigs compare what is held as a set.
 */
@OptIn(CrdtInternalApi::class)
class RgaDropWindowPastUnheldTest {

    private val me = ReplicaId("a")
    private val peer = ReplicaId("b")

    /** Six of [me]'s appends, seqs 1..6, and a raised state holding only the ones at [heldSeqs]. */
    private fun holed(vararg heldSeqs: Long): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        val (full, minted) = Rga.empty<String>().insertAllAfter(me, RgaId.HEAD, listOf("1", "2", "3", "4", "5", "6"))
        val partial = minted.filter { it.id.seq in heldSeqs }.fold(Rga.empty<String>()) { state, op -> state.apply(op) }
        return partial.advanceClock(me, full.lamport, full.seqHighWater(me)) to minted
    }

    private fun ids(minted: List<RgaOp.Insert<String>>, vararg seqs: Long): Set<RgaId> =
        minted.filter { it.id.seq in seqs }.mapTo(mutableSetOf()) { it.id }

    @Test
    fun theFloorCrossesAHoleBelowADroppedSeq() {
        val (state, minted) = holed(1, 4, 5, 6)
        val (crossed, patch) = requireNotNull(state.dropWindowPastUnheld(me, ids(minted, 1, 4)))
        val (wedged, _) = requireNotNull(state.dropWindow(me, ids(minted, 1, 4)))
        assertAll(
            // The rig: seqs 2 and 3 are a hole, and dropWindow's walk stops at it.
            { assertEquals(6L, state.seqHighWater(me), "the raise reached seq 6") },
            { assertEquals(setOf("1", "4", "5", "6"), state.toList().toSet(), "seqs 2 and 3 are not held") },
            { assertEquals(1L, wedged.compactedBelow[me], "dropWindow stops below the hole") },
            { assertEquals(ids(minted, 4), wedged.compactedIds, "and records seq 4 in a Compact") },
            // The property.
            { assertEquals(4L, crossed.compactedBelow[me], "the floor crosses 2 and 3 to reach 4") },
            { assertEquals(emptySet(), crossed.compactedIds, "and nothing is recorded in a Compact") },
            { assertEquals(0, crossed.compactOpCount) },
            { assertEquals(listOf("5", "6"), crossed.toList()) },
            { assertEquals(4L, patch.delta.compactedBelow[me], "the delta carries the floor") },
        )
    }

    @Test
    fun theHolesOpsArePurgedWhenTheyComeBack() {
        val (state, minted) = holed(1, 4, 5, 6)
        val (crossed, patch) = requireNotNull(state.dropWindowPastUnheld(me, ids(minted, 1, 4)))
        val returned = minted.filter { it.id.seq in setOf(2L, 3L) }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        val peerHoldingThem = returned.piece(patch.delta)
        assertAll(
            { assertEquals(listOf("2", "3"), returned.toList(), "the rig: the returned ops are the hole's") },
            { assertEquals(listOf("5", "6"), crossed.piece(returned).toList(), "this replica purges them") },
            { assertEquals(emptyList(), peerHoldingThem.toList(), "and so does a peer that absorbs the delta") },
        )
    }

    @Test
    fun aHoleWithNoDroppedSeqAboveItStaysOpen() {
        val (state, minted) = holed(1, 2)
        val (dropped, _) = requireNotNull(state.dropWindowPastUnheld(me, ids(minted, 1, 2)))
        val returned = minted.filter { it.id.seq >= 3L }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        assertAll(
            { assertEquals(6L, state.seqHighWater(me), "the rig: seqs 3 to 6 are a hole past every held one") },
            { assertEquals(2L, dropped.compactedBelow[me], "the floor stops at the last dropped seq") },
            { assertEquals(listOf("3", "4", "5", "6"), dropped.piece(returned).toList(), "the hole's ops are admitted") },
        )
    }

    /** [Rga.dropAllOwn] closes the hole [aHoleWithNoDroppedSeqAboveItStaysOpen] leaves (#3513). */
    @Test
    fun dropAllOwnClosesAHoleAboveEveryHeldSeq() {
        val (state, minted) = holed(1, 2)
        val (dropped, patch) = requireNotNull(state.dropAllOwn(me))
        val returned = minted.filter { it.id.seq >= 3L }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        assertAll(
            { assertEquals(6L, state.seqHighWater(me), "the rig: seqs 3 to 6 are a hole past every held one") },
            { assertEquals(listOf("1", "2"), state.toList(), "the rig: 1 and 2 are held") },
            { assertEquals(6L, dropped.compactedBelow[me], "the floor reaches the high-water") },
            { assertEquals(emptyList(), dropped.toList(), "the held ops are dropped") },
            { assertEquals(emptyList(), dropped.piece(returned).toList(), "the hole's ops are purged when they come back") },
            { assertEquals(emptyList(), returned.piece(patch.delta).toList(), "and by a peer that absorbs the delta") },
            { assertEquals(6L, dropped.seqHighWater(me), "the next mint still lies past them") },
        )
    }

    @Test
    fun dropAllOwnLeavesAForeignAuthorAlone() {
        val (state, _) = holed(1, 2)
        val (withPeer, peerOps) = state.insertAllAfter(peer, RgaId.HEAD, listOf("p", "q"))
        val (dropped, _) = requireNotNull(withPeer.dropAllOwn(me))
        assertAll(
            { assertEquals(0L, dropped.compactedBelow[peer], "the peer's entry never moves") },
            { assertEquals(emptySet(), dropped.compactedIds, "and nothing goes to a Compact") },
            { assertEquals(listOf("p", "q"), dropped.toList(), "its records stay: ${peerOps.map { it.id }}") },
        )
    }

    @Test
    fun dropAllOwnIsNullOnceTheFloorIsAtTheHighWater() {
        val (state, _) = holed(1, 2)
        val (dropped, _) = requireNotNull(state.dropAllOwn(me))
        assertAll(
            { assertEquals(null, dropped.dropAllOwn(me), "a second call raises nothing") },
            { assertEquals(null, Rga.empty<String>().dropAllOwn(me), "nor does one on a state that minted nothing") },
        )
    }

    @Test
    fun aHeldRetainedSeqAboveTheHoleBlocksTheCrossing() {
        val (state, minted) = holed(1, 4, 5)
        val (dropped, _) = requireNotNull(state.dropWindowPastUnheld(me, ids(minted, 1, 5)))
        assertAll(
            { assertEquals(setOf("1", "4", "5"), state.toList().toSet(), "the rig: 2 and 3 are a hole below a held 4") },
            { assertEquals(1L, dropped.compactedBelow[me], "the floor stops below the hole, since 4 stays") },
            { assertEquals(ids(minted, 5), dropped.compactedIds, "and 5 is recorded in a Compact") },
            { assertEquals(listOf("4"), dropped.toList()) },
        )
    }

    /**
     * A seq already compacted does not license a crossing: only a seq this call drops does. The
     * `Compact` here is a peer's, which held this replica's 5 and 6 and windowed them away, so
     * nobody here chose to drop 2 to 4. Crossing to 5 would floor them, and when they came back
     * they would be purged.
     */
    @Test
    fun aPeersCompactOverOwnDotsDoesNotLicenseACrossing() {
        val (state, minted) = holed(1)
        val peerHeld = minted.filter { it.id.seq >= 5L }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        val (_, peerPatch) = requireNotNull(peerHeld.dropWindow(peer, ids(minted, 5, 6)))
        val merged = state.piece(peerPatch.delta)
        val (withNew, _) = merged.insertAllAfter(me, minted.first().id, listOf("7"))
        val (dropped, _) = requireNotNull(withNew.dropWindowPastUnheld(me, ids(minted, 1)))
        val returned = minted.filter { it.id.seq in 2L..4L }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        assertAll(
            // The rig: the peer's Compact took 5 and 6, and 2 to 4 are a hole between them and 1.
            { assertEquals(ids(minted, 5, 6), merged.compactedIds, "the peer's Compact took 5 and 6") },
            { assertEquals(listOf("1"), merged.toList()) },
            // The property.
            { assertEquals(1L, dropped.compactedBelow[me], "the floor stops at the dropped 1") },
            { assertEquals(setOf("2", "3", "4", "7"), dropped.piece(returned).toList().toSet(), "2 to 4 are admitted when they return") },
        )
    }

    /**
     * A compacted seq inside a gap does not block a crossing that a dropped seq above it licenses.
     * The peer's `Compact` took this replica's 3, between the holes 2 and 4; dropping 1 and 5 floors
     * through all of them, so the drops keep folding into the floor.
     */
    @Test
    fun aCompactedSeqInsideAGapDoesNotBlockACrossingLicensedAboveIt() {
        val (state, minted) = holed(1, 5, 6)
        val peerHeld = minted.filter { it.id.seq == 3L }.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        val (_, peerPatch) = requireNotNull(peerHeld.dropWindow(peer, ids(minted, 3)))
        val merged = state.piece(peerPatch.delta)
        val (dropped, _) = requireNotNull(merged.dropWindowPastUnheld(me, ids(minted, 1, 5)))
        assertAll(
            { assertEquals(ids(minted, 3), merged.compactedIds, "the rig: the peer's Compact took 3") },
            { assertEquals(5L, dropped.compactedBelow[me], "the floor crosses 2 to 4 to the dropped 5") },
            { assertEquals(listOf("6"), dropped.toList()) },
        )
    }

    /**
     * A wedge already recorded heals. Before #3447 a drop above a hole became a `Compact`; a later
     * pass that drops a seq above it crosses the hole and the compacted seq together.
     */
    @Test
    fun aWedgeAlreadyRecordedHealsOnTheNextDropAboveIt() {
        val (state, minted) = holed(1, 4, 5, 6)
        val (wedged, _) = requireNotNull(state.dropWindow(me, ids(minted, 4)))
        val (healed, _) = requireNotNull(wedged.dropWindowPastUnheld(me, ids(minted, 1, 5)))
        assertAll(
            { assertEquals(0L, wedged.compactedBelow[me], "the rig: seq 4 went to a Compact") },
            { assertEquals(ids(minted, 4), wedged.compactedIds) },
            { assertEquals(5L, healed.compactedBelow[me], "the floor crosses 2, 3 and the compacted 4 to 5") },
            { assertEquals(listOf("6"), healed.toList()) },
        )
    }

    /** A compacted seq contiguous with the floor still extends it, as in [Rga.dropWindow]. */
    @Test
    fun aCompactedSeqContiguousWithTheFloorStillExtendsIt() {
        val (state, minted) = holed(1, 2, 5, 6)
        val (compacted, _) = requireNotNull(state.dropWindow(me, ids(minted, 2)))
        val (dropped, _) = requireNotNull(compacted.dropWindowPastUnheld(me, ids(minted, 1)))
        assertAll(
            { assertEquals(ids(minted, 2), compacted.compactedIds, "the rig: 2 went to a Compact") },
            { assertEquals(2L, dropped.compactedBelow[me], "the floor steps 1 then the compacted 2, and stops at the hole") },
        )
    }

    @Test
    fun aForeignAuthorsDotsNeverFold() {
        val (state, minted) = holed(1, 4, 5, 6)
        val (withPeer, peerOps) = state.insertAllAfter(peer, RgaId.HEAD, listOf("p", "q"))
        val dropped = ids(minted, 1, 4) + peerOps.map { it.id }
        val (after, _) = requireNotNull(withPeer.dropWindowPastUnheld(me, dropped))
        assertAll(
            { assertEquals(4L, after.compactedBelow[me]) },
            { assertEquals(0L, after.compactedBelow[peer], "the peer's entry never moves") },
            { assertEquals(peerOps.mapTo(mutableSetOf()) { it.id }, after.compactedIds, "its dots go to a Compact") },
        )
    }

    /** With no hole at all, the two walks are the same walk. */
    @Test
    fun withNoHoleItIsDropWindow() {
        val (full, minted) = Rga.empty<String>().insertAllAfter(me, RgaId.HEAD, listOf("1", "2", "3", "4"))
        val dropped = minted.filter { it.id.seq in setOf(1L, 2L, 4L) }.mapTo(mutableSetOf()) { it.id }
        val past = requireNotNull(full.dropWindowPastUnheld(me, dropped))
        val plain = requireNotNull(full.dropWindow(me, dropped))
        assertAll(
            { assertEquals(plain.first, past.first) },
            { assertEquals(plain.second.delta, past.second.delta) },
            { assertTrue(past.first.compactedIds.isNotEmpty(), "the rig: 4 sits above a retained 3") },
        )
    }
}
