package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GCounterDoubleTest {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    @Test
    fun incAccumulatesOwnSlot() {
        var g = GCounterDouble.ZERO
        g = g.piece(g.inc(a, 1.5).delta)
        g = g.piece(g.inc(a, 2.25).delta)
        assertEquals(3.75, g.value)
        assertEquals(3.75, g.count(a))
        assertEquals(0.0, g.count(b))
    }

    @Test
    fun incMustBePositive() {
        assertFailsWith<IllegalArgumentException> { GCounterDouble.ZERO.inc(a, 0.0) }
        assertFailsWith<IllegalArgumentException> { GCounterDouble.ZERO.inc(a, -1.0) }
    }

    @Test
    fun pieceIsElementwiseMaxAndSumsSlots() {
        val ga = GCounterDouble.of(a to 2.0, b to 1.0)
        val gb = GCounterDouble.of(b to 4.0, c to 3.0)
        val merged = ga.piece(gb)
        // a=2, b=max(1,4)=4, c=3  → 9.0
        assertEquals(9.0, merged.value)
        assertEquals(merged, gb.piece(ga)) // commutative
        assertEquals(merged, merged.piece(gb)) // idempotent
    }

    @Test
    fun valueIsCanonicalOrderIndependent() {
        // Same converged state built two ways must report the same value bit-for-bit.
        val forward = GCounterDouble.of(a to 0.1, b to 0.2, c to 0.3)
        val shuffled = GCounterDouble.of(c to 0.3, a to 0.1, b to 0.2)
        assertEquals(forward.value, shuffled.value)
        // And equal to an explicit canonical (sorted-key) sum.
        val canonical = listOf(a to 0.1, b to 0.2, c to 0.3).sortedBy { it.first }.sumOf { it.second }
        assertEquals(canonical, forward.value)
    }

    @Test
    fun deltaCarriesOnlyBumpedSlot() {
        val patch = GCounterDouble.of(a to 5.0).inc(a, 1.0)
        assertEquals(6.0, patch.delta.count(a))
        assertTrue(patch.delta.count(b) == 0.0)
    }
}
