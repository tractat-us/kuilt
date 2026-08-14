package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A binding rigged **below** the evidence floor: two distinct samples where
 * [DISTINCT_SAMPLE_FLOOR] is three. Not a binding of `IntMax` — [IntMaxConformanceTest] is that.
 *
 * It is a real, discovered test class on purpose, and that is the whole receipt. The four laws run
 * against this fixture for real, and they pass — which is what the pre-existing suite did with a
 * list this thin, measured rather than asserted in prose. Only
 * [QuiltedConformanceSuite.samplesMeetTheEvidenceFloor] is overridden, inverted so that the floor
 * *raising* is what keeps the build green.
 *
 * **Two distinct, not one, because the boundary is where a floor gets quietly lowered.** A rig of a
 * single sample reds a floor of 2 and a floor of 3 alike, so it cannot tell them apart; this one
 * fails only while the floor is genuinely 3. Its sibling
 * [SampleEvidenceFloorSelfTest.aSingleSampleRedsTheFloorToo] covers the degenerate end.
 *
 * @see SampleEvidenceFloorSelfTest for why this pairs with a rig that calls the check directly.
 */
internal class TwoDistinctSamplesRigTest : QuiltedConformanceSuite<IntMax>() {

    override fun samples(): List<IntMax> = listOf(IntMax(1), IntMax(4))

    /**
     * Inverted: this fixture must **fail** the floor, so a green run here is the receipt.
     *
     * Calls `super` rather than [checkSampleEvidenceFloor] deliberately. That is the only thing in
     * the repo that pins the suite's own `@Test` to the check — delete the call from
     * [QuiltedConformanceSuite.samplesMeetTheEvidenceFloor] and every binding silently loses the
     * guard while [SampleEvidenceFloorSelfTest], which calls the function directly, stays green.
     * The pair is the one [us.tractat.kuilt.conformance.lattice.VacuityFloorSelfTest] draws: one
     * arm covers a floor lowered to nothing, the other a floor no longer consulted.
     */
    @Test
    override fun samplesMeetTheEvidenceFloor() {
        val failure = assertFailsWith<IllegalStateException> { super.samplesMeetTheEvidenceFloor() }
        assertTrue(
            "2 distinct by `==`" in failure.message.orEmpty(),
            "the failure must report what it counted, not merely that it refused: ${failure.message}",
        )
    }
}

/**
 * A binding rigged **at** the degenerate end: three samples, one value. Sibling to
 * [TwoDistinctSamplesRigTest]; see it for why these are discovered test classes.
 *
 * This is the half a count-only floor would miss. `listOf(x, x, x)` satisfies "at least three
 * samples" and satisfies none of what that sentence was for — which is why the floor counts
 * *distinct* values, and why this rig exists beside one that is merely short.
 */
internal class ThreeIdenticalSamplesRigTest : QuiltedConformanceSuite<IntMax>() {

    override fun samples(): List<IntMax> = listOf(IntMax(2), IntMax(2), IntMax(2))

    /** Inverted, as in [TwoDistinctSamplesRigTest.samplesMeetTheEvidenceFloor]. */
    @Test
    override fun samplesMeetTheEvidenceFloor() {
        val failure = assertFailsWith<IllegalStateException> { super.samplesMeetTheEvidenceFloor() }
        assertTrue(
            "3 sample(s), 1 distinct by `==`" in failure.message.orEmpty(),
            "the count and the distinct count must both appear — a list that is long and thin is " +
                "the case a count-only floor waves through: ${failure.message}",
        )
    }
}

/**
 * A test of the **floor**, not of a type — sibling to
 * [us.tractat.kuilt.conformance.lattice.VacuityFloorSelfTest] and
 * [us.tractat.kuilt.conformance.lattice.CodecLawSelfTest], and the standing receipt for
 * [checkSampleEvidenceFloor].
 *
 * The two rig classes above pin that the suite's `@Test` consults the floor. This one pins the other
 * three things a receipt needs:
 *
 * 1. **The floor discriminates** — a live binding clears it. An arm that reds on every list is not a
 *    detector, it is a floor set above everything.
 * 2. **The hole it closes is real** — the four pre-existing laws are green on both rig fixtures, and
 *    that is asserted here by calling the actual law methods rather than described in a comment. A
 *    future change that makes one of them notice thin evidence reds this test, which is the right
 *    outcome: the receipt is then out of date and wants rewriting, not deleting.
 * 3. **`==` is the relation, not `hashCode`** — the one input at which the obvious `toSet()`
 *    spelling of this floor is false while the shipped spelling is true.
 */
