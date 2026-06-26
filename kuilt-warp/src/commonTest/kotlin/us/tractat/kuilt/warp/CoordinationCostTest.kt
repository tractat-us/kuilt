package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the E-3 coordination-cost model.
 *
 * Two dimensions are covered:
 * - [Draft.coordinationCost] — scores a [Draft] by coordination rounds and coordinated volume.
 * - [Draft.plan] — applies E-2 rewrites to minimise [coordinationCost].
 *
 * The go/no-go gate lives in [PlannerGoNoGoTest].
 */
class CoordinationCostTest {

    private val src = OpId("source.docs")
    private val mapScore = OpId("map.score")
    private val mapFormat = OpId("map.format")
    private val filterThreshold = OpId("filter.above-threshold")
    private val filterRecent = OpId("filter.recent")
    private val emb = OpId("embroider.rank")

    // ── fully-monotone drafts ─────────────────────────────────────────────────

    @Test
    fun fullyMonotoneDraftHasZeroCost() {
        val draft = Warp.shuttle(src).map(mapScore).filter(filterThreshold)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertAll(
            { assertEquals(0, cost.rounds, "fully-monotone draft must have 0 rounds") },
            { assertEquals(0L, cost.coordinatedVolume, "fully-monotone draft must have 0 volume") },
        )
    }

    @Test
    fun emptyDraftHasZeroCost() {
        val draft = Warp.shuttle(src)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertEquals(CoordinationCost(rounds = 0, coordinatedVolume = 0L), cost)
    }

    // ── coordinated rounds ────────────────────────────────────────────────────

    @Test
    fun draftWithEmbroideryHasOneRound() {
        val draft = Warp.shuttle(src).map(mapScore).embroider(emb)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertEquals(1, cost.rounds)
    }

    // ── coordinated volume — position of Embroider ────────────────────────────

    @Test
    fun embroiderImmediatelyAfterSourceUsesSourceCardinality() {
        val stats = statsFor(src, 1000)
        val draft = Warp.shuttle(src).embroider(emb).map(mapScore)
        val cost = draft.coordinationCost(stats)
        assertAll(
            { assertEquals(1, cost.rounds) },
            {
                assertTrue(
                    cost.coordinatedVolume >= 900L,
                    "volume before any filter must approximate source cardinality, got ${cost.coordinatedVolume}",
                )
            },
        )
    }

    @Test
    fun filterBeforeEmbroideryReducesCoordinatedVolume() {
        // Source: 1000 items; filter passes 50 (~5% selectivity).
        val stats = statsFor(src, 1000).piece(statsFor(filterThreshold, 50))
        val draft = Warp.shuttle(src).filter(filterThreshold).embroider(emb)
        val cost = draft.coordinationCost(stats)
        assertAll(
            { assertEquals(1, cost.rounds) },
            {
                assertTrue(
                    cost.coordinatedVolume < 100L,
                    "filter before embroider must reduce volume to ~50, got ${cost.coordinatedVolume}",
                )
            },
        )
    }

    @Test
    fun filterAfterEmbroideryDoesNotReduceCoordinatedVolume() {
        // Filter is after Embroider — it does not affect how many items reach the Embroider.
        val stats = statsFor(src, 1000).piece(statsFor(filterThreshold, 50))
        val draft = Warp.shuttle(src).embroider(emb).filter(filterThreshold)
        val cost = draft.coordinationCost(stats)
        assertTrue(
            cost.coordinatedVolume >= 900L,
            "filter after embroider must not reduce volume, got ${cost.coordinatedVolume}",
        )
    }

    @Test
    fun unknownFilterSelectivityIsConservative() {
        // stats has source cardinality but NO data for filterThreshold.
        val stats = statsFor(src, 1000)
        val draft = Warp.shuttle(src).filter(filterThreshold).embroider(emb)
        val cost = draft.coordinationCost(stats)
        assertTrue(
            cost.coordinatedVolume >= 900L,
            "unknown filter selectivity must be conservative (no volume reduction), got ${cost.coordinatedVolume}",
        )
    }

