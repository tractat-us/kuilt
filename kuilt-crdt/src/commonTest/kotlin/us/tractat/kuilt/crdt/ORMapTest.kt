package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ORMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyMap() {
        assertNull(ORMap.empty<String, GCounter>()["nope"])
        assertEquals(emptySet<String>(), ORMap.empty<String, GCounter>().keys)
    }

    @Test
    fun putThenContains() {
        val m = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        assertTrue("votes" in m.keys)
        assertEquals(1L, m["votes"]?.value)
    }

    @Test
    fun valuesMergeViaTheirOwnPiece() {
        // Alice and Bob each insert their own per-replica GCounter under "votes"; merge sums them.
        val mA = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 3L))
        val mB = ORMap.empty<String, GCounter>().put(b, "votes", GCounter.of(b to 5L))
        val merged = mA.piece(mB)
        assertEquals(8L, merged["votes"]?.value)
    }

    @Test
    fun removeMakesKeyAbsent() {
        val m = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        assertFalse("votes" in m.remove("votes").keys)
        assertNull(m.remove("votes")["votes"])
    }

    @Test
    fun addWinsOverConcurrentRemove() {
        // shared start: alice puts "votes" -> {a:1}
        val start = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        val alice = start.remove("votes")            // alice removes what she saw
        val bob = start.put(b, "votes", GCounter.of(b to 1L)) // bob concurrently re-puts
        val merged = alice.piece(bob)
        assertTrue("votes" in merged.keys) // add wins: bob's presence tag (B,1) survives
        // Bob's put pieced {a:1} with {b:1} — both GCounter slots survive (GCounter merge is max).
        // Alice's remove only tombstoned the presence tag (A,1), not the GCounter value.
        assertEquals(2L, merged["votes"]?.value)
    }

    @Test
    fun mergeIsCommutative() {
        val start = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        val alice = start.remove("votes")
        val bob = start.put(b, "votes", GCounter.of(b to 1L))
        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun roundTripsThroughJson() {
        val m = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        val ser = ORMap.serializer(String.serializer(), GCounter.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }
}