internal class SampleEvidenceFloorSelfTest {

    /**
     * Control: a live binding clears the floor.
     *
     * Taken from [IntMaxConformanceTest] rather than restated, for the reason
     * [us.tractat.kuilt.conformance.lattice.VacuityFloorSelfTest] takes its control arm live — a
     * copied fixture is one that silently stops being the binding it represents. Every other live
     * binding is the same control at scale; this one just says so in a place a reader looks.
     */
    @Test
    fun theLiveBindingClearsTheFloor() {
        val report = checkSampleEvidenceFloor(IntMaxConformanceTest().samples())
        println("control — IntMaxConformanceTest taken live\n$report")
        assertTrue(
            report.distinctSamples >= DISTINCT_SAMPLE_FLOOR,
            "the control must CLEAR the floor or the rigs below prove nothing about discrimination: $report",
        )
    }

    /**
     * **The measurement of the hole.** Both rig fixtures pass every law the suite had before the
     * floor existed — called directly, so this is the suite's own verdict rather than a model of it.
     *
     * `pieceIsAssociative` over `listOf(x, x, x)` evaluates 27 triples, all of them `x ⊔ x ⊔ x`
     * against itself; `pieceIsCommutative` and `pieceIsLeastUpperBound` evaluate 9 pairs each, all
     * `x ⊔ x`. Green on any type whatsoever, including one whose `piece` is not a join at all. That
     * is what "documented but unenforced" bought (#2312).
     */
    @Test
    fun theFourLawsAreGreenOnBothRefusedFixtures() {
        val short = TwoDistinctSamplesRigTest()
        val thin = ThreeIdenticalSamplesRigTest()
        assertAll(
            { short.pieceIsIdempotent() },
            { short.pieceIsCommutative() },
            { short.pieceIsAssociative() },
            { short.pieceIsLeastUpperBound() },
            { short.samplesReAssertAfterRetirement() },
            { thin.pieceIsIdempotent() },
            { thin.pieceIsCommutative() },
            { thin.pieceIsAssociative() },
            { thin.pieceIsLeastUpperBound() },
            { thin.samplesReAssertAfterRetirement() },
        )
    }

    /**
     * The degenerate end of the short arm — one sample, which the discovered rig deliberately does
     * not use because it cannot distinguish a floor of 3 from a floor of 2.
     */
    @Test
    fun aSingleSampleRedsTheFloorToo() {
        val failure = assertFailsWith<IllegalStateException> {
            checkSampleEvidenceFloor(listOf(IntMax(9)))
        }
        assertTrue(
            "1 sample(s), 1 distinct" in failure.message.orEmpty(),
            "the failure must report both counts: ${failure.message}",
        )
    }

    /**
     * The floor is exactly [DISTINCT_SAMPLE_FLOOR] — three distinct values clear it, and three of
     * which two are equal do not.
     *
     * Without this pair the floor could be any number at or below 3 and every other arm here would
     * read the same. It is also the arm that answers *is there any input at which this assertion is
     * false?* in both directions: the first list falsifies "the floor always raises", the second
     * falsifies "the floor never raises". An assertion with only one of those is decoration.
     */
    @Test
    fun theFloorSitsExactlyAtThreeDistinct() {
        val cleared = checkSampleEvidenceFloor(listOf(IntMax(1), IntMax(2), IntMax(3)))
        assertAll(
            { assertTrue(cleared.distinctSamples == DISTINCT_SAMPLE_FLOOR, "expected 3 distinct: $cleared") },
            {
                assertFailsWith<IllegalStateException>(
                    "three entries with a repeat among them are two distinct values, not three",
                ) { checkSampleEvidenceFloor(listOf(IntMax(1), IntMax(2), IntMax(1))) }
            },
        )
    }

