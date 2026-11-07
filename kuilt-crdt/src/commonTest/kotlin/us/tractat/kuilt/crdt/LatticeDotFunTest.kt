package us.tractat.kuilt.crdt

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [LatticeDotFun]'s join: a dot both sides hold keeps the **join** of its two payloads, where
 * [DotFun] keeps the left operand's. Everything else is the causal rule every dot store shares.
 */
class LatticeDotFunTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")
    private val d1 = Dot(a, 1L)

    private val json = Json { allowStructuredMapKeys = true }

    private fun counter(vararg pairs: Pair<ReplicaId, Long>): GCounter = GCounter.of(*pairs)

    /** One dot, held by both replicas, each having grown its payload on its own. */
    private val bumpedByB = Causal(LatticeDotFun(mapOf(d1 to counter(a to 1L, b to 2L))), DotContext.of(d1))
    private val bumpedByC = Causal(LatticeDotFun(mapOf(d1 to counter(a to 1L, c to 5L))), DotContext.of(d1))

    @Test
    fun aSharedDotKeepsTheJoinOfBothPayloads() {
        val merged = bumpedByB.piece(bumpedByC)
        assertEquals(mapOf(d1 to counter(a to 1L, b to 2L, c to 5L)), merged.store.values)
    }

    @Test
    fun aSharedDotWithTwoPayloadsCommutes() {
        assertEquals(bumpedByB.piece(bumpedByC), bumpedByC.piece(bumpedByB))
    }

    /**
     * The positive control for the test above: the same two states as [DotFun] do **not** commute,
     * which is the defect this type exists to remove. If `DotFun` ever starts joining payloads this
     * reds, and the reason for [LatticeDotFun] should be re-read.
     */
    @Test
    fun dotFunDoesNotCommuteOnTheSameStates() {
        val left = Causal(DotFun(bumpedByB.store.values), bumpedByB.context)
        val right = Causal(DotFun(bumpedByC.store.values), bumpedByC.context)
        assertNotEquals(left.piece(right), right.piece(left))
    }

    @Test
    fun anObservedRemoveBeatsAConcurrentBump() {
        // C saw d1 and removed it; B, concurrently, grew d1's payload. The remove wins: d1 is in
        // C's context and absent from its store, which is the signature of a deliberate remove.
        val removedByC = Causal(LatticeDotFun<GCounter>(), DotContext.of(d1))
        val merged = bumpedByB.piece(removedByC)
        assertAll(
            { assertEquals(emptyMap(), merged.store.values) },
            { assertEquals(merged, removedByC.piece(bumpedByB)) },
        )
    }

    /**
     * The causal rule every dot store shares, so this passes on [DotFun] too: it pins that the
     * payload join did not disturb it.
     */
    @Test
    fun anUnseenDotSurvivesEitherSide() {
        val d2 = Dot(b, 1L)
        val onlyB = Causal(LatticeDotFun(mapOf(d2 to counter(b to 1L))), DotContext.of(d2))
        val expected = mapOf(d1 to counter(a to 1L, c to 5L), d2 to counter(b to 1L))
        assertAll(
            { assertEquals(expected, bumpedByC.piece(onlyB).store.values) },
            { assertEquals(expected, onlyB.piece(bumpedByC).store.values) },
        )
    }

    /**
     * The delta of a payload bump is `({d ↦ δv}, {d})`. Its reference is a whole state built by
     * hand — the payload joined in place — not `x.piece(delta)` itself, which would compare the
     * delta path against its own spelling (#2111).
     */
    @Test
    fun aBumpDeltaReproducesTheWholeBump() {
        val x = Causal(
            LatticeDotFun(mapOf(d1 to counter(a to 1L), Dot(b, 1L) to counter(b to 1L))),
            DotContext.of(d1, Dot(b, 1L)),
        )
        val payloadDelta = counter(a to 1L).inc(a, 3L).delta
        val delta = Causal(LatticeDotFun(mapOf(d1 to payloadDelta)), DotContext.of(d1))
        val whole = Causal(
            LatticeDotFun(mapOf(d1 to counter(a to 4L), Dot(b, 1L) to counter(b to 1L))),
            x.context,
        )
        assertEquals(whole, x.piece(delta))
    }

    @Test
    fun roundTripsThroughJson() {
        val ser = Causal.serializer(LatticeDotFun.serializer(GCounter.serializer()))
        val merged = bumpedByB.piece(bumpedByC)
        assertEquals(merged, json.decodeFromString(ser, json.encodeToString(ser, merged)))
    }

    @Test
    fun encodesIdenticallyWhicheverWayTheJoinRan() {
        val d2 = Dot(b, 1L)
        val d3 = Dot(c, 1L)
        val one = Causal(LatticeDotFun(mapOf(d3 to counter(c to 1L))), DotContext.of(d3))
        val two = Causal(LatticeDotFun(mapOf(d2 to counter(b to 1L))), DotContext.of(d2))
        val ser = Causal.serializer(LatticeDotFun.serializer(GCounter.serializer()))
        assertContentEquals(
            Cbor.encodeToByteArray(ser, one.piece(two).piece(bumpedByB)),
            Cbor.encodeToByteArray(ser, bumpedByB.piece(two).piece(one)),
        )
    }
}
