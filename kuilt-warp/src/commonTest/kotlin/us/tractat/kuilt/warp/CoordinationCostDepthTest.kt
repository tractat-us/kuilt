package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G4 tests — `rounds` as DAG depth, `coupling` blast-radius term, and the go/no-go gate.
 *
 * Theory: the number of genuine consensus rounds equals the count of coordinated nodes in
 * the planned DAG — where [DraftStage.BatchedEmbroider] counts as **one** round regardless
 * of how many ops it batches. Independent embroideries that consolidate into a single
 * [DraftStage.BatchedEmbroider] therefore reduce the round count; a sequential chain does not.
 *
 * The coupling term ([CoordinationCost.coupling]) captures the blast-radius tradeoff: a
 * larger batch commits more agreements in one proposal, which is efficient but couples their
 * failure domains. Lexicographic ordering: rounds first (minimise), then coupling
 * (minimise), then volume (minimise).
 *
 * ## Go/no-go gate (G4)
 *
 * A representative multi-coordination query — three independent sub-queries plus one
 * dependent chain — must show a measurable round-count cut after [Draft.plan]:
 *   unplanned rounds: 4  (each Embroider is its own node)
 *   planned rounds:   2  (BatchedEmbroider at level 0 + one dependent Embroider at level 1)
 *
 * This is the round-count cut that E-3 could not show (E-3 was pinned at ≤1 rounds on a
 * linear pipeline). A truthful NO-GO would replace the assertion with an explicit failure
 * comment.
 *
 * **VERDICT: GO — see [planCutsRoundsOnMultiCoordinationQuery].**
 */
class CoordinationCostDepthTest {

    private val srcA = OpId("source.a")
    private val srcB = OpId("source.b")
    private val srcC = OpId("source.c")
    private val embA = OpId("embroider.a")
    private val embB = OpId("embroider.b")
    private val embC = OpId("embroider.c")
    private val embD = OpId("embroider.d")
    private val mapM = OpId("map.m")
    private val filterF = OpId("filter.f")

    // ── rounds: single-embroider regression ──────────────────────────────────

    @Test
    fun singleEmbroiderPathHasOneRound() {
        val draft = Warp.shuttle(srcA).map(mapM).embroider(embA)
        assertEquals(1, draft.coordinationCost(WarpStats.empty()).rounds)
    }

    @Test
    fun fullyMonotoneDraftHasZeroRoundsAndZeroCoupling() {
        val draft = Warp.shuttle(srcA).map(mapM).filter(filterF)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertAll(
            { assertEquals(0, cost.rounds, "fully-monotone draft must have 0 rounds") },
            { assertEquals(0, cost.coupling, "fully-monotone draft must have 0 coupling") },
        )
    }

    // ── rounds: multiple coordinated nodes ────────────────────────────────────

    @Test
    fun twoIndependentEmbroideriesHaveTwoRoundsBeforeConsolidation() {
        // Two independent branches — no consolidation yet.
        val combined = Warp.shuttle(srcA).embroider(embA)
            .combine(Warp.shuttle(srcB).embroider(embB))
        val cost = combined.coordinationCost(WarpStats.empty())
        assertEquals(
            2,
            cost.rounds,
            "two independent embroideries (unconsolidated) must report 2 rounds",
        )
    }

    @Test
    fun threeIndependentEmbroideriesHaveThreeRoundsBeforeConsolidation() {
        val combined = Warp.shuttle(srcA).embroider(embA)
            .combine(Warp.shuttle(srcB).embroider(embB))
            .combine(Warp.shuttle(srcC).embroider(embC))
        val cost = combined.coordinationCost(WarpStats.empty())
        assertEquals(
            3,
            cost.rounds,
            "three independent embroideries (unconsolidated) must report 3 rounds",
        )
    }

    @Test
    fun consolidatedEmbroideriesHaveOneRound() {
        val combined = Warp.shuttle(srcA).embroider(embA)
            .combine(Warp.shuttle(srcB).embroider(embB))
        val consolidated = combined.consolidateEmbroideries()
        val cost = consolidated.coordinationCost(WarpStats.empty())
        assertEquals(
            1,
            cost.rounds,
            "two embroideries consolidated into one BatchedEmbroider must report 1 round",
        )
    }

    @Test
    fun dependencyChainHasTwoRoundsNotOne() {
        // embroider(A) → map → embroider(B): sequential dependency — cannot batch.
        val chained = Warp.shuttle(srcA).embroider(embA).map(mapM).embroider(embB)
        val cost = chained.coordinationCost(WarpStats.empty())
        assertEquals(
            2,
            cost.rounds,
            "dependency chain of two embroideries must report 2 rounds",
        )
    }

    // ── BatchedEmbroider counts as one round, not opIds.size ─────────────────

    @Test
    fun batchedEmbroiderCountsAsOneRoundNotOpIdsSize() {
        // A BatchedEmbroider with 3 opIds is still one consensus proposal.
        val batched = DraftStage.BatchedEmbroider(listOf(embA, embB, embC))
        val node = DraftNode(
            id = NodeId(1),
            stage = batched,
            predecessors = setOf(NodeId(0)),
        )
        val source = DraftNode(
            id = NodeId(0),
            stage = DraftStage.Source(srcA),
            predecessors = emptySet(),
        )
        val draft = Draft<Unit>(listOf(source, node))
        val cost = draft.coordinationCost(WarpStats.empty())
        assertEquals(
            1,
            cost.rounds,
            "BatchedEmbroider(3 ops) must count as 1 round, not 3",
        )
    }

    // ── coupling term = max batch size ────────────────────────────────────────

