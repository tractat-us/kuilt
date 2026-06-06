package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reusable contract test suite for [Quilted] (delta-state CRDT) implementations.
 *
 * Subclass and implement [samples] to bind any type under test; every [Test]
 * encodes a law a conforming join-semilattice must satisfy. Lives in
 * `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every
 * CRDT type can subclass it from its own test source set.
 *
 * ```kotlin
 * class GCounterConformanceTest : QuiltedConformanceSuite<GCounter>() {
 *     override fun samples(): List<GCounter> = listOf(/* representative values */)
 * }
 * ```
 *
 * [samples] must return at least **three distinct** values for the associativity
 * and absorption checks to be meaningful; more variety is better. The type's
 * `equals` must reflect lattice equality — the laws are checked with `==`.
 */
public abstract class QuiltedConformanceSuite<S : Quilted<S>> {

    /** Representative, distinct sample values (≥ 3). */
    public abstract fun samples(): List<S>

    @Test
    public fun pieceIsIdempotent() {
        for (a in samples()) {
            assertEquals(a, a.piece(a), "piece must be idempotent for $a")
        }
    }

    @Test
    public fun pieceIsCommutative() {
        val s = samples()
        for (a in s) for (b in s) {
            assertEquals(a.piece(b), b.piece(a), "piece must be commutative for $a, $b")
        }
    }

    @Test
    public fun pieceIsAssociative() {
        val s = samples()
        for (a in s) for (b in s) for (c in s) {
            assertEquals(
                a.piece(b).piece(c),
                a.piece(b.piece(c)),
                "piece must be associative for $a, $b, $c",
            )
        }
    }

    @Test
    public fun pieceIsLeastUpperBound() {
        // The join of a and b must absorb both operands: merging either back in
        // changes nothing. This is what makes resends and reordering harmless.
        val s = samples()
        for (a in s) for (b in s) {
            val joined = a.piece(b)
            assertEquals(joined, joined.piece(a), "join must absorb left operand $a")
            assertEquals(joined, joined.piece(b), "join must absorb right operand $b")
        }
    }
}
