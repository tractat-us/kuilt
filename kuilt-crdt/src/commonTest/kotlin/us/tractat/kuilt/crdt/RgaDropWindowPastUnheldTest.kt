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

    /** The wedge already recorded heals: a hole below an own seq a Compact took is crossed too. */
    @Test
    fun theFloorCrossesAHoleBelowAnAlreadyCompactedSeq() {
        val (state, minted) = holed(1, 4, 5, 6)
        val (wedged, _) = requireNotNull(state.dropWindow(me, ids(minted, 4)))
        val (healed, _) = requireNotNull(wedged.dropWindowPastUnheld(me, ids(minted, 1)))
        assertAll(
            { assertEquals(0L, wedged.compactedBelow[me], "the rig: seq 4 went to a Compact") },
            { assertEquals(ids(minted, 4), wedged.compactedIds) },
            { assertEquals(4L, healed.compactedBelow[me], "the floor crosses the hole to the compacted 4") },
            { assertEquals(listOf("5", "6"), healed.toList()) },
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
