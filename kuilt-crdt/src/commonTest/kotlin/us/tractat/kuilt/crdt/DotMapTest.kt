package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private fun present(key: String, vararg ds: Dot): DotMap<String, DotSet> =
        DotMap(mapOf(key to DotSet(ds.toSet())))

    @Test
    fun dotsFlattenAcrossKeys() {
        val map = DotMap(
            mapOf(
                "x" to DotSet(setOf(Dot(a, 1L))),
                "y" to DotSet(setOf(Dot(b, 1L))),
            ),
        )
        assertEquals(setOf(Dot(a, 1L), Dot(b, 1L)), map.dots)
    }

    @Test
    fun emptyMapIsBottom() {
        assertTrue(DotMap<String, DotSet>().isBottom)
    }

    @Test
    fun orSetAddWinsOverConcurrentRemove() {
        // OR-Set of one element "card", told as a DotMap<String, DotSet>.
        // Alice removed it (empty map), context remembers (A,1).
        val alice = Causal(DotMap<String, DotSet>(), DotContext.of(Dot(a, 1L)))
        // Bob concurrently re-added: card -> {(A,1),(B,1)}.
        val bob = Causal(present("card", Dot(a, 1L), Dot(b, 1L)), DotContext.of(Dot(a, 1L), Dot(b, 1L)))
        val merged = alice.piece(bob)
        // card survives via (B,1); (A,1) was seen+removed by Alice.
        assertEquals(setOf(Dot(b, 1L)), merged.store.entries["card"]?.dots)
    }

    @Test
    fun keyIsDroppedWhenItsNestedStoreBecomesBottom() {
        // Alice removed "card" (saw (A,1), now empty); Bob never re-added.
        val alice = Causal(DotMap<String, DotSet>(), DotContext.of(Dot(a, 1L)))
        val bob = Causal(present("card", Dot(a, 1L)), DotContext.of(Dot(a, 1L)))
        val merged = alice.piece(bob)
        assertTrue("card" !in merged.store.entries) // pruned entirely
    }
}
