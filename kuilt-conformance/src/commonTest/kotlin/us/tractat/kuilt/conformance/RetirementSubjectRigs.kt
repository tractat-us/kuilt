package us.tractat.kuilt.conformance

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A binding whose [RetirementReAssertion.shows] is written against its **sibling samples** instead
 * of about a subject — `{ it != retired }`, the predicate #2184 names — bound to a lattice that
 * cannot retire at all.
 *
 * It is a real, discovered test class on purpose, and the fact that it is **green** is the whole
 * receipt. [QuiltedConformanceSuite.samplesReAssertAfterRetirement] runs against this fixture for
 * real and passes every arm, including the freshly-joined identity check — so the guard's power
 * stops at "the predicate reads the value", and does not reach "the predicate is about anything".
 * That limit is now pinned rather than carried in someone's head; see [RetirementSubjectSelfTest],
 * which asserts the pass rather than leaving it inferable from a green run.
 *
 * **`IntMax`, deliberately, and not because it was convenient.** A rig built on a genuinely retiring
 * type would leave open the reading that the predicate is *nearly* right — that it happens to track
 * a real retirement by accident. `IntMax` is a max-wins integer: it has no removal, no tombstone and
 * no supersession, three ascending values are three plain additions, and `{ it != retired }` still
 * reads true → false → true across them. The predicate therefore carries **zero** information about
 * retirement, which is the strongest form of the point and the reason this fixture is not a binding
 * of `IntMax` — [IntMaxConformanceTest] is that, and it correctly leaves
 * [QuiltedConformanceSuite.retirementIsMeaningful] `false`.
 *
 * **This pins the hole; it does not close it.** Closing it structurally would mean inspecting a
 * lambda, which the suite cannot do. What closes it in practice is the KDoc on
 * [RetirementReAssertion.shows] plus the mandatory [RetirementReAssertion.subject] beside it — and
 * [RetirementSubjectSelfTest.aBlankSubjectPassesToo] measures how much the second of those is worth.
 */
internal class SiblingReferencingShowsRigTest : QuiltedConformanceSuite<IntMax>() {

    override fun samples(): List<IntMax> = listOf(ASSERTED, RETIRED, RE_ASSERTED)

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<IntMax> = RetirementReAssertion(
        // Free text: no assertion in the suite reads it, which is the point of
        // `RetirementSubjectSelfTest.aBlankSubjectPassesToo`.
        subject = "nothing — this predicate is about the sibling samples",
        asserted = ASSERTED,
        retired = RETIRED,
        reAsserted = RE_ASSERTED,
        shows = { it != RETIRED },
    )

    internal companion object {
        /** Below [RETIRED], so the suite's `asserted ⊑ retired` ordering arm holds. */
        val ASSERTED: IntMax = IntMax(1)

        /** The value the predicate is written against — and the only thing it is about. */
        val RETIRED: IntMax = IntMax(2)

        /** Above [RETIRED], so the `retired ⊑ reAsserted` arm holds. */
        val RE_ASSERTED: IntMax = IntMax(3)
    }
}

/**
 * A test of the **guard**, not of a type — sibling to
 * [us.tractat.kuilt.conformance.lattice.VacuityFloorSelfTest] and
 * [us.tractat.kuilt.conformance.lattice.CodecLawSelfTest], and the standing receipt for
 * [QuiltedConformanceSuite.samplesReAssertAfterRetirement]'s subject arms (#2184).
 *
 * Three things a receipt needs, in the order they have to be established:
 *
 * 1. **The guard discriminates.** Three rigs red it — one per subject arm the guard has — each
 *    identified **by the words in the failure**, because a rig that reds the pass is half a
 *    receipt and a rig that reds the arm it was built for is the whole one. An arm that reds on
 *    nothing is not a detector.
 * 2. **The hole is real.** A predicate written against the sibling samples passes every arm, on a
 *    lattice with no retirement in it at all.
 * 3. **What the hole rests on.** [RetirementReAssertion.subject] is the field that makes such a
 *    predicate conspicuous to a reviewer, and it is unconstrained — a blank one passes too.
 *
 * The rigs below are **anonymous objects** rather than top-level classes, deliberately: a concrete
 * [QuiltedConformanceSuite] subclass is a discovered test class on every target (which is exactly
 * what [SiblingReferencingShowsRigTest] relies on), so a rig that must *fail* cannot be one —
 * `samplesReAssertAfterRetirement` is not `open`, so it could not be inverted the way
 * [TwoDistinctSamplesRigTest] inverts the evidence floor.
 */
internal class RetirementSubjectSelfTest {