    /**
     * **The input on which `samples().toSet().size` — the obvious spelling, and the one #2312
     * proposed — does not agree with `==`, and does not even agree with itself across targets.**
     *
     * `hashCode` is not part of the [Quilted] contract. [EqualButUnhashed] overrides equality and
     * leaves `hashCode` at identity, which breaks the `equals`/`hashCode` contract every hash
     * container relies on — so what a `Set` makes of three equal instances is **unspecified**, and
     * observably so: three of them read as *three* distinct on most runs and as *two* on a `wasmJs`
     * run where two identity hashes shared a bucket and `equals` collapsed them there. A later
     * `wasmJs` run read three again. **The number is not stable across targets and not stable across
     * runs of one target**, which is the whole argument — a floor whose verdict depends on
     * identity-hash allocation is not a floor. The type is not a contrivance either: nothing in
     * `Quilted` asks for `hashCode`, so a binding author who writes none has broken no rule, and
     * under a `toSet()` floor would have got a non-reproducible evidence count for their trouble.
     *
     * **So the assertion is on `==`, and the `Set` number is printed rather than pinned** — no
     * number for it appears in an assertion or, deliberately, anywhere in this KDoc as a claim about
     * what a given target *will* read. An earlier draft asserted `toSet().size == 3` and reddened
     * the `wasmJs` run above; the rig's own precondition was the unspecified thing, which is the
     * finding rather than a flake. What is deterministic on every target and every run is that
     * [checkSampleEvidenceFloor] consults no hash and reads **one**.
     *
     * That is enough to red a `toSet()` mutation on any run where the two spellings disagree: at a
     * `Set` count of 3 the floor stops raising at all and [assertFailsWith] reds; at 2 it raises
     * with the wrong count and the message check reds. **What it cannot detect** is a run whose
     * `Set` happens to collapse all three into one bucket — there the two spellings agree on this
     * input and nothing over it could separate them. So this arm's red is *probabilistic in the
     * mutant*, which is worth knowing and is not a reason to weaken it: the shipped code is
     * deterministic, and it is only the `toSet()` counterfactual whose detection is not.
     */
    @Test
    fun equalValuesWithoutAHashCodeStillCollapse() {
        val three = listOf(EqualButUnhashed(7), EqualButUnhashed(7), EqualButUnhashed(7))
        println("`toSet()` on three equal instances with no hashCode reads ${three.toSet().size} on this target")
        assertAll(
            {
                assertTrue(
                    three.all { it == three[0] },
                    "the rig is only meaningful while these really are one value by `==`: $three",
                )
            },
            {
                val failure = assertFailsWith<IllegalStateException> { checkSampleEvidenceFloor(three) }
                assertTrue(
                    "3 sample(s), 1 distinct" in failure.message.orEmpty(),
                    "`==` must see one value here on every target, whatever the Set saw: ${failure.message}",
                )
            },
        )
    }
}

/**
 * A join-semilattice with equality and **no** `hashCode` — the shape
 * [SampleEvidenceFloorSelfTest.equalValuesWithoutAHashCodeStillCollapse] needs and the [Quilted]
 * contract permits.
 *
 * Deliberately not a `data class`: the compiler would generate the `hashCode` whose absence is the
 * entire point. `EqualsWithHashCodeExist` is suppressed for the same reason — the rule is right
 * about production types and this one exists to *be* the type the rule warns about, so that
 * [checkSampleEvidenceFloor] can be shown not to depend on the hash. Do not "fix" it by adding a
 * `hashCode`; that silently deletes the arm.
 */
@Suppress("EqualsWithHashCodeExist")
internal class EqualButUnhashed(val value: Int) : Quilted<EqualButUnhashed> {
    override fun piece(other: EqualButUnhashed): EqualButUnhashed =
        if (other.value > value) other else this

    override fun equals(other: Any?): Boolean = other is EqualButUnhashed && other.value == value

    override fun toString(): String = "EqualButUnhashed($value)"
}
