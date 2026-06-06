package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smallest possible lattice: max-wins integer. Proves the interface plumbing. */
private data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

class QuiltedLawsTest {

    @Test
    fun pieceIsIdempotent() {
        val a = IntMax(3)
        assertEquals(a, a.piece(a))
    }

    @Test
    fun pieceIsCommutative() {
        assertEquals(IntMax(3).piece(IntMax(7)), IntMax(7).piece(IntMax(3)))
    }

    @Test
    fun pieceIsAssociative() {
        val a = IntMax(1); val b = IntMax(5); val c = IntMax(2)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    @Test
    fun patchAppliesViaPiece() {
        val state = IntMax(3)
        val patch = Patch(IntMax(5))
        assertEquals(IntMax(5), state.piece(patch))
    }
}
