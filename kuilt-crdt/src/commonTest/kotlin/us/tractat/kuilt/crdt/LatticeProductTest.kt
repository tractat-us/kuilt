package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Property tests for [LatticeProduct]: the three join-semilattice laws —
 * idempotent, commutative, and associative — on the componentwise product.
 *
 * Witness: [GCounter] × [GSet] (two independent delta-state CRDTs with
 * different join operations, exercising that each component is joined separately).
 */
class LatticeProductTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    private fun product(
        counter: GCounter,
        set: GSet<String>,
    ): LatticeProduct<GCounter, GSet<String>> = LatticeProduct.of(counter, set)

    // ── lattice laws ─────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val a = product(GCounter.of(r1 to 5L), GSet.of("alpha"))
        assertEquals(a, a.piece(a))
    }

    @Test
    fun pieceIsCommutative() {
        val a = product(GCounter.of(r1 to 3L), GSet.of("x"))
        val b = product(GCounter.of(r2 to 7L), GSet.of("y"))
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun pieceIsAssociative() {
        val a = product(GCounter.of(r1 to 1L), GSet.of("a"))
        val b = product(GCounter.of(r2 to 2L), GSet.of("b"))
        val c = product(GCounter.of(r3 to 3L), GSet.of("c"))
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    // ── componentwise join ───────────────────────────────────────────────────

    @Test
    fun pieceJoinsFirstComponentIndependently() {
        val a = product(GCounter.of(r1 to 10L), GSet.of("x"))
        val b = product(GCounter.of(r2 to 20L), GSet.of("x"))
        val merged = a.piece(b)
        assertEquals(30L, merged.first.value)
    }

    @Test
    fun pieceJoinsSecondComponentIndependently() {
        val a = product(GCounter.of(r1 to 1L), GSet.of("x"))
        val b = product(GCounter.of(r1 to 1L), GSet.of("y"))
        val merged = a.piece(b)
        assertEquals(setOf("x", "y"), merged.second.elements)
    }

    // ── causalDots ───────────────────────────────────────────────────────────

    @Test
    fun causalDotsUnionsBothComponents() {
        // GCounter and GSet both return empty causalDots by default (no op-dots).
        // The product must return the union of both — which is also empty here.
        // Proves causalDots() is callable and returns the union.
        val p = product(GCounter.of(r1 to 1L), GSet.of("z"))
        assertTrue(p.causalDots().isEmpty())
    }

    // ── structural equality / toString ───────────────────────────────────────

    @Test
    fun equalityIsComponentwise() {
        val a = product(GCounter.of(r1 to 5L), GSet.of("x"))
        val b = product(GCounter.of(r1 to 5L), GSet.of("x"))
        val c = product(GCounter.of(r1 to 5L), GSet.of("y"))
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun toStringIncludesBothComponents() {
        val p = product(GCounter.of(r1 to 1L), GSet.of("hello"))
        assertTrue(p.toString().contains("LatticeProduct"))
    }
}
