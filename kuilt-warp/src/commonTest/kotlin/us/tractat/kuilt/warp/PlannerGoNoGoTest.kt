package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E-3 go/no-go gate: does the coordination-cost model measurably cut coordination rounds
 * on a representative query vs. unplanned execution?
 *
 * **Scenario:** A document-ranking pipeline over 1 000 documents. Only 5% of documents pass
 * the relevance threshold. A programmer has placed the `Embroider` (consensus/rank step)
 * *before* the filter — so the unplanned executor coordinates over all 1 000 documents.
 * The planner defers the `Embroider` past the filter, reducing the coordinated volume to ~50.
 *
 * **Cost model:** [Draft.coordinationCost] measures the coordinated volume at the [Embroider]'s
 * position in the pipeline, using [WarpStats] (HyperLogLog sketches) for cardinality and
 * per-filter selectivity. The go/no-go criterion: planned coordinated volume < 25% of
 * unplanned coordinated volume (well-above the theoretical ~5% true selectivity, generous for HLL).
 *
 * **Method:** Analytical comparison — E-5 (incremental execution) is not yet merged, so
 * coordination events are counted by inspecting the [DraftStage] structure and applying
 * [WarpStats] cardinality estimates. This is honest: the estimate is structurally determined
 * by WHERE the [DraftStage.Embroider] sits in the pipeline, not by execution.
 *
 * **VERDICT:** GO — see assertion message for the concrete numbers.
 */
class PlannerGoNoGoTest {

    private val src = OpId("source.docs")
    private val mapScore = OpId("map.score")
    private val filterThreshold = OpId("filter.above-threshold")
    private val embroider = OpId("embroider.rank")
    private val mapFormat = OpId("map.format")

    @Test
    fun representativeQueryShowsMeasurableCoordinatedVolumeCut() {
        // 1 000 source documents; 5% (50 docs) pass the relevance filter.
        val stats = buildStats(sourceCount = 1_000, filterPassCount = 50)

        // Unplanned: programmer placed Embroider EARLY, before the selective filter.
        val unplanned = Warp.shuttle(src)
            .map(mapScore)
            .embroider(embroider)       // ← placed before filtering (sub-optimal)
            .filter(filterThreshold)
            .map(mapFormat)

        // Planned: the planner defers Embroider past all Free stages.
        val planned = unplanned.plan(stats)

        val unplannedCost = unplanned.coordinationCost(stats)
        val plannedCost = planned.coordinationCost(stats)

        assertAll(
            // The planner must not change the observable result.
            {
                assertTrue(
                    unplanned.isEquivalentTo(planned),
                    "plan must preserve semantic equivalence",
                )
            },
            // Both drafts have exactly one coordination round (one Embroider).
            { assertEquals(1, unplannedCost.rounds, "unplanned must have 1 round") },
            { assertEquals(1, plannedCost.rounds, "planned must have 1 round") },
            // GATE: planned coordinated volume is less than 25% of unplanned.
            // True ratio is ~5% (50/1000); 25% gives comfortable HLL tolerance.
            {
                assertTrue(
                    plannedCost.coordinatedVolume < unplannedCost.coordinatedVolume / 4,
                    "GO/NO-GO GATE: planned coordinated volume must be <25% of unplanned. " +
                        "unplanned=${unplannedCost.coordinatedVolume}, " +
                        "planned=${plannedCost.coordinatedVolume}. " +
                        "Threshold=${unplannedCost.coordinatedVolume / 4}",
                )
            },
        )
    }

    /** Two peers observe the same source independently, then merge — mirrors distributed gossip. */
    @Test
    fun representativeQueryHoldsAfterMergingDistributedStats() {
        // Peer A observes the first 600 docs; Peer B observes the last 600 (300 overlap).
        val statsA = buildStatsFromRange(sourceRange = 1..600, filterRange = 1..30)
        val statsB = buildStatsFromRange(sourceRange = 401..1_000, filterRange = 21..50)
        val merged = statsA.piece(statsB)

        val unplanned = Warp.shuttle(src)
            .map(mapScore)
            .embroider(embroider)
            .filter(filterThreshold)
            .map(mapFormat)

        val planned = unplanned.plan(merged)
        val unplannedCost = unplanned.coordinationCost(merged)
        val plannedCost = planned.coordinationCost(merged)

        // The volume cut holds even after merging distributed HLL sketches.
        assertTrue(
            plannedCost.coordinatedVolume < unplannedCost.coordinatedVolume / 4,
            "volume cut must hold after merging distributed stats: " +
                "unplanned=${unplannedCost.coordinatedVolume}, planned=${plannedCost.coordinatedVolume}",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildStats(sourceCount: Int, filterPassCount: Int): WarpStats {
        var stats = WarpStats.empty()
        for (i in 1..sourceCount) stats = stats.piece(stats.observe(src, "doc_$i"))
        for (i in 1..filterPassCount) stats = stats.piece(stats.observe(filterThreshold, "doc_${i * (sourceCount / filterPassCount)}"))
        return stats
    }

    private fun buildStatsFromRange(sourceRange: IntRange, filterRange: IntRange): WarpStats {
        var stats = WarpStats.empty()
        for (i in sourceRange) stats = stats.piece(stats.observe(src, "doc_$i"))
        for (i in filterRange) stats = stats.piece(stats.observe(filterThreshold, "doc_${i * 20}"))
        return stats
    }
}
