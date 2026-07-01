/**
 * G5 go/no-go gate: does batched Raft execution issue fewer real Raft rounds on a planned draft
 * than on the same unplanned draft?
 *
 * Theory: [Draft.executeCoordinated] issues one Raft proposal per coordinated node in the DAG.
 * An unplanned draft with K independent [DraftStage.Embroider] nodes issues K proposals; the
 * same draft after [Draft.plan] consolidates independent embroiders into [DraftStage.BatchedEmbroider]
 * nodes — one per dependency level — issuing only `depth` proposals.
 *
 * **Representative query (from G4):**
 * - Branch A: srcA → embroider(A)                     [level 0, independent]
 * - Branch B: srcB → embroider(B)                     [level 0, independent]
 * - Branch C: srcC → embroider(C) → map → embroider(D) [D at level 1, depends on C]
 *
 * Unplanned: 4 real Raft commits (A, B, C, D each → one proposal)
 * Planned: 2 real Raft commits (BatchedEmbroider(A,B,C) + Embroider(D))
 *
 * The go/no-go criterion:
 * 1. `plannedRounds < unplannedRounds` at real Raft execution.
 * 2. `roundsAtExecution == coordinationCost(planned, stats).rounds` — execution matches the model.
 *
 * Multi-node Raft discipline (per CLAUDE.md):
 * - [raftSimTest] provides [StandardTestDispatcher] + 5 s timeout.
 * - [MultiNodeRaftSim] handles per-node seeded [Random], backgroundScope child scopes,
 *   bounded [MultiNodeRaftSim.awaitLeader] and [MultiNodeRaftSim.proposeOnLeader] helpers.
 * - [MultiNodeRaftSim.checkInvariants] asserts election safety and state-machine safety.
 * - No hand-rolled cluster, no [advanceUntilIdle].
 *
 * **VERDICT: GO — see [plannedQueryIssuesDagDepthRaftRoundsNotK].**
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchedExecutionSimTest {

    /**
     * A single-embroider draft issues exactly one Raft round — the baseline regression.
     *
     * Proves that [Draft.executeCoordinated] issues one proposal for the simplest
     * coordinated case and that the proposal actually commits in the real Raft cluster.
     */
    @Test
    fun singleEmbroiderDraftIssuesOneRaftRound() = raftSimTest(n = 3) { sim ->
        val draft = Warp.shuttle(OpId("src")).embroider(OpId("emb"))
        sim.awaitLeader()

        val rounds = draft.executeCoordinated { sim.proposeOnLeader(it) }

        assertEquals(1, rounds, "single-embroider draft must issue exactly 1 Raft round")
        sim.checkInvariants()
    }

    /**
     * A planned draft (G4 representative query) issues `depth` real Raft rounds, strictly
     * fewer than the same draft unplanned (K rounds).
     *
     * Unplanned (4 separate Embroider nodes): 4 Raft commits
     * Planned (BatchedEmbroider(A,B,C) at level 0 + Embroider(D) at level 1): 2 Raft commits
     *
     * Also verifies `roundsAtExecution == coordinationCost(planned).rounds` — the analytical
     * model correctly predicts execution behaviour.
     *
     * **VERDICT: GO — planned rounds (2) < unplanned rounds (4) at real Raft execution.**
     */
    @Test
    fun plannedQueryIssuesDagDepthRaftRoundsNotK() = raftSimTest(n = 3) { sim ->
        val srcA = OpId("src.a")
        val srcB = OpId("src.b")
        val srcC = OpId("src.c")
        val embA = OpId("emb.a")
        val embB = OpId("emb.b")
        val embC = OpId("emb.c")
        val embD = OpId("emb.d")
        val mapM = OpId("map.m")

        // G4 representative query:
        //   Level 0: embroider(A), embroider(B), embroider(C) — independent
        //   Level 1: embroider(D) — depends on embroider(C)
        val branchA = Warp.shuttle(srcA).embroider(embA)
        val branchB = Warp.shuttle(srcB).embroider(embB)
        val branchC = Warp.shuttle(srcC).embroider(embC).map(mapM).embroider(embD)
        val unplanned: Draft<Unit> = branchA.combine(branchB).combine(branchC)
        val planned: Draft<Unit> = unplanned.plan(WarpStats.empty())

        sim.awaitLeader()

        // ── Unplanned: 4 separate Embroider nodes → 4 real Raft proposals ──────
        var unplannedRounds = 0
        unplanned.executeCoordinated { payload ->
            sim.proposeOnLeader(payload)
            unplannedRounds++
        }

        // ── Planned: 2 coordinated nodes → 2 real Raft proposals ────────────────
        var plannedRounds = 0
        planned.executeCoordinated { payload ->
            sim.proposeOnLeader(payload)
            plannedRounds++
        }

        val analyticalRounds = planned.coordinationCost(WarpStats.empty()).rounds

        assertAll(
            // Unplanned: one proposal per Embroider node (K = 4).
            {
                assertEquals(
                    4,
                    unplannedRounds,
                    "unplanned: 4 Embroider nodes → 4 real Raft proposals",
                )
            },
            // Planned: one proposal per dependency level (depth = 2).
            {
                assertEquals(
                    2,
                    plannedRounds,
                    "planned: BatchedEmbroider(A,B,C) + Embroider(D) → 2 real Raft proposals",
                )
            },
            // GO gate: the planned execution issues strictly fewer real Raft rounds.
            {
                assertTrue(
                    plannedRounds < unplannedRounds,
                    "GO/NO-GO GATE: planned rounds ($plannedRounds) must be strictly less than " +
                        "unplanned rounds ($unplannedRounds) at real Raft execution.",
                )
            },
            // Model accuracy: execution round count matches the analytical cost model.
            {
                assertEquals(
                    analyticalRounds,
                    plannedRounds,
                    "roundsAtExecution ($plannedRounds) must equal " +
                        "coordinationCost(planned).rounds ($analyticalRounds) — " +
                        "the analytical model must correctly predict execution behaviour.",
                )
            },
        )

        sim.checkInvariants()
    }
}
