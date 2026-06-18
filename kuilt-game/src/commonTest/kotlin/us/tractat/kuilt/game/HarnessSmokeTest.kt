@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Proves the harness wiring: two real [raftNode]s over [InMemoryLoom] seams converge to
 * a leader under virtual time with [StandardTestDispatcher]. De-risks every later Task.
 *
 * Uses [seats] from [GameBootstrapHarness] for the host/join pairing.
 */
class HarnessSmokeTest {

    @Test
    fun twoSeatStaticClusterElectsLeader() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (aSeam, bSeam) = seats(loom, 2)

        val ids = setOf(NodeId(aSeam.selfId.value), NodeId(bSeam.selfId.value))

        // Distinct seeds per node so election timeout draws differ and one node wins cleanly.
        val a = backgroundScope.raftNode(ClusterConfig.ofVoters(ids), SeamRaftTransport(aSeam), InMemoryRaftStorage(), fastRaftConfig(seed = 1L))
        val b = backgroundScope.raftNode(ClusterConfig.ofVoters(ids), SeamRaftTransport(bSeam), InMemoryRaftStorage(), fastRaftConfig(seed = 2L))

        // Race both nodes — exactly one becomes leader; the other remains follower.
        val winningRole = merge(a.role, b.role).first { it is RaftRole.Leader }
        assertIs<RaftRole.Leader>(winningRole)
    }
}

/**
 * Fast [RaftConfig] for virtual-time tests. Short timeouts so elections complete in a
 * handful of virtual milliseconds. [seed] differentiates per-node draws for symmetry-breaking
 * (callers must pass distinct seeds per node in multi-node tests).
 */
internal fun fastRaftConfig(seed: Long): RaftConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(seed),
)
