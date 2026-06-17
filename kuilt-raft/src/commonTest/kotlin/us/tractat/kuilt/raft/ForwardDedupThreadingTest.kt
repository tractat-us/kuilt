@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Task 5: a forwarded proposal's [DedupKey] is stamped at the *proposer* and arrives at the leader's
 * log UNCHANGED — the leader must not re-stamp it. Reuses #483's forwarding harness ([raftSim]); the
 * new assertion is that the proposer's key equals the committed entry's key on the leader.
 */
class ForwardDedupThreadingTest {
    @Test
    fun forwardCarriesProposerKeyUnchangedToLeaderLog() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.followers().first()

        val returned = follower.propose("m".encodeToByteArray(), requestId = 7)
        sim.awaitCommit(returned.index)

        // The proposer pinned requestId 7 under its own clientId; the leader appended it verbatim.
        val leaderEntry = sim.storages.getValue(leaderId).entries().first { it.index == returned.index }
        assertNotNull(returned.dedupKey)
        assertEquals(7L, returned.dedupKey?.requestId)
        // Leader did NOT re-stamp: the committed entry carries the proposer's exact key (clientId + serial).
        assertEquals(returned.dedupKey, leaderEntry.dedupKey)
        sim.checkInvariants()
    }
}