    @Test
    fun couplingIsOneForSingleEmbroider() {
        val draft = Warp.shuttle(srcA).embroider(embA)
        val cost = draft.coordinationCost(WarpStats.empty())
        assertEquals(
            1,
            cost.coupling,
            "single Embroider has coupling = 1 (batch size 1)",
        )
    }

    @Test
    fun couplingEqualsMaxBatchSizeAfterConsolidation() {
        // Three independent branches consolidated into BatchedEmbroider(3 ops) → coupling = 3.
        val combined = Warp.shuttle(srcA).embroider(embA)
            .combine(Warp.shuttle(srcB).embroider(embB))
            .combine(Warp.shuttle(srcC).embroider(embC))
        val consolidated = combined.consolidateEmbroideries()
        val cost = consolidated.coordinationCost(WarpStats.empty())
        assertEquals(
            3,
            cost.coupling,
            "BatchedEmbroider(3 ops) must have coupling = 3",
        )
    }

    @Test
    fun couplingIsMaxAcrossAllCoordinatedNodesInMixedDraft() {
        // level 0: BatchedEmbroider(A,B,C) → coupling 3
        // level 1: Embroider(D) → coupling 1
        // max = 3
        val combined = Warp.shuttle(srcA).embroider(embA)
            .combine(Warp.shuttle(srcB).embroider(embB))
            .combine(Warp.shuttle(srcC).embroider(embC).map(mapM).embroider(embD))
        val planned = combined.plan(WarpStats.empty())
        val cost = planned.coordinationCost(WarpStats.empty())
        assertEquals(
            3,
            cost.coupling,
            "coupling must equal the max batch size across all coordinated nodes (3 from BatchedEmbroider)",
        )
    }

    // ── CoordinationCost ordering — coupling as secondary tiebreaker ──────────

    @Test
    fun couplingBreaksTieWhenRoundsAreEqual() {
        val higherCoupling = CoordinationCost(rounds = 1, coupling = 5, coordinatedVolume = 10L)
        val lowerCoupling = CoordinationCost(rounds = 1, coupling = 2, coordinatedVolume = 100L)
        assertTrue(
            lowerCoupling < higherCoupling,
            "smaller coupling wins when rounds are equal (even if volume is higher)",
        )
    }

    @Test
    fun volumeBreaksTieWhenRoundsAndCouplingAreEqual() {
        val smallVol = CoordinationCost(rounds = 1, coupling = 2, coordinatedVolume = 50L)
        val largeVol = CoordinationCost(rounds = 1, coupling = 2, coordinatedVolume = 500L)
        assertTrue(
            smallVol < largeVol,
            "smaller volume wins when both rounds and coupling are equal",
        )
    }

    // ── Go/no-go: plan measurably cuts round count ────────────────────────────

    /**
     * Representative multi-coordination query:
     * - Branch A: srcA → embroider(A)       [independent, level 0]
     * - Branch B: srcB → embroider(B)       [independent, level 0]
     * - Branch C: srcC → embroider(C) → map → embroider(D)  [chain: D depends on C, level 1]
     *
     * Unplanned: 4 coordinated nodes (A, B, C, D) → rounds = 4
     * Planned (after optimize/consolidate): BatchedEmbroider(A,B,C) + Embroider(D) → rounds = 2
     *
     * This is the round-count cut E-3 could not demonstrate (E-3 was structurally pinned at
     * ≤1 rounds on a single-embroider linear pipeline). On a multi-coordination DAG, the
     * planner's consolidation step drives rounds from N (one per node) to the coordination
     * DAG depth (one per dependency level).
     *
     * **VERDICT: GO — planned rounds (2) < unplanned rounds (4).**
     */
    @Test
    fun planCutsRoundsOnMultiCoordinationQuery() {
        // Branch A: single embroider.
        val branchA = Warp.shuttle(srcA).embroider(embA)
        // Branch B: single embroider.
        val branchB = Warp.shuttle(srcB).embroider(embB)
        // Branch C: chained — embroider(C) must complete before embroider(D).
        val branchC = Warp.shuttle(srcC).embroider(embC).map(mapM).embroider(embD)

        val unplanned = branchA.combine(branchB).combine(branchC)
        val planned = unplanned.plan(WarpStats.empty())

        val unplannedRounds = unplanned.coordinationCost(WarpStats.empty()).rounds
        val plannedRounds = planned.coordinationCost(WarpStats.empty()).rounds

        assertAll(
            // Structural guarantee: plan preserves semantics.
            {
                assertTrue(
                    unplanned.isEquivalentTo(planned),
                    "plan must preserve semantic equivalence",
                )
            },
            // Unplanned: 4 separate Embroider nodes → 4 rounds.
            {
                assertEquals(
                    4,
                    unplannedRounds,
                    "unplanned multi-coordination query must report 4 rounds (one per Embroider node)",
                )
            },
            // Planned: BatchedEmbroider(A,B,C) + Embroider(D) → 2 rounds.
            {
                assertEquals(
                    2,
                    plannedRounds,
                    "planned query must report 2 rounds (BatchedEmbroider at level 0 + Embroider at level 1)",
                )
            },
            // GO gate: the planner demonstrably cuts round count.
            {
                assertTrue(
                    plannedRounds < unplannedRounds,
                    "GO/NO-GO GATE: planned rounds ($plannedRounds) must be less than unplanned ($unplannedRounds). " +
                        "E-3 could not show this (single-embroider pipeline pinned at rounds ≤ 1); " +
                        "G4 unlocks the round-count lever via consolidation.",
                )
            },
        )
    }
}
