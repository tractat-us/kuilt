package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 *
 * A type that can **retire** — a map or set with `remove`, a register whose write supersedes the
 * last one — additionally sets [retirementIsMeaningful] and names the assert/retire/re-assert
 * shape in [retirementReAssertion]; see [samplesReAssertAfterRetirement] for why that shape is the
 * one a hand-picked list keeps missing.
 */
public abstract class QuiltedConformanceSuite<S : Quilted<S>> {

    /** Representative, distinct sample values (≥ 3). */
    public abstract fun samples(): List<S>

    /**
     * Whether this type can **retire** — stop showing something it once showed, while still only
     * moving *up* the lattice. A map with `remove`, a set with `remove`, a register whose write
     * supersedes the previous one: all retire. A grow-only type — a counter, `GSet`, `IntMax` —
     * does not, and leaves this `false` (the default) so nothing here applies to it.
     *
     * A binding that sets this to `true` must also name the shape in [retirementReAssertion].
     */
    public open val retirementIsMeaningful: Boolean get() = false

    /**
     * Three of this binding's [samples], in causal order, spelling out the shape a retiring type is
     * easiest to get wrong on: one that **asserts** something, a later one that **retires** it, and
     * a later one still that **re-asserts** it.
     *
     * `null` — the default — goes with `retirementIsMeaningful = false`.
     *
     * **Why the binding has to declare this rather than the suite deriving it.** Retirement is not
     * expressible in the join-semilattice algebra: a removal is *more information*, so
     * `s → s.remove(k)` moves up exactly as `s → s.add(k)` does, and `s ⊔ s.remove(k) == s.remove(k)`
     * holds either way. Nothing the suite can compute from [Quilted.piece] and `==` separates the
     * two, so the binding names the samples and [samplesReAssertAfterRetirement] checks the claim
     * is consistent and that the samples are really there.
     *
     * **The re-asserted value must be one the retired value does not dominate.** If it is simply
     * "different" — a larger count under the same author — a join that drops the retired
     * contribution lands on the same value as one that keeps it, and the shape proves nothing.
     * Measured against the pre-#2099 `ORMap`: re-asserting `GCounter.of(a to 1L)` or
     * `GCounter.of(a to 2L)` after `a` retired `GCounter.of(a to 1L)` finds **0** associativity
     * violations; re-asserting under a *different* author finds **12**.
     */
    public open fun retirementReAssertion(): Triple<S, S, S>? = null

    /**
     * A binding that can retire must keep a sample that **re-asserts what an earlier sample
     * retired** — assert, retire, assert again.
     *
     * That third state is the one a hand-picked sample list reliably lacks, and it is the one the
     * interesting defects need: a value blended into an entry at join time survives a later
     * retirement of the tag that carried it, and the survival depends on the order the operands
     * were joined in (#2086). Without it, every law here passes on a lattice that loses writes —
     * measured: `ORMap`'s five samples found **0** violations against the broken type, and one
     * added sample found **12**.
     *
     * This test is what stops that sample being deleted by a future tidy-up: a list that loses the
     * shape fails rather than quietly passing.
     */
    @Test
    public fun samplesReAssertAfterRetirement() {
        if (!retirementIsMeaningful) {
            assertNull(
                retirementReAssertion(),
                "retirementIsMeaningful is false, so retirementReAssertion() must be null — " +
                    "naming the shape claims a retirement the binding has declared it does not have",
            )
            return
        }
        val (asserted, retired, reAsserted) = assertNotNull(
            retirementReAssertion(),
            "retirementIsMeaningful is true, so retirementReAssertion() must name the three samples",
        )
        val s = samples()
        assertTrue(asserted in s, "the asserting state must be one of samples(): $asserted")
        assertTrue(retired in s, "the retiring state must be one of samples(): $retired")
        assertTrue(reAsserted in s, "the re-asserting state must be one of samples(): $reAsserted")
        assertEquals(
            3,
            setOf(asserted, retired, reAsserted).size,
            "the three states must be distinct, or the shape collapses",
        )
        assertEquals(
            retired,
            asserted.piece(retired),
            "the retiring state must sit above the asserting one — a retirement observes what it retires",
        )
        assertEquals(
            reAsserted,
            retired.piece(reAsserted),
            "the re-asserting state must sit above the retiring one — a re-assertion observes the retirement",
        )
    }

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