    @Test
    fun emptyStatsProducesZeroVolume() {
        // Without any observations, source cardinality is 0 → volume is 0.
        val draft = Warp.shuttle(src).embroider(emb)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertEquals(0L, cost.coordinatedVolume)
    }

    @Test
    fun multipleFreeStagesBeforeEmbroideryApplyAllSelectivities() {
        // Source: 1000; f1 passes 200; f2 passes 100.
        // Expected volume ≈ 100 (applies both selectivities independently, using source as denominator).
        // (This is a best-effort approximation for independently observed filters.)
        val stats = statsFor(src, 1000)
            .piece(statsFor(filterThreshold, 200))
            .piece(statsFor(filterRecent, 100))
        val draft = Warp.shuttle(src)
            .filter(filterThreshold)
            .filter(filterRecent)
            .embroider(emb)
        val cost = draft.coordinationCost(stats)
        // Volume = 1000 × (200/1000) × (100/1000) = 20. Even generous tolerance is <150.
        assertTrue(
            cost.coordinatedVolume < 150L,
            "chained filters must apply combined selectivity, got ${cost.coordinatedVolume}",
        )
    }

    // ── planner ───────────────────────────────────────────────────────────────

    @Test
    fun planOutputIsEquivalentToInput() {
        val draft = Warp.shuttle(src).map(mapScore).embroider(emb).filter(filterThreshold)
        val planned = draft.plan(WarpStats.empty())
        assertTrue(draft.isEquivalentTo(planned), "plan must preserve semantic equivalence")
    }

    @Test
    fun planDoesNotIncreaseCoordinationCost() {
        val stats = statsFor(src, 1000).piece(statsFor(filterThreshold, 50))
        val draft = Warp.shuttle(src).map(mapScore).embroider(emb).filter(filterThreshold)
        val unplannedCost = draft.coordinationCost(stats)
        val plannedCost = draft.plan(stats).coordinationCost(stats)
        assertTrue(
            plannedCost <= unplannedCost,
            "planned cost $plannedCost must not exceed unplanned cost $unplannedCost",
        )
    }

    @Test
    fun planIsIdempotent() {
        val draft = Warp.shuttle(src).map(mapScore).embroider(emb).filter(filterThreshold)
        val stats = WarpStats.empty()
        val once = draft.plan(stats)
        val twice = once.plan(stats)
        assertEquals(once.stages, twice.stages, "plan must be idempotent")
    }

    @Test
    fun alreadyOptimalDraftPlanIsNoOp() {
        // Source → Filter → Map → Embroider — already optimal.
        val draft = Warp.shuttle(src).filter(filterThreshold).map(mapScore).embroider(emb)
        val planned = draft.plan(WarpStats.empty())
        assertEquals(draft.stages, planned.stages, "already-optimal draft must not change")
    }

    // ── CoordinationCost ordering ─────────────────────────────────────────────

    @Test
    fun coordinationCostOrderingByRoundsFirst() {
        val free = CoordinationCost(rounds = 0, coordinatedVolume = 9999L)
        val coordinated = CoordinationCost(rounds = 1, coordinatedVolume = 1L)
        assertTrue(free < coordinated, "fewer rounds wins regardless of volume")
    }

    @Test
    fun coordinationCostOrderingByVolumeWhenRoundsEqual() {
        val small = CoordinationCost(rounds = 1, coordinatedVolume = 50L)
        val large = CoordinationCost(rounds = 1, coordinatedVolume = 1000L)
        assertTrue(small < large, "smaller volume wins when rounds are equal")
    }

    @Test
    fun coordinationCostEqualityByValue() {
        val a = CoordinationCost(rounds = 1, coordinatedVolume = 200L)
        val b = CoordinationCost(rounds = 1, coordinatedVolume = 200L)
        assertEquals(a, b)
        assertEquals(0, a.compareTo(b))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a [WarpStats] with [n] distinct observations for [opId].
     * Elements are named "element_1" … "element_n" — deterministic, no randomness.
     */
    private fun statsFor(opId: OpId, n: Int): WarpStats =
        (1..n).fold(WarpStats.empty()) { stats, i ->
            stats.piece(stats.observe(opId, "element_$i"))
        }
}
