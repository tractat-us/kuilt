@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Determinism witnesses for issue #383: a real [RaftNode] cluster electing a leader under the
 * canonical [raftRunTest] harness — now [StandardTestDispatcher] + virtual time — resolves at a
 * fixed *virtual* instant while consuming negligible *wall* time, independent of host load.
 *
 * The convergence assertion itself lives in [ElectionTest]; this file pins the two properties that
 * make the suite deterministic and that a future reader must not regress: bounded virtual instant
 * and wall-clock independence. See the banner in [RaftTestFixtures].
 */
class VirtualElectionTest {
    @Test
    fun initialElection_elects_exactly_one_leader() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        assertNotNull(leader)
        assertTrue(leader.role.value is RaftRole.Leader)
        sim.checkInvariants()
    }

    /**
     * Determinism witness: the election resolves at a fixed *virtual* instant (≤ two election-timeout
     * windows of [FAST_RAFT_CONFIG], i.e. ≤ 20 ms — two because PreVote + real election each take up
     * to one window) while consuming negligible *wall* time. This is the property the prior
     * `UnconfinedTestDispatcher` harness could not guarantee — its eager-execution ordering of timers
     * vs message round-trips was load-dependent.
     *
     * Uses a fresh, private [RaftConfig] with its own seeded [Random] so the test's RNG draws are
     * independent of how many other tests ran before it (FAST_RAFT_CONFIG's RNG is stateful and shared).
     */
    @Test
    fun election_resolves_at_a_deterministic_virtual_instant() = raftRunTest {
        val config = FAST_RAFT_CONFIG.copy(random = Random(RAFT_TEST_SEED))
        val wallStart = TimeSource.Monotonic.markNow()
        val sim = raftSim(this, backgroundScope, config = config)
        awaitLeader(sim)
        val virtualAtLeader = testScheduler.currentTime
        val wallElapsedMs = wallStart.elapsedNow().inWholeMilliseconds
        // Two election windows: PreVote can run up to one window before the real election starts.
        val maxVirtualMs = 2 * config.electionTimeoutMax.inWholeMilliseconds
        assertTrue(
            virtualAtLeader in 1..maxVirtualMs,
            "leader should emerge within two election windows; virtual instant was $virtualAtLeader ms",
        )
        assertTrue(wallElapsedMs < 2_000L, "virtual-time test must not depend on wall clock; spent ${wallElapsedMs}ms")
    }
}
