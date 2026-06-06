package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoPhaseSetTest {

    @Test
    fun emptySetContainsNothing() {
        assertFalse(TwoPhaseSet.empty<String>().contains("x"))
        assertEquals(emptySet(), TwoPhaseSet.empty<String>().elements)
    }

    @Test
    fun addThenContains() {
        val s = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
        assertTrue(s.contains("x"))
    }

    @Test
    fun removeMakesAbsent() {
        val s = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
        val gone = s.piece(s.remove("x"))
        assertFalse(gone.contains("x"))
    }

    @Test
    fun removeWinsOverConcurrentReAdd() {
        // start: "x" added. Alice removes; Bob (unaware) re-adds.
        val start = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
        val alice = start.piece(start.remove("x"))
        val bob = start.piece(start.add("x"))
        // The merge: tombstone wins forever. "x" stays gone.
        assertFalse(alice.piece(bob).contains("x"))
    }

    @Test
    fun mergeIsCommutative() {
        val start = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
        val alice = start.piece(start.remove("x"))
        val bob = start.piece(start.add("x"))
        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun cannotResurrectARemovedElement() {
        // Even on a later isolated add — tombstone permanence is the contract.
        val s = TwoPhaseSet.empty<String>()
            .let { it.piece(it.add("x")) }
            .let { it.piece(it.remove("x")) }
        val retried = s.piece(s.add("x"))
        assertFalse(retried.contains("x"))
    }

    @Test
    fun roundTripsThroughJson() {
        val s = TwoPhaseSet.empty<String>()
            .let { it.piece(it.add("x")) }
            .let { it.piece(it.add("y")) }
            .let { it.piece(it.remove("x")) }
        val ser = TwoPhaseSet.serializer(String.serializer())
        assertEquals(s, Json.decodeFromString(ser, Json.encodeToString(ser, s)))
    }
}
