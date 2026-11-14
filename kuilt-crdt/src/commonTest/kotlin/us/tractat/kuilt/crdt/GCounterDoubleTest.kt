package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
    fun pieceIsAssociative() {
        // Overlapping slots so a broken join (e.g. sum instead of max) would differ.
        val ga = GCounterDouble.of(a to 2.0, b to 1.0)
        val gb = GCounterDouble.of(b to 4.0, c to 3.0)
        val gc = GCounterDouble.of(a to 5.0, c to 1.0)
        // (a⊕b)⊕c == a⊕(b⊕c)
        assertEquals(ga.piece(gb).piece(gc), ga.piece(gb.piece(gc)))
        // a=max(2,5)=5, b=max(1,4)=4, c=max(3,1)=3 → 12.0
        assertEquals(12.0, ga.piece(gb).piece(gc).value)
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

    /**
     * A slot is non-negative, as on [GCounter]: [GCounterDouble.inc] only raises one. A negative
     * or NaN slot handed to [GCounterDouble.of] is refused (#3397). NaN matters as much as a
     * negative: `maxOf` with NaN is NaN, so one such slot would win every join of that replica.
     */
    @Test
    fun ofRefusesANegativeOrNaNSlot() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { GCounterDouble.of(a to 1.0, b to -0.5) } },
            { assertFailsWith<IllegalArgumentException> { GCounterDouble.of(a to Double.NaN) } },
            { assertEquals(0.0, GCounterDouble.of(a to 0.0).count(a), "a zero slot is a legal seed") },
        )
    }

    /** The decoder refuses one too, because the check lives in the type. The first assertion is the rig. */
    @Test
    fun decoderRefusesANegativeSlot() {
        val valid = Json.encodeToString(GCounterDouble.serializer(), GCounterDouble.of(a to 7.5))
        val negated = valid.replace(":7.5", ":-7.5")
        assertAll(
            { assertNotEquals(valid, negated, "the rig found the slot to negate in $valid") },
            { assertFailsWith<IllegalArgumentException> { Json.decodeFromString(GCounterDouble.serializer(), negated) } },
        )
    }
}
