/**
 * #875: a deterministic contested-settle test for intent-register loser stand-down.
 *
 * The intent register exists to catch the transient-window duplicate: during membership churn
 * two peers' rings briefly disagree on a task's owner, so both claim and announce intent for the
 * same task. The [us.tractat.kuilt.warp.ClaimStrategy.RingWithIntent] settle window then holds
 * execution while the grow-only claimant set converges, after which every peer computes the same
 * winner (the lowest live claimant) — exactly one executes and the others deterministically
 * stand down. This is the same intent-register path #931's failover fix touched; that test proved
 * the winner *follows through*, but never asserted the loser *stands down*. This closes that gap.
 *
 * Runs through the published [MultiNodeWarpSim] harness ([warpSimTest]): `StandardTestDispatcher`
 * (FIFO at each virtual instant), a virtual clock off the test scheduler, bounded settle/await —
 * never `advanceUntilIdle()` (the Quilter anti-entropy loops re-arm forever). A custom
 * `nodeFactory` gives the two peers disjoint roster views to manufacture the contest.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp.test

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.WarpNode
import kotlin.test.Test
import kotlin.test.assertEquals

class WarpContestedSettleTest {

    /**
     * Two peers transiently both own the same task under disjoint roster views; after the settle
     * window resolves the contest, exactly one — the lowest-`PeerId` winner — executes and the
     * other stands down. No duplicate.
     *
     * The contest is manufactured with disjoint rings: the host (`peer-1`, the lowest id) starts
     * with a **self-only** roster, so its single-peer ring makes it own every task; the joiner
     * (`peer-2`) starts with the **full** roster, so it is the true two-peer ring owner of the
     * chosen task. Both therefore claim the same task and announce intent into the grow-only
     * claimant register. Because both rings changed at startup, `RingWithIntent` holds execution
     * for the settle window while the intent set converges; every peer then computes the same
     * winner (`peer-1`). The host executes; the joiner stands down. The joiner's stand-down is the
     * behaviour under test — asserted directly as "the task executed exactly once, by the winner".
     */
    @Test
    fun contestedClaimSettlesToOneWinnerAndTheLoserStandsDown(): TestResult {
        // Populated inside nodeFactory (invoked in join order during harness init); keyed by
        // PeerId so the body can converge the host's view once the contest has been announced.
        val rosterFlows = mutableMapOf<PeerId, MutableStateFlow<Set<PeerId>>>()

        return warpSimTest(
            n = 2,
            strategy = ClaimStrategy.RingWithIntent(),
            nodeFactory = { sim, id, seam, scope ->
                // peer-1 (host, lowest id) → self-only ring: claims every task.
                // peer-2 (joiner)         → full ring: the true owner of the contested task.
                val initial = if (id == sim.peer(0)) setOf(id) else sim.peerIds.toSet()
                val roster = MutableStateFlow(initial)
                rosterFlows[id] = roster
                WarpNode(
                    selfId = id,
                    seam = seam,
                    rosterFlow = roster,
                    scope = scope,
                    quilterConfig = sim.quilterConfig,
                    clock = sim.virtualClock,
                    strategy = ClaimStrategy.RingWithIntent(),
                    registry = sim.trackedEchoRegistry(id),
                    epoch = 0L,
                )
            },
        ) { sim ->
            val winner = sim.peer(0) // peer-1 — the lowest PeerId, so the deterministic winner
            val loser = sim.peer(1)  // peer-2 — the true ring owner, which must stand down

            // A task the converged two-peer ring assigns to the joiner, so it legitimately claims
            // it while the host claims it only because its transient self-only ring owns everything.
            val task = sim.taskOwnedBy(loser)
            sim.enqueueEcho(task, on = winner)

            // Both peers announce intent under their disjoint views; the settle window holds
            // execution while the claimant register converges and the winner is resolved.
            sim.settle()

            // The host's view catches up to the full roster — the contest is fully resolved.
            rosterFlows.getValue(winner).value = sim.peerIds.toSet()
            sim.awaitResults(listOf(task))

            assertAll(
                {
                    assertEquals(
                        listOf(winner),
                        sim.executedBy(task),
                        "the task executes exactly once, on the lowest-PeerId winner; the loser stands down",
                    )
                },
                { assertEquals(setOf(task), sim.node(0).results.taskIds, "result converges on the winner's board") },
                { assertEquals(setOf(task), sim.node(1).results.taskIds, "result converges on the loser's board") },
            )
        }
    }
}
