package us.tractat.kuilt.raft.test

import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test

/** Real-engine consumers migrated from kuilt-raft's hand-written trace collectors. */
class TraceTest {
    @Test fun initialElection_emits_Timeout_and_RequestVote() = raftSimTest { sim ->
        val leader = sim.awaitLeader()
        val id = sim.nodes.entries.first { it.value === leader }.key
        sim.settle()
        assertAll(
            { sim.assertTraced<RaftTraceEvent.Timeout>(id) },
            { sim.assertTraced<RaftTraceEvent.RequestVote>(id) },
        )
    }

    @Test fun proposal_emits_ClientRequest_then_AdvanceCommitIndex() = raftSimTest { sim ->
        val leader = sim.awaitLeader()
        val id = sim.nodes.entries.first { it.value === leader }.key
        val index = leader.propose(byteArrayOf(42)).index
        sim.awaitCommit(index)
        sim.settle()
        sim.assertTracedInOrder(id,
            { it is RaftTraceEvent.ClientRequest && it.index == index },
            { it is RaftTraceEvent.AdvanceCommitIndex && it.newCommitIndex >= index },
            message = "proposal must precede its commit",
        )
    }

    @Test fun stepDown_emits_BecomeFollower() = raftSimTest { sim ->
        val leader = sim.awaitLeader()
        val id = sim.nodes.entries.first { it.value === leader }.key
        sim.settle()
        val elected = sim.assertTraced<RaftTraceEvent.BecomeLeader>(id)
        sim.partitionOff(id)
        sim.awaitLeader(among = sim.nodeIds.filter { it != id }.toSet())
        sim.heal()
        sim.awaitRole(id, RaftRole.Follower)
        sim.settle()
        sim.assertTraced<RaftTraceEvent.BecomeFollower>(id) { it.term >= elected.term && it.clock > elected.clock }
    }
}
