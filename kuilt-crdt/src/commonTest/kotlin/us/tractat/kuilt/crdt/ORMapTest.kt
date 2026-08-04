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
    fun aReplicasSecondPutKeepsItsFirst() {
        // A put is additive over the value lattice, including across the putter's *own* writes. The
        // second put supersedes the first's tag, so the fresh tag has to carry what that tag held —
        // drop that fold and a replica silently loses its own history on every re-put.
        val m = ORMap.empty<String, GCounter>()
            .put(a, "votes", GCounter.of(a to 3L))
            .put(a, "votes", GCounter.of(b to 4L))

        assertEquals(7L, m["votes"]?.value)
        assertEquals(1, m.tagsOn("votes").size, "…while still leaving the replica one tag on the key")
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
        // …and the value is bob's contribution alone. Alice's remove retired tag (A,1), and a tag
        // takes the write made under it — so {a:1} goes with it, while bob's concurrent {b:1},
        // whose tag she never saw, stays. Before #2086 this read 2: bob's put folded {a:1} into a
        // single entry-level value that alice's remove could no longer reach, which is exactly the
        // blend that made `piece` non-associative.
        assertEquals(1L, merged["votes"]?.value)
    }

    @Test
    fun aRemoveTakesTheWritesItObservedAndLeavesTheOnesItDidNot() {
        // The same shape one step on: the key is put twice before the remove, so the removed
        // contribution is a fold of two writes rather than one.
        val start = ORMap.empty<String, GCounter>()
            .put(a, "votes", GCounter.of(a to 1L))
            .put(a, "votes", GCounter.of(a to 4L))
        val bob = start.put(b, "votes", GCounter.of(b to 2L))
        val merged = start.remove("votes").piece(bob)

        assertEquals(2L, merged["votes"]?.value, "only the write the remover never observed survives")
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
        // An entry keys its contributions by Dot, so plain JSON needs the structured-key flag —
        // same as MVRegister and ResettableCounter. CBOR and Protobuf need nothing.
        val json = Json { allowStructuredMapKeys = true }
        val m = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
        val ser = ORMap.serializer(String.serializer(), GCounter.serializer())
        assertEquals(m, json.decodeFromString(ser, json.encodeToString(ser, m)))
    }
}
