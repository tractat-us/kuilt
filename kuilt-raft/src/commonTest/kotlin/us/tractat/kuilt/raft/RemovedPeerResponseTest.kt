@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * #3124: once a peer has been removed, a same-term response from it must not re-create the leader's
 * state for it, nor resume replication to it.
 *
 * The observable half is the second. A rejected AppendEntries response used to drive the §5.3 backup
 * and an immediate resend to whoever sent it, member or not. So a late rejection from a removed peer
 * pulled the leader back into replicating to a node outside the config, and rebuilt the
 * `nextIndex`/`matchIndex` entries the prune had just dropped. The control is the leader's own trace:
 * an `AppendEntries` addressed to the removed peer after its rejection lands.
 */
class RemovedPeerResponseTest {

    @Test
    fun aRejectionFromARemovedPeerDoesNotResumeReplicationToIt() = raftRunTest {
        val voters = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val removed = NodeId("learner")
        val config = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = voters.toList() + removed,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(ClusterConfig(voters = voters, learners = setOf(removed)), transport, storage, config)
            },
        )
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        leader.changeMembership(ClusterConfig(voters = voters))
        sim.partitionOff(removed)

        val sentToRemoved = mutableListOf<RaftTraceEvent.AppendEntries>()
        backgroundScope.launch {
            leader.trace.collect { if (it is RaftTraceEvent.AppendEntries && it.to == removed) sentToRemoved += it }
        }
        sim.settle()

        sim.deliverAppendEntriesResponse(to = leaderId, from = removed, term = sim.storages.getValue(leaderId).term())
        delay(20.milliseconds)
        assertEquals(emptyList(), sentToRemoved, "the leader resumed replicating to a peer outside its config")
    }
}
