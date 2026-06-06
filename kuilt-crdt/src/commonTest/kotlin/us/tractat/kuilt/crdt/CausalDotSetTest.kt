package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausalDotSetTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun addWinsOverConcurrentRemove() {
        // Alice removed the only dot she saw; her context still remembers (A,1).
        val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L)))
        // Bob concurrently added a fresh dot; he still holds both.
        val bob = Causal(
            DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )
        val merged = alice.piece(bob)
        // (A,1): Alice saw & dropped -> gone. (B,1): Alice never saw -> kept.
        assertEquals(setOf(Dot(b, 1L)), merged.store.dots)
        assertFalse(merged.store.isBottom) // present — add wins
    }

    @Test
    fun removeWinsWhenTheAddWasAlreadySeen() {
        // If Alice had ALSO seen (B,1) before removing, (B,1) drops too.
        val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L), Dot(b, 1L)))
        val bob = Causal(
            DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )
        assertTrue(alice.piece(bob).store.isBottom) // gone
    }

    @Test
    fun mergeIsIdempotent() {
        val x = Causal(DotSet(setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L)))
        assertEquals(x, x.piece(x))
    }

    @Test
    fun mergeIsCommutative() {
        val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L)))
        val bob = Causal(DotSet(setOf(Dot(a, 1L), Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L)))
        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun roundTripsThroughJson() {
        val x = Causal(DotSet(setOf(Dot(a, 1L), Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L)))
        val ser = Causal.serializer(DotSet.serializer())
        val encoded = Json.encodeToString(ser, x)
        assertEquals(x, Json.decodeFromString(ser, encoded))
    }
}
