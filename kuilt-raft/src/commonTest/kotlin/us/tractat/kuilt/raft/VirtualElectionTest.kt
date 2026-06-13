@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Proof-of-concept (issue #383): one election test converted to fully deterministic virtual time.
 *
 * Mirrors `ElectionTest.initialElection_elects_exactly_one_leader`, but runs under
 * [raftVirtualTest] ([StandardTestDispatcher] + bounded `advanceTimeBy`) so its outcome is
 * independent of host load — no eager-execution ordering race, no real-clock dependence.
 */
class VirtualElectionTest {
    @Test
    fun initialElection_elects_exactly_one_leader() = raftVirtualTest {
        val sim = VirtualRaftSim(this, backgroundScope)
        val leader = sim.awaitLeaderV()
        assertNotNull(leader)
        assertTrue(leader.role.value is RaftRole.Leader)
        sim.sim.checkInvariants()
    }

    /**
     * Determinism witness: the election resolves at a fixed *virtual* instant (≤ one election-timeout
     * window of [FAST_RAFT_CONFIG], i.e. ≤ 10 ms) while consuming negligible *wall* time. This is the
     * property [ElectionTest] cannot guarantee — under `UnconfinedTestDispatcher` the eager-execution
     * ordering of timers vs message round-trips is load-dependent.
     */
    @Test
    fun election_resolves_at_a_deterministic_virtual_instant() = raftVirtualTest {
        val wallStart = TimeSource.Monotonic.markNow()
        val sim = VirtualRaftSim(this, backgroundScope)
        sim.awaitLeaderV()
        val virtualAtLeader = testScheduler.currentTime
        val wallElapsedMs = wallStart.elapsedNow().inWholeMilliseconds
        assertTrue(
            virtualAtLeader in 1..FAST_RAFT_CONFIG.electionTimeoutMax.inWholeMilliseconds,
            "leader should emerge within one election window; virtual instant was $virtualAtLeader ms",
        )
        assertTrue(wallElapsedMs < 2_000L, "virtual-time test must not depend on wall clock; spent ${wallElapsedMs}ms")
    }
}