    /**
     * **The hole, pinned.** The sibling-referencing predicate passes, and this asserts it rather
     * than leaving a reader to infer it from a suite that happens to be green.
     *
     * Calls the suite's own `@Test` method, so a future change that *does* close this hole reds
     * here — which is the right outcome. This receipt is then out of date and wants rewriting
     * (and #2184 closing), not deleting.
     */
    @Test
    fun aPredicateWrittenAgainstTheSiblingSamplesPassesEveryArm() {
        SiblingReferencingShowsRigTest().samplesReAssertAfterRetirement()
    }

    /**
     * **Control, direction one.** A predicate that never goes false reds the retiring arm — the one
     * assertion that separates a retirement from three ascending additions.
     */
    @Test
    fun aPredicateThatNeverGoesFalseRedsTheGuard() {
        val failure = assertFailsWith<AssertionError> { rigShowing { true }.samplesReAssertAfterRetirement() }
        assertTrue(
            "must NOT show the subject" in failure.message.orEmpty(),
            "the retiring arm must be the arm that raises, not merely some arm: ${failure.message}",
        )
    }

    /**
     * **Control, direction two.** A predicate that never goes true reds the asserting arm first.
     *
     * Both directions, because a guard that only caught one would still pass a predicate that is
     * constantly wrong the other way, and "it reddened" on its own does not say which arm did.
     */
    @Test
    fun aPredicateThatNeverGoesTrueRedsTheGuard() {
        val failure = assertFailsWith<AssertionError> { rigShowing { false }.samplesReAssertAfterRetirement() }
        assertTrue(
            "must show the subject it asserts" in failure.message.orEmpty(),
            "the asserting arm must be the arm that raises: ${failure.message}",
        )
    }

    /**
     * **Control, the third arm.** A predicate that reads the *instance* rather than the value reds
     * the freshly-joined arm — and only that arm.
     *
     * `{ it !== RETIRED }` is true for the asserting state, false for the retiring one and true for
     * the re-asserting one, so it clears the first three arms exactly as a correct predicate would.
     * It parts company on `retired.piece(asserted)`, which is a *new* `IntMax(2)`: equal to
     * [SiblingReferencingShowsRigTest.RETIRED] and not identical to it, so an identity-reading
     * predicate says the subject came back. That is the defect this arm exists for, and it is the
     * one shape a value-reading predicate cannot exhibit.
     */
    @Test
    fun aPredicateThatReadsTheInstanceRedsTheFreshlyJoinedArm() {
        val failure = assertFailsWith<AssertionError> {
            rigShowing { it !== SiblingReferencingShowsRigTest.RETIRED }.samplesReAssertAfterRetirement()
        }
        assertTrue(
            "must not resurrect the subject" in failure.message.orEmpty(),
            "the freshly-joined arm must be the arm that raises: ${failure.message}",
        )
    }

    /**
     * **What the hole rests on.** [RetirementReAssertion.subject] carries no constraint — a blank
     * one passes every arm — so its whole value is that a reviewer reads it beside the predicate.
     *
     * Worth asserting rather than assuming, because #2184's reason for calling the hole
     * unreachable-by-accident is precisely that the mandatory `subject` field makes a
     * sibling-referencing predicate conspicuous in review. That reason is a claim about a *human*
     * reading a field the machine never looks at, and this arm is what says so out loud.
     */
    @Test
    fun aBlankSubjectPassesToo() {
        rigShowing(subject = "") { it != SiblingReferencingShowsRigTest.RETIRED }
            .samplesReAssertAfterRetirement()
    }

    /**
     * A binding over [SiblingReferencingShowsRigTest]'s three samples with an arbitrary [shows].
     *
     * Anonymous, so the framework never discovers it — see this class's KDoc for why that matters.
     */
    private fun rigShowing(
        subject: String = "the subject under test",
        shows: (IntMax) -> Boolean,
    ): QuiltedConformanceSuite<IntMax> = object : QuiltedConformanceSuite<IntMax>() {
        override fun samples(): List<IntMax> = listOf(
            SiblingReferencingShowsRigTest.ASSERTED,
            SiblingReferencingShowsRigTest.RETIRED,
            SiblingReferencingShowsRigTest.RE_ASSERTED,
        )

        override val retirementIsMeaningful: Boolean get() = true

        override fun retirementReAssertion(): RetirementReAssertion<IntMax> = RetirementReAssertion(
            subject = subject,
            asserted = SiblingReferencingShowsRigTest.ASSERTED,
            retired = SiblingReferencingShowsRigTest.RETIRED,
            reAsserted = SiblingReferencingShowsRigTest.RE_ASSERTED,
            shows = shows,
        )
    }
}
