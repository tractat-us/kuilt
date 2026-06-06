package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GSetTest {

    @Test
    fun emptySetContainsNothing() {
        assertFalse(GSet.empty<String>().contains("x"))
        assertEquals(emptySet(), GSet.empty<String>().elements)
    }

    @Test
    fun addProducesADeltaThatAddsAnElement() {
        val empty = GSet.empty<String>()
        val delta = empty.add("x")
        val next = empty.piece(delta)
        assertTrue(next.contains("x"))
        assertEquals(setOf("x"), next.elements)
    }

    @Test
    fun mergeIsUnion() {
        val left = GSet.of("x", "y")
        val right = GSet.of("y", "z")
        assertEquals(setOf("x", "y", "z"), left.piece(right).elements)
    }

    @Test
    fun mergeIsCommutative() {
        val left = GSet.of("x")
        val right = GSet.of("y")
        assertEquals(left.piece(right), right.piece(left))
    }

    @Test
    fun addIsIdempotent() {
        val once = GSet.empty<String>().piece(GSet.empty<String>().add("x"))
        assertEquals(once, once.piece(once.add("x")))
    }

    @Test
    fun roundTripsThroughJson() {
        val s = GSet.of("x", "y")
        val ser = GSet.serializer(String.serializer())
        assertEquals(s, Json.decodeFromString(ser, Json.encodeToString(ser, s)))
    }
}
