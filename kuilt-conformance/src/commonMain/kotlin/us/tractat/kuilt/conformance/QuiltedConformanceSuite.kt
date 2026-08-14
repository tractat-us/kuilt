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
 * The least number of pairwise-distinct [QuiltedConformanceSuite.samples] the four laws need before
 * any of them is a claim about the type rather than about one value.
 *
 * **Three, not two, because associativity is the law that pays for the third.** A two-value list
 * cannot spell an ordered triple of three *different* operands, and that triple is the whole content
 * of `(a ⊔ b) ⊔ c == a ⊔ (b ⊔ c)`: with only `a` and `b` available, every triple repeats an operand,
 * and a bracketing that drops a contribution as soon as a third one arrives between the brackets is
 * unreachable. That is not a hypothetical shape — it is the one
 * [QuiltedConformanceSuite.samplesReAssertAfterRetirement] exists for, and the one whose absence let
 * a real lattice defect sit under a green `pieceIsAssociative`.
 */
public const val DISTINCT_SAMPLE_FLOOR: Int = 3

/**
 * What a binding's [QuiltedConformanceSuite.samples] list actually offered the laws, measured by
 * [checkSampleEvidenceFloor].
 *
 * Printed on every run, green or red, for the reason the sibling suite prints its vacuity rates: a
 * floor whose value nobody sees is a floor nobody notices drifting toward. The two counts are
 * carried separately because they fail differently and want different fixes — `5 samples,
 * 5 distinct` is healthy, `5 samples, 2 distinct` is a list with three spellings of one value in it,
 * and only the second number is the floor.
 *
 * @param samples entries the binding returned.
 * @param distinctSamples entries no earlier entry equalled, by `==`. See [checkSampleEvidenceFloor]
 *   for why this is counted rather than taken from a `Set`, and for what `==` is the right relation.
 */
public class SampleEvidenceReport(
    public val samples: Int,
    public val distinctSamples: Int,
) {
    override fun toString(): String =
        "  samples returned  $samples\n" +
            "  distinct by `==`  $distinctSamples  floor ≥ $DISTINCT_SAMPLE_FLOOR"
}

/**
 * Measure [samples] and refuse a list too thin for [QuiltedConformanceSuite]'s laws to have tested
 * anything — fewer than [DISTINCT_SAMPLE_FLOOR] values distinct by `==`.
 *
 * Separate from the suite, and public, for the reason
 * [us.tractat.kuilt.conformance.lattice.LatticeLawHarness.checkVacuityFloors] is: a floor that lives
 * only inside a `@Test` method can be rigged only by subclassing the suite, and a subclass of a
 * suite is itself a test class the framework discovers and runs — so the rig would fail the build it
 * is trying to document. `SampleEvidenceFloorRigs` in this module's `commonTest` calls this
 * function, and the suite's [QuiltedConformanceSuite.samplesMeetTheEvidenceFloor] is a delegation to
 * it. A binding that composes rather than subclasses can check its own list the same way.
 *
 * **Distinct means distinct by `==`, and for this suite that is not a choice between two readings —
 * it is the only relation available.** Every assertion in [QuiltedConformanceSuite] compares with
 * `==`, so two samples that are `==` are literally interchangeable in every one of them and the
 * second contributes no comparison the first did not already make. The obvious alternative,
 * distinctness by *encoded bytes*, is not merely a stricter reading here — it is unavailable:
 * [Quilted] carries no serializer, and this suite has no codec in it anywhere. It is also not the
 * same measurement, which is worth being explicit about rather than assuming the two agree: the
 * sibling's codec pass counts distinct states and distinct *encodings* as two separate numbers
 * precisely because a type whose `equals` is coarser than its wire form is legitimate, and an
 * injectivity law relating them was considered and rejected on those grounds (#2342). A binding
 * whose samples differ only in bytes is, to the four laws here, one sample — so `==` is not a
 * weaker floor than bytes would be, it is the floor that describes what these laws can see.
 *
 * **Counted in `O(n²)` against a growing list, deliberately not `samples.toSet().size`.** `hashCode`
 * is not part of the [Quilted] contract — a type that overrides equality without it reads every
 * entry as distinct through a `Set`, so the `toSet` spelling passes `listOf(x, x, x)` on exactly the
 * types whose author was least careful, which is the decoration this floor exists to replace. The
 * sibling harness counts its pool the same way and for the same reason.
 *
 * @throws IllegalStateException if fewer than [DISTINCT_SAMPLE_FLOOR] entries are pairwise distinct.
 * @return what was measured, so a caller can print it on a green run too.
 */
public fun <S : Quilted<S>> checkSampleEvidenceFloor(samples: List<S>): SampleEvidenceReport {
    val distinct = ArrayList<S>(samples.size)
    for (sample in samples) if (distinct.none { it == sample }) distinct += sample
    val report = SampleEvidenceReport(samples = samples.size, distinctSamples = distinct.size)
    check(distinct.size >= DISTINCT_SAMPLE_FLOOR) {
        "samples() is too thin for the laws to have tested anything: ${samples.size} sample(s), " +
            "${distinct.size} distinct by `==`, floor $DISTINCT_SAMPLE_FLOOR. Every law in " +
            "QuiltedConformanceSuite quantifies over samples(), so on this list each of them " +
            "compared a value with itself and passed — on any type whatsoever. " +
            "Distinct values were: $distinct"
    }
    return report
}

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
 * and absorption checks to be meaningful; more variety is better. That floor is
 * **checked** — by [samplesMeetTheEvidenceFloor], since #2312. It spent a long time documented and
 * unenforced, which is the shape a precondition takes when every binding written so far happens to
 * satisfy it. The type's `equals` must reflect lattice equality — the laws are checked with `==`,
 * and so is the floor.
 *
 * A type that can **retire** additionally sets [retirementIsMeaningful] and names the
 * assert/retire/re-assert shape, plus the subject predicate that makes the retirement observable,
 * in [retirementReAssertion]; see [samplesReAssertAfterRetirement] for why that shape is the one a
 * hand-picked list keeps missing, and why naming the three states alone did not pin it. What
 * counts as retiring is defined once, in [us.tractat.kuilt.conformance.lattice.OpKind] — including
 * the reading this surface is licensed to take that the lattice bindings are not.
 */
