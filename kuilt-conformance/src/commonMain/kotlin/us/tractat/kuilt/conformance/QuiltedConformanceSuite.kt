package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The assert → retire → re-assert shape a retiring binding names, **plus the evidence** that makes
 * it a retirement rather than three unrelated writes.
 *
 * The three states alone cannot carry that claim. Retirement is not expressible in the
 * join-semilattice algebra: a removal is *more information*, so `s → s.remove(k)` moves up exactly
 * as `s → s.add(k)` does. `asserted ⊑ retired ⊑ reAsserted` is therefore satisfied by *any* chain of
 * three additions — which is how the guard used to pass with the shape it exists to protect entirely
 * absent (#2157). So the binding also hands over a reading of its own public value surface: [shows],
 * a predicate over one named [subject]. That predicate is the only thing that can say
 * "[retired] stopped showing what [asserted] showed, and [reAsserted] shows it again".
 *
 * @param S the lattice type under test.
 */
public class RetirementReAssertion<S : Quilted<S>>(
    /**
     * What the three states are about, in one short phrase — `key "votes"`, `element "x"`,
     * `the register's value`. Appears in the failure message, and forces the binding author to say
     * out loud what it is that gets retired.
     */
    public val subject: String,
    /** A sample that **shows** [subject]. */
    public val asserted: S,
    /** A later sample that **no longer shows** it — the retirement. */
    public val retired: S,
    /** A later sample still that **shows it again** — the re-assertion. */
    public val reAsserted: S,
    /**
     * Reads [subject] off the binding's public value surface — `"votes" in it.keys`,
     * `it.contains("x")`, `it.store.values.isNotEmpty()`. Must be a function of the *value*, not of
     * object identity; the suite checks that by evaluating it on a freshly joined equal state.
     */
    public val shows: (S) -> Boolean,
)

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
 * shape, plus the subject predicate that makes the retirement observable, in
 * [retirementReAssertion]; see [samplesReAssertAfterRetirement] for why that shape is the one a
 * hand-picked list keeps missing, and why naming the three states alone did not pin it.
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
     * a later one still that **re-asserts** it — together with the predicate that reads that
     * "something" off the binding's public value surface.
     *
     * `null` — the default — goes with `retirementIsMeaningful = false`.
     *
     * **Why the binding has to declare this rather than the suite deriving it.** Retirement is not
     * expressible in the join-semilattice algebra: a removal is *more information*, so
     * `s → s.remove(k)` moves up exactly as `s → s.add(k)` does, and `s ⊔ s.remove(k) == s.remove(k)`
     * holds either way. Nothing the suite can compute from [Quilted.piece] and `==` separates the
     * two — which is precisely why the three states have to arrive with
     * [RetirementReAssertion.shows] attached, and why naming them alone was not enough (#2157).
     *
     * **The re-asserted value must be one the retired value does not dominate.** If it is simply
     * "different" — a larger count under the same author — a join that drops the retired
     * contribution lands on the same value as one that keeps it, and the shape proves nothing.
     * Measured against the pre-#2099 `ORMap`: re-asserting `GCounter.of(a to 1L)` or
     * `GCounter.of(a to 2L)` after `a` retired `GCounter.of(a to 1L)` finds **0** associativity
     * violations; re-asserting under a *different* author finds **12**. That last part is a
     * judgement the suite still cannot check — see [samplesReAssertAfterRetirement].
     */
    public open fun retirementReAssertion(): RetirementReAssertion<S>? = null

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
     *
     * **What "loses the shape" has to mean, and why ordering alone cannot say it.** Membership,
     * distinctness and `asserted ⊑ retired ⊑ reAsserted` are satisfied by any three ascending
     * writes, so a tidy-up that swaps the re-asserting sample for a put on an *unrelated* key —
     * and updates [retirementReAssertion] to match, which is what a real tidy-up looks like —
     * passed every one of them (#2157). The subject predicate the binding supplies is what closes
     * that: [RetirementReAssertion.shows] must be `true` for `asserted`, **`false` for `retired`**,
     * and `true` again for `reAsserted`. Nothing weaker distinguishes a retirement from an
     * addition, because in the lattice they are the same move.
     *
     * **Still not checked here:** that the re-asserted contribution is one the retired value does
     * not dominate (see [retirementReAssertion]). That is an authorship judgement about *which*
     * contribution comes back, not about *whether* the subject comes back, and it has no
     * expression in terms the suite can evaluate.
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
        val shape = assertNotNull(
            retirementReAssertion(),
            "retirementIsMeaningful is true, so retirementReAssertion() must name the three samples",
        )
        val asserted = shape.asserted
        val retired = shape.retired
        val reAsserted = shape.reAsserted
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
        assertRetirementIsObservable(shape)
    }

    /**
     * The half of [samplesReAssertAfterRetirement] that ordering cannot express: the named
     * [RetirementReAssertion.subject] is shown, then not shown, then shown again.
     */
    private fun assertRetirementIsObservable(shape: RetirementReAssertion<S>) {
        val subject = shape.subject
        assertTrue(
            shape.shows(shape.asserted),
            "the asserting state must show the subject it asserts ($subject): ${shape.asserted}",
        )
        assertFalse(
            shape.shows(shape.retired),
            "the retiring state must NOT show the subject ($subject) — a state that still shows it " +
                "retires nothing, and the three states are just a chain of additions: ${shape.retired}",
        )
        assertTrue(
            shape.shows(shape.reAsserted),
            "the re-asserting state must show the subject ($subject) again — a state that asserts " +
                "something *else* ascends the lattice exactly the same way and proves nothing, " +
                "which is how this guard once passed with the shape absent (#2157): ${shape.reAsserted}",
        )
        assertFalse(
            shape.shows(shape.retired.piece(shape.asserted)),
            "re-joining the asserted state must not resurrect the subject ($subject) — the " +
                "retirement has to win, and `shows` has to read the value rather than the instance",
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
