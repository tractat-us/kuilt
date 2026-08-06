package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

/** TwoPhaseSet is the product of two grow-only sets — it obeys every law. */
internal class TwoPhaseSetConformanceTest : QuiltedConformanceSuite<TwoPhaseSet<String>>() {
    override fun samples(): List<TwoPhaseSet<String>> {
        val base = TwoPhaseSet.empty<String>()
        val a = base.piece(base.add("a"))
        val ab = a.piece(a.add("b"))
        val abMinusA = ab.piece(ab.remove("a"))
        return listOf(base, a, ab, abMinusA, base.piece(base.add("c")))
    }

    /**
     * **Left `false` deliberately, on a type whose lattice binding does declare a `RETIRE` op**
     * (`TwoPhaseSetConvergenceTest`'s `remove`) — the one case in #2167's six where the shape cannot
     * be honoured.
     *
     * `remove` retires under [us.tractat.kuilt.conformance.lattice.OpKind]: `abMinusA` above stops
     * showing "a" and puts nothing in its place. What is missing is the **third** state.
     * [TwoPhaseSet.contains] is `element in added && element !in removed`, both components are
     * grow-only, and so the tombstone is permanent by construction — *"once removed, an element can
     * never be re-added; even a fresh add will be masked."* That is the type's defining wart, not an
     * omission in this sample list, and the ORSet one rung up exists precisely to fix it.
     *
     * **Measured, not assumed.** A probe binding declaring `true` with the most honest triple
     * available — `a`, `abMinusA`, and `abMinusA.piece(abMinusA.add("a"))` — does not reach
     * [RetirementReAssertion.shows] at all. It reds one assertion earlier, on *"the three states
     * must be distinct": `expected <3> but was <2>`*. Re-adding after the tombstone is not merely
     * invisible, it is a **no-op on the state**: the add contributes `"a"` to a grow-only `added`
     * that already contains it, so the join lands back on `abMinusA` itself.
     *
     * A predicate on some *other* subject — `elements.isNotEmpty()`, say, retired by removing "a"
     * and re-asserted by adding "c" — would go true → false → true and pass. It would also be the
     * substitution [samplesReAssertAfterRetirement] exists to catch (#2157), one level up: not a
     * different sample under the same subject, but a different subject chosen to fit the samples.
     * So this stays `false`, and the reason is recorded here rather than left to the default.
     */
    override val retirementIsMeaningful: Boolean get() = false
}