public abstract class QuiltedConformanceSuite<S : Quilted<S>> {

    /**
     * Representative, distinct sample values — at least [DISTINCT_SAMPLE_FLOOR] of them, distinct by
     * `==`, enforced by [samplesMeetTheEvidenceFloor].
     */
    public abstract fun samples(): List<S>

    /**
     * The four laws below were handed enough evidence to have tested anything: **at least
     * [DISTINCT_SAMPLE_FLOOR] pairwise-distinct samples**.
     *
     * **This is not a test of the type; it is a test of the evidence** — the same job
     * [us.tractat.kuilt.conformance.lattice.LatticeLawSuite.generatorIsNotVacuous] does for the
     * generated half of this pair, which is the half that had it. Every law here quantifies over
     * [samples], so a binding returning `listOf(x)` — or `listOf(x, x, x)` — clears
     * [pieceIsIdempotent], [pieceIsCommutative], [pieceIsAssociative] and [pieceIsLeastUpperBound]
     * having compared one value with itself, on *any* type whatsoever, and the suite reports four
     * green laws over a lattice it never entered. That was true of this suite from the day it was
     * written: the floor was in [samples]' KDoc and nothing read it (#2312). Hand-picked lists are
     * exactly the ones a tidy-up shrinks, and a shrunk list has no other tripwire.
     *
     * The measured counts print on every run, green or red, because a floor whose value nobody sees
     * is a floor nobody notices drifting toward.
     *
     * **Distinct by `==`, and why that is the reading rather than distinct-by-bytes** — see
     * [checkSampleEvidenceFloor], which also argues why the count is not `samples().toSet().size`.
     *
     * **What this cannot detect, and where each one is covered instead:**
     * - **Three distinct but totally ordered samples.** A chain clears this floor, and a chain is a
     *   thinner search than three mutually concurrent states — concurrency is where a join has to
     *   decide something. It is not floored here because it cannot be: a genuinely
     *   totally-ordered lattice has no concurrent pair to offer (`IntMax` is one, and its binding is
     *   a chain by nature), so a concurrency floor needs a per-binding declaration to waive it. The
     *   sibling has exactly that —
     *   [us.tractat.kuilt.conformance.lattice.VacuityFloors.concurrentPairs], waived by
     *   [us.tractat.kuilt.conformance.lattice.VacuityFloors.totalOrder] — and measures it as a
     *   *rate* over a generated pool, which a hand-picked list of four is not.
     * - **Samples that are distinct but dull** — three values that never exercise a branch of the
     *   type's `piece`. Nothing here reads the type's decision tree; [retirementReAssertion] is the
     *   one shape this suite insists on by name, and the sibling's op alphabet is where breadth is
     *   measured.
     * - **A type whose `equals` is broken outright** — one returning `false` for a value against
     *   itself reads every entry as distinct and clears this floor. [pieceIsIdempotent] reds on such
     *   a type first, so the pair is sound; this guard alone is not.
     * - **A [samples] that returns a different list on each call.** The floor measures the call it
     *   made; another law's call could still be thin. No binding does this and nothing stops one.
     */
    @Test
    public open fun samplesMeetTheEvidenceFloor() {
        val report = checkSampleEvidenceFloor(samples())
        println("${this::class.simpleName} — sample evidence\n$report")
    }

    /**
     * Whether this type can **retire** — stop showing something it once showed, while still only
     * moving *up* the lattice.
     *
     * **What counts as retiring is defined in exactly one place:**
     * [us.tractat.kuilt.conformance.lattice.OpKind], as *an op retires when it takes an observation
     * back without putting another in its place*. Read it there rather than re-deriving it here;
     * the definition has drifted between surfaces twice already, which is why it now has a single
     * home (#2146, #2159).
     *
     * **This surface reads it more generously than the lattice bindings do, deliberately.** A
     * register's `set` *supersedes* rather than withdraws, so `MVRegisterConvergenceTest` declares
     * no retiring op at all — yet `MVRegisterConformanceTest` sets this `true` and names a `set`
     * that stops the register showing an earlier value. [OpKind] carries the rule that makes both
     * right: **classify strictly where the answer is averaged, generously where it is checked.**
     * There the classification feeds a *rate* — the retirement vacuity floor — which a label every
     * op carries would clear by construction. Here it gates a *single constructed triple whose
     * every step is asserted* ([samplesReAssertAfterRetirement]), so a generous reading buys one
     * more checked shape and can inflate nothing; and a supersession that did not really stop the
     * old value being shown reds the guard rather than passing it.
     *
     * A binding that sets this to `true` must also name the shape in [retirementReAssertion]. The
     * `false` default is an **opt-out, not evidence**: a type that genuinely cannot retire reads
     * `false`, and so does a retiring binding that has not named its triple yet (#2167). A binding
     * that leaves it `false` on a type its lattice binding declares retiring should say why.
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
