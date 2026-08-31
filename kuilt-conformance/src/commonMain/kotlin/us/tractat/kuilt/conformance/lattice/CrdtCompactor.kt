package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.VersionVector

/** One compaction step: the resulting [state] and how many ids it dropped. */
public class CompactionStep<S>(public val state: S, public val droppedCount: Int)

/**
 * Invokes **one** compaction of [state] at a harness-derived cut, or returns `null` when nothing
 * qualifies. The whole body of a real one is a call to that type's own `compact(…)` plus unwrapping
 * the returned pair.
 *
 * **It sees the state and the cut and nothing else** — never the seed, the permutation, the replica
 * index, or its sibling replicas. Three consequences, all of them the point:
 *
 * 1. It **cannot tailor a cut per permutation** and so manufacture the agreement the phases assert.
 *    The cut is derived by [LatticeLawHarness] from the state alone, as
 *    [VersionVector.Companion.contiguous] over `causalDots()`/`causalFloor()` — which is literally
 *    what `Quilter.recomputeCut` publishes for a fully converged room and for a solo peer, so the
 *    cut the phases compact at is one a real deployment reaches rather than a convenient fiction.
 * 2. It is a **pure function of state**, so two states that compare equal compact to states that
 *    compare equal. That is what leaves the equality assertion holding while the *byte* assertion
 *    stays free to discriminate — which is the whole mechanism here, since the defects this
 *    reaches for are ones equality is structurally blind to.
 * 3. It **cannot mutate the phase-0 inputs**, because it is handed values and every CRDT here is
 *    immutable.
 *
 * There is nowhere in that signature for a per-type fudge to live, which is why the cut is not a
 * parameter a binding supplies (#2019).
 */
public fun interface CrdtCompactor<S> {
    /** One compaction of [state] at `(stableCut, frontierMax, delivered)`, or `null`. */
    public fun compactOnce(
        state: S,
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): CompactionStep<S>?
}

/**
 * What the compaction phases actually **reached**, accumulated across one harness's [run] calls —
 * the rig receipt for [CompactableLatticeLawSuite.compactionIsReachedAndBigEnoughToDiscriminate].
 *
 * A compaction phase that never compacts is green, and so is one that only ever drops a single id.
 * Both are indistinguishable from a working phase by every assertion the phase makes, which is
 * exactly how #2019 arrived: the convergence suites were green on all three canonicalisation
 * mechanisms reverted, because they never called `compact()` at all. So the phases count what they
 * reached and the suite asserts floors on the counts.
 *
 * @param runs [LatticeLawHarness.run] calls made with a compactor bound.
 * @param postMergeRunsWithCompaction runs in which the **post-merge** phase performed at least one
 *   state-changing compaction. Guards V1 — "the hook never fires".
 * @param postMergeMaxDroppedInOneStep the largest single compaction step seen, in ids. Guards V2 —
 *   "it fires, but too small to discriminate": a `Compact` carrying **one** id has exactly one key
 *   order and a one-element dropped-dot set has exactly one element order, so no ordering defect is
 *   detectable at size 1. This is the metric that a floor phrased as "compaction happened" misses.
 * @param preMergeRunsWithTwoOrMoreCompacting runs in which **two or more replicas** each compacted
 *   alone before the merge. One is not enough: the pre-merge phase exists to vary the *merge of
 *   already-compacted states*, and with a single compacted operand there is nothing to merge it
 *   with. This is the count that pins `MovableTree.compactedDots`, which the post-merge phase
 *   cannot see at all (see [LatticeLawHarness.run]).
 */
public class CompactionCoverage(
    public val runs: Int = 0,
    public val postMergeRunsWithCompaction: Int = 0,
    public val postMergeMaxDroppedInOneStep: Int = 0,
    public val preMergeRunsWithTwoOrMoreCompacting: Int = 0,
) {
    override fun toString(): String =
        "  runs                                $runs\n" +
            "  post-merge runs that compacted      $postMergeRunsWithCompaction\n" +
            "  post-merge max ids in one step      $postMergeMaxDroppedInOneStep\n" +
            "  pre-merge runs with >=2 compacting  $preMergeRunsWithTwoOrMoreCompacting"
}

/**
 * Per-binding floors on [CompactionCoverage]. **Per binding, never shared**: the distributions
 * differ by type, and a floor low enough for the thinnest binding stops separating a healthy
 * generator from a broken one for every other (#2019).
 *
 * Pin each at roughly three-quarters of the measured value and record the measurement beside it, in
 * the binding. An exact pin turns every future generator tweak into a mechanical number-bump, which
 * is how a coverage floor rots; a floor with stated headroom lets a reviewer see that a run at 17
 * against a floor of 16 and a measurement of 25 is a regression even though it passes.
 *
 * @param postMergeRunsWithCompaction minimum runs whose post-merge phase compacted something.
 * @param postMergeMaxDroppedInOneStep minimum size of the largest single compaction step. **Never
 *   below 2** — see [CompactionCoverage.postMergeMaxDroppedInOneStep] — which
 *   [CompactableLatticeLawSuite] enforces rather than trusts.
 * @param preMergeRunsWithTwoOrMoreCompacting minimum runs in which two or more replicas each
 *   compacted before the merge.
 */
public class CompactionFloors(
    public val postMergeRunsWithCompaction: Int,
    public val postMergeMaxDroppedInOneStep: Int,
    public val preMergeRunsWithTwoOrMoreCompacting: Int,
) {
    init {
        require(postMergeMaxDroppedInOneStep >= MIN_DISCRIMINATING_STEP) {
            "postMergeMaxDroppedInOneStep floor must be at least $MIN_DISCRIMINATING_STEP — a " +
                "compaction that drops one id has exactly one key order, so no ordering defect is " +
                "detectable at that size and the floor would be decoration. Got " +
                "$postMergeMaxDroppedInOneStep."
        }
    }

    internal companion object {
        /** The smallest step at which an iteration-order defect is even expressible. */
        const val MIN_DISCRIMINATING_STEP = 2
    }
}
