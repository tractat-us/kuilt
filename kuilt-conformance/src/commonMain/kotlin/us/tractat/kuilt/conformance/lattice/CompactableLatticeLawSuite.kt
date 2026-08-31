package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test

/**
 * [LatticeLawSuite] for a type that garbage-collects — bind here instead, and the two compaction
 * phases of [LatticeLawHarness.run] become load-bearing (#2019).
 *
 * ## What this exists for
 *
 * Until this suite, no convergence generator in the repo ever called `compact()`. Measured on
 * `main`: reverting each of the three mechanisms that make a compaction record encode canonically —
 * `CanonicalMapSerializer` on `Compact.positions` (#1978), `CanonicalSetSerializer` on
 * `MovableTree.compactedDots` (#1957), and `compareCompactPositions` (#713) — left
 * `RgaConvergenceTest`, `FugueConvergenceTest` and `MovableTreeConvergenceTest` **green** in every
 * case, while the dedicated hand-written tests in `:kuilt-crdt` reddened. The generator was
 * structurally blind, so each of those three defects had to be caught by a test somebody thought to
 * write. The cost that removes is the *next* compactable type, which starts with zero generator
 * coverage and would need its own hand-written test for each field its compaction mints.
 *
 * ## Binding
 *
 * Pass a [CrdtCompactor] to the harness and declare [compactionFloors]:
 *
 * ```kotlin
 * internal class RgaConvergenceTest : CompactableLatticeLawSuite<Rga<String>>() {
 *     override fun newHarness() = LatticeLawHarness(
 *         // …
 *         compactor = { state, stableCut, frontierMax, delivered ->
 *             state.compact(stableCut, frontierMax, delivered)
 *                 ?.let { (compacted, op) -> CompactionStep(compacted, op.positions.size) }
 *         },
 *     )
 *     override val compactionFloors = CompactionFloors(24, 6, 24)
 * }
 * ```
 *
 * The floors are **per binding** and their measured values belong beside them — see
 * [CompactionFloors].
 */
public abstract class CompactableLatticeLawSuite<S : Quilted<S>> : LatticeLawSuite<S>() {

    /** Floors on what the compaction phases must reach for their assertions to mean anything. */
    public abstract val compactionFloors: CompactionFloors

    /**
     * The compaction phases actually **reached** compaction, and reached it big enough to
     * discriminate — see [CompactionCoverage] for what each count guards and why.
     *
     * **This is a test of the evidence, not of the type.** Every assertion the two compaction
     * phases make holds vacuously over a run in which nothing compacted; a phase that never fires
     * is green, and so is one that only ever drops a single id, because a one-element collection
     * has exactly one iteration order and no ordering defect is expressible in it. Both are
     * indistinguishable from a working phase by the phases' own assertions. So the reach is
     * counted, not inferred from a side effect: "the state changed" stays true when the mechanism
     * under test is reintroduced, and a count does not.
     *
     * Seeds `0..31` — the same window [convergesAcrossSeeds] runs, so the counts describe the runs
     * that phase 0 and the compaction phases actually asserted over.
     *
     * The measured rates print on every run, green or red, because a floor nobody sees is a floor
     * nobody notices drifting toward.
     */
    @Test
    public fun compactionIsReachedAndBigEnoughToDiscriminate() {
        val harness = newHarness()
        harness.runSeeds(0L..31L)
        val coverage = harness.compactionCoverage
        val floors = compactionFloors
        check(coverage.runs > 0) {
            "No run bound a compactor — ${this::class.simpleName} extends " +
                "CompactableLatticeLawSuite but its harness was built with `compactor = null`, so " +
                "both compaction phases were skipped and every assertion they make is absent " +
                "rather than green. Pass a `CrdtCompactor` to `LatticeLawHarness`."
        }
        check(coverage.postMergeRunsWithCompaction >= floors.postMergeRunsWithCompaction) {
            floorFailure(
                "post-merge runs that compacted",
                coverage.postMergeRunsWithCompaction,
                floors.postMergeRunsWithCompaction,
                coverage,
                "The post-merge phase is asserting over states nothing was ever dropped from, so " +
                    "it holds for any compaction implementation whatsoever — including none. " +
                    "Either the generator stopped producing tombstones the stable cut covers, or " +
                    "the cut stopped reaching them: `VersionVector.contiguous` stops at the first " +
                    "gap, so a generator that leaves holes in an author's seq run silently buys " +
                    "less compaction rather than failing.",
            )
        }
        check(coverage.postMergeMaxDroppedInOneStep >= floors.postMergeMaxDroppedInOneStep) {
            floorFailure(
                "post-merge max ids dropped in one step",
                coverage.postMergeMaxDroppedInOneStep,
                floors.postMergeMaxDroppedInOneStep,
                coverage,
                "Compaction fires, but never on enough ids at once for an ordering defect to be " +
                    "expressible. A `Compact` carrying one id has exactly one key order and a " +
                    "one-element dropped-dot set has exactly one element order, so the phase's " +
                    "byte assertion cannot discriminate at that size — this is the metric a floor " +
                    "phrased as 'compaction happened' misses.",
            )
        }
        check(
            coverage.preMergeRunsWithTwoOrMoreCompacting >= floors.preMergeRunsWithTwoOrMoreCompacting,
        ) {
            floorFailure(
                "pre-merge runs with >=2 replicas compacting",
                coverage.preMergeRunsWithTwoOrMoreCompacting,
                floors.preMergeRunsWithTwoOrMoreCompacting,
                coverage,
                "The pre-merge phase exists to vary the merge of *already-compacted* states, and " +
                    "with fewer than two compacted operands there is nothing to merge. It is the " +
                    "only phase that pins a compaction record whose own order is already canonical " +
                    "at mint time — `MovableTree.compactedDots` is that case, and the post-merge " +
                    "phase is blind to it on every seed.",
            )
        }
        println("${this::class.simpleName} — compaction coverage over seeds 0..31\n$coverage")
    }

    private fun floorFailure(
        name: String,
        measured: Int,
        floor: Int,
        coverage: CompactionCoverage,
        why: String,
    ): String =
        "Compaction coverage floor breached — $name measured $measured, required at least $floor.\n" +
            "  $why\n" +
            "  Do not lower the floor to make this green: the floor is what tells a reader the " +
            "compaction phases are still searching. Fix the generator, or widen `opsPerReplica`.\n" +
            coverage
}
