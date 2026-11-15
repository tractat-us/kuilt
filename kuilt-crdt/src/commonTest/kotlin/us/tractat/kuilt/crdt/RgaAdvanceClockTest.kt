package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [Rga.advanceClock] and [Rga.seqHighWater]: a state rebuilt without some of the ops its
 * replica minted can be moved past them, so it never mints one of their ids again (#3410).
 */
@OptIn(ExperimentalSerializationApi::class, CrdtInternalApi::class)
class RgaAdvanceClockTest {

    private val me = ReplicaId("a")
    private val peer = ReplicaId("b")
    private val cbor = Cbor { alwaysUseByteString = true }
    private val wire = Rga.wireSerializer(String.serializer())

    /** Three of [me]'s inserts, and a state holding only the first: what a partial read leaves. */
    private fun partialOfThree(): Triple<Rga<String>, Rga<String>, List<RgaOp.Insert<String>>> {
        val (full, minted) = Rga.empty<String>().insertAllAfter(me, RgaId.HEAD, listOf("x", "y", "z"))
        return Triple(full, Rga.empty<String>().apply(minted.first()), minted)
    }

    @Test
    fun aRaisedStateMintsPastBothCounters() {
        val (full, partial, minted) = partialOfThree()
        val raised = partial.advanceClock(me, full.lamport, full.seqHighWater(me))
        val (_, next) = raised.insertAllAfter(me, minted.first().id, listOf("w"))
        val id = next.single().id
        assertAll(
            // The rig: the partial state would mint the second insert's id again.
            { assertEquals(1L, partial.seqHighWater(me), "the partial state knows one seq") },
            { assertEquals(minted[1].id.seq, partial.insertAllAfter(me, RgaId.HEAD, listOf("w")).second.single().id.seq) },
            { assertEquals(full.lamport + 1, id.lamport, "next lamport is past the full state's clock") },
            { assertEquals(full.seqHighWater(me) + 1, id.seq, "next seq is past the full state's seq") },
            { assertTrue(minted.none { it.id == id || it.id.lamport == id.lamport }, "no id or lamport reused") },
        )
    }

    @Test
    fun theRaiseChangesNoOpAndNothingOnTheWire() {
        val (full, partial, _) = partialOfThree()
        val raised = partial.advanceClock(me, full.lamport, full.seqHighWater(me))
        assertAll(
            { assertEquals(partial, raised, "the same state") },
            { assertEquals(partial.toList(), raised.toList()) },
            { assertContentEquals(cbor.encodeToByteArray(wire, partial), cbor.encodeToByteArray(wire, raised)) },
            // So a decode forgets it, which is why a caller keeps the pair itself.
            { assertEquals(1L, cbor.decodeFromByteArray(wire, cbor.encodeToByteArray(wire, raised)).seqHighWater(me)) },
        )
    }

    @Test
    fun aRaiseThatRaisesNothingReturnsTheSameState() {
        val (full, _, _) = partialOfThree()
        assertAll(
            { assertSame(full, full.advanceClock(me, full.lamport, full.seqHighWater(me))) },
            { assertSame(full, full.advanceClock(me, 0L, 0L)) },
        )
    }

    @Test
    fun onlyTheNamedReplicasSeqMoves() {
        val (full, partial, _) = partialOfThree()
        val raised = partial.advanceClock(peer, full.lamport, 40L)
        assertAll(
            { assertEquals(40L, raised.seqHighWater(peer)) },
            { assertEquals(1L, raised.seqHighWater(me), "this replica's seq is untouched") },
            { assertEquals(full.lamport, raised.lamport, "the shared clock rises") },
        )
    }

    /** Every mutation carries both counters forward; a raise lost on any of them reuses an id. */
    @Test
    fun theRaiseSurvivesEveryMutation() {
        val (_, partial, minted) = partialOfThree()
        val raised = partial.advanceClock(me, 50L, 30L)
        val (withPeer, _) = Rga.empty<String>().insertAllAfter(peer, RgaId.HEAD, listOf("p"))
        val survivors = mapOf(
            "insertAllAfter" to raised.insertAllAfter(me, RgaId.HEAD, listOf("q")).first,
            "removeFirst" to raised.removeFirst(1).first,
            "apply(Remove)" to raised.apply(RgaOp.Remove(minted.first().id)),
            "apply(peer insert)" to raised.apply(withPeer.operations().single()),
            "piece" to raised.piece(withPeer),
            "piece, other side" to withPeer.piece(raised),
            "dropWindow" to requireNotNull(raised.dropWindow(me, setOf(minted.first().id))).first,
        )
        assertAll(
            *survivors.map { (name, state) ->
                {
                    assertTrue(state.lamport >= 50L, "$name lowered the clock to ${state.lamport}")
                    assertTrue(state.seqHighWater(me) >= 30L, "$name lowered the seq to ${state.seqHighWater(me)}")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun aDecodedStateRaisesToo() {
        val (full, partial, _) = partialOfThree()
        val decoded = cbor.decodeFromByteArray(wire, cbor.encodeToByteArray(wire, partial))
        val raised = decoded.advanceClock(me, full.lamport, full.seqHighWater(me))
        assertAll(
            { assertEquals(full.lamport, raised.lamport) },
            { assertEquals(full.seqHighWater(me), raised.seqHighWater(me)) },
            { assertEquals(decoded.toList(), raised.toList()) },
        )
    }

    @Test
    fun aNegativeCounterIsRefused() {
        val state = Rga.empty<String>()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { state.advanceClock(me, -1L, 0L) } },
            { assertFailsWith<IllegalArgumentException> { state.advanceClock(me, 0L, -1L) } },
        )
    }
}
