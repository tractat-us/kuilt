package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ORSetTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun addThenContains() {
        val s = ORSet.empty<String>().add(a, "card")
        assertTrue(s.contains("card"))
        assertEquals(setOf("card"), s.elements)
    }

    @Test
    fun removeMakesAbsent() {
        val s = ORSet.empty<String>().add(a, "card").remove("card")
        assertFalse(s.contains("card"))
        assertEquals(emptySet(), s.elements)
    }

    @Test
    fun addWinsOverConcurrentRemove() {
        // shared start: A added "card"
        val start = ORSet.empty<String>().add(a, "card")
        val alice = start.remove("card")     // Alice removes what she saw
        val bob = start.add(b, "card")       // Bob concurrently re-adds
        val merged = alice.piece(bob)
        assertTrue(merged.contains("card"))  // add wins
    }

    @Test
    fun removeWinsWhenNothingConcurrentlyAdded() {
        val start = ORSet.empty<String>().add(a, "card")
        val alice = start.remove("card")
        // Bob did nothing new; merging the removal with the stale-present state drops it
        val merged = alice.piece(start)
        assertFalse(merged.contains("card"))
    }

    @Test
    fun mergeIsCommutative() {
        val start = ORSet.empty<String>().add(a, "card")
        val alice = start.remove("card")
        val bob = start.add(b, "card")
        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun roundTripsThroughJson() {
        val s = ORSet.empty<String>().add(a, "x").add(b, "y")
        val ser = ORSet.serializer(String.serializer())
        assertEquals(s, Json.decodeFromString(ser, Json.encodeToString(ser, s)))
    }
}
