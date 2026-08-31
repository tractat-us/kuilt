package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A test of the **compaction rig**, not of a type — the sibling of [VacuityFloorSelfTest].
 *
 * The two compaction phases of [LatticeLawHarness.run] assert nothing a run in which nothing
 * compacted would notice. That is not a hypothetical: it is #2019 itself, where three
 * canonicalisation mechanisms could each be reverted while every convergence suite stayed green,
 * because none of them ever called `compact()`. So the reach is *counted*, and
 * [CompactableLatticeLawSuite.compactionIsReachedAndBigEnoughToDiscriminate] asserts floors on the
 * counts.
 *
 * Which raises the same question one level up — **does the counting itself have teeth?** These arms
 * answer it by breaking the rig in the two ways it can silently go quiet, and confirming each is
 * named rather than tolerated.
 */
internal class CompactionCoverageSelfTest {

    /** A compactor that always declines — the shape of a hook whose cut never authorises a drop. */
    private fun inertHarness(): LatticeLawHarness<Rga<String>> = harnessWith { _, _, _, _ -> null }

    private fun harnessWith(compactor: CrdtCompactor<Rga<String>>?): LatticeLawHarness<Rga<String>> {
        val live = RgaConvergenceTest().newHarness()
        return LatticeLawHarness(
            initial = live.initial,
            alphabet = live.alphabet,
            serializer = live.serializer,
            criticalShapes = live.criticalShapes,
            floors = live.floors,
            replicaCount = live.replicaCount,
            opsPerReplica = live.opsPerReplica,
            compactor = compactor,
        )
    }

    /**
     * A binding that reaches nothing leaves **both phases green** and is caught only by the counts.
     *
     * This is the receipt for the whole design. The inert compactor is a faithful model of the
     * pre-#2019 state — no compaction at any point — and every assertion the phases make holds over
     * it, because two states that were never compacted trivially compact to the same thing. The
     * measured value is `0`, and the only surface that says so is the coverage floor.
     */
    @Test
    fun aCompactorThatNeverFiresLeavesThePhasesGreenAndOnlyTheCountsRed() {
        val inert = inertHarness()
        inert.runSeeds(0L..31L)
        val live = RgaConvergenceTest().newHarness()
        live.runSeeds(0L..31L)
        assertAll(
            { assertEquals(32, inert.compactionCoverage.runs, "the phases ran") },
            { assertEquals(0, inert.compactionCoverage.postMergeRunsWithCompaction, "and reached nothing") },
            { assertEquals(0, inert.compactionCoverage.postMergeMaxDroppedInOneStep) },
            { assertEquals(0, inert.compactionCoverage.preMergeRunsWithTwoOrMoreCompacting) },
            // The control: the same generator with the real compactor does reach it, so the zeros
            // above are the compactor's doing and not a property of this type's trajectories.
            { assertTrue(live.compactionCoverage.postMergeRunsWithCompaction > 0, "control reached compaction") },
        )
    }

    /**
     * A floor at 1 is decoration, and [CompactionFloors] refuses to be constructed with one.
     *
     * A compaction that drops a single id has exactly one key order, so no iteration-order defect
     * is expressible in it and the phases' byte assertion cannot discriminate. A binding that pinned
     * its floor there would read as covered while proving nothing — which is the failure this whole
     * change exists to remove, arriving through the floor instead of through the generator.
     */
    @Test
    fun aDiscriminationFloorBelowTwoIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CompactionFloors(
                postMergeRunsWithCompaction = 24,
                postMergeMaxDroppedInOneStep = 1,
                preMergeRunsWithTwoOrMoreCompacting = 24,
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("at least ${CompactionFloors.MIN_DISCRIMINATING_STEP}"),
            "the message must name the bound, got: ${failure.message}",
        )
    }

    /**
     * The pre-merge phase's disjoint-history premise is asserted, not assumed.
     *
     * A replica compacting alone uses its own delivered vector as the stable cut, which is sound
     * only while its history is single-author. A generator that gave two replicas one `ReplicaId`
     * would break that quietly and the resulting convergence failure would be the generator's fault
     * — so the harness names it instead.
     */
    @Test
    fun replicasSharingAnAuthorIdAreNamedRatherThanSilentlyUnsound() {
        val live = RgaConvergenceTest().newHarness()
        val sharedAuthor = LatticeLawHarness(
            initial = Rga.empty<String>(),
            // Every replica writes as R0 regardless of its index — the premise breaker.
            alphabet = listOf(
                LatticeOp("insert-head-as-R0", OpKind.ASSERT) { state, _, random ->
                    state.insertAt(ReplicaId("R0"), 0, "v${random.nextInt(100)}").first
                },
            ),
            serializer = Rga.wireSerializer(String.serializer()),
            criticalShapes = emptyList(),
            floors = live.floors,
            replicaCount = 3,
            opsPerReplica = 4,
            compactor = { state, stableCut, frontierMax, delivered ->
                state.compact(stableCut, frontierMax, delivered)
                    ?.let { (compacted, op) -> CompactionStep(compacted, op.positions.size) }
            },
        )
        val failure = assertFailsWith<IllegalStateException> { sharedAuthor.run(seed = 0L) }
        assertTrue(
            failure.message.orEmpty().contains("not disjoint"),
            "the message must name the premise, got: ${failure.message}",
        )
    }
}
