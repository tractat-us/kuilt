@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.scale

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Raft scaling tests in Layer A — deterministic, in-memory, CI-gateable.
 *
 * Drives clusters of N = 3, 5, 7, 9 voters through [MultiNodeRaftSim] and measures
 * message complexity as a function of N. The central regression guard is the **2(N-1)
 * steady-state cost per committed entry**: the leader sends exactly N-1 AppendEntries
 * (one per follower, `entryCount=1`) and receives exactly N-1 AppendEntriesAccepted
 * in response — a total of 2(N-1) transport messages per entry.
 *
 * Message counts are read from [us.tractat.kuilt.raft.RaftTraceEvent] — the engine
 * already emits [RaftTraceEvent.AppendEntries] and [RaftTraceEvent.AppendEntriesAccepted]
 * for every replication round, so no changes to [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork]
 * are needed. [RaftTraceEvent] captures both the send and the acceptance at the protocol
 * level, irrespective of serialisation overhead, making the counts transport-agnostic.
 *
 * All tests run under [raftSimTest]'s [StandardTestDispatcher] + 5 s timeout. Each test
 * is fenced independently so a hang in one does not block others.
 */
class RaftScalingTest {

    // ── Sweep: leaders elected at each node count ────────────────────────────

    @Test
    fun leaderElected_threeNodes() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        sim.checkInvariants()
        assertEquals(RaftRole.Leader::class, leader.role.value::class)
    }

    @Test
    fun leaderElected_fiveNodes() = raftSimTest(n = 5) { sim ->
        val leader = sim.awaitLeader()
        sim.checkInvariants()
        assertEquals(RaftRole.Leader::class, leader.role.value::class)
    }

    @Test
    fun leaderElected_sevenNodes() = raftSimTest(n = 7) { sim ->
        val leader = sim.awaitLeader()
        sim.checkInvariants()
        assertEquals(RaftRole.Leader::class, leader.role.value::class)
    }

    @Test
    fun leaderElected_nineNodes() = raftSimTest(n = 9) { sim ->
        val leader = sim.awaitLeader()
        sim.checkInvariants()
        assertEquals(RaftRole.Leader::class, leader.role.value::class)
    }

    // ── Regression guard: 2(N-1) messages per committed entry ───────────────

    /**
     * Verifies the Raft steady-state message complexity for a 3-node cluster.
     *
     * One committed entry costs exactly 2(N-1) = 4 transport messages:
     * - Leader → 2 followers: 2 AppendEntries (entryCount=1)
     * - 2 followers → Leader: 2 AppendEntriesAccepted
     */
    @Test
    fun replicationCost_threeNodes() = raftSimTest(n = 3) { sim ->
        assertReplicationCostIs2NMinus1(scope = this, sim = sim, n = 3)
    }

    /**
     * Verifies 2(N-1) = 8 messages for a 5-node cluster.
     */
    @Test
    fun replicationCost_fiveNodes() = raftSimTest(n = 5) { sim ->
        assertReplicationCostIs2NMinus1(scope = this, sim = sim, n = 5)
    }

    /**
     * Verifies 2(N-1) = 12 messages for a 7-node cluster.
     */
    @Test
    fun replicationCost_sevenNodes() = raftSimTest(n = 7) { sim ->
        assertReplicationCostIs2NMinus1(scope = this, sim = sim, n = 7)
    }

    /**
     * Verifies 2(N-1) = 16 messages for a 9-node cluster.
     */
    @Test
    fun replicationCost_nineNodes() = raftSimTest(n = 9) { sim ->
        assertReplicationCostIs2NMinus1(scope = this, sim = sim, n = 9)
    }

    // ── Convergence: all nodes commit a proposed entry ───────────────────────

    /**
     * All N=3 nodes converge to the same commit index after one proposal.
     */
    @Test
    fun allNodesCommit_threeNodes() = raftSimTest(n = 3) { sim ->
        sim.awaitLeader()
        val entry = sim.proposeOnLeader("cmd".encodeToByteArray())
        sim.awaitCommit(entry.index)
        sim.checkInvariants()
    }

    /**
     * All N=7 nodes converge to the same commit index after one proposal.
     */
    @Test
    fun allNodesCommit_sevenNodes() = raftSimTest(n = 7) { sim ->
        sim.awaitLeader()
        val entry = sim.proposeOnLeader("cmd".encodeToByteArray())
        sim.awaitCommit(entry.index)
        sim.checkInvariants()
    }

    /**
     * All N=9 nodes converge to the same commit index after one proposal.
     */
    @Test
    fun allNodesCommit_nineNodes() = raftSimTest(n = 9) { sim ->
        sim.awaitLeader()
        val entry = sim.proposeOnLeader("cmd".encodeToByteArray())
        sim.awaitCommit(entry.index)
        sim.checkInvariants()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Asserts the 2(N-1) steady-state Raft message complexity for one committed entry.
     *
     * **Event distribution**: `trace` is per-node. The leader emits
     * [RaftTraceEvent.AppendEntries] (it sends RPCs). Each follower emits
     * [RaftTraceEvent.AppendEntriesAccepted] when it accepts (the `from` field identifies
     * which leader's RPC was accepted). To capture both halves of the 2(N-1) cost,
     * we collect from **all** nodes' traces.
     *
     * Approach:
     * 1. Elect a leader and settle the cluster.
     * 2. Subscribe to every node's trace on [TestScope.backgroundScope] — `trace` is a
     *    [kotlinx.coroutines.flow.SharedFlow] (replay=0) that supports multiple collectors;
     *    the [MultiNodeRaftSim] ring-buffer is already one collector, and our second
     *    subscription is safe.
     * 3. `settle()` after launch so all collection coroutines run and subscribe before any
     *    proposal-related events are emitted.
     * 4. Propose one entry and wait for all nodes to commit.
     * 5. Drain and count:
     *    - [RaftTraceEvent.AppendEntries] where `entryCount > 0` — replication RPCs
     *      (heartbeats with `entryCount=0` are excluded).
     *    - [RaftTraceEvent.AppendEntriesAccepted] where `matchIndex == entry.index` —
     *      follower acceptances for exactly this proposed entry.
     *
     * Why trace-based instead of network-counter-based: [RaftTraceEvent] already exposes
     * the exact message types needed as semantically typed events. Adding a raw counter to
     * [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork.sendTo] would count opaque bytes
     * and require decoding to separate heartbeats from replication — more instrumentation,
     * less information. The trace is the authoritative source and needs no modification.
     */
    private suspend fun assertReplicationCostIs2NMinus1(
        scope: TestScope,
        sim: MultiNodeRaftSim,
        n: Int,
    ) {
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        // Settle the cluster so background heartbeat AppendEntries drain before counting.
        sim.settle()

        // Subscribe to ALL nodes' traces — AppendEntries comes from the leader's trace,
        // AppendEntriesAccepted comes from each follower's trace.
        val traceChannel = Channel<RaftTraceEvent>(Channel.UNLIMITED)
        val collections = sim.nodes.values.map { node ->
            scope.backgroundScope.launch {
                node.trace.collect { traceChannel.send(it) }
            }
        }
        // Yield so all launched collection coroutines run and subscribe to their SharedFlows
        // before we propose. Under StandardTestDispatcher, launch queues — settle() lets them
        // execute and register as subscribers, so no proposal events are missed.
        sim.settle()

        // Propose one entry and wait for it to commit on every node.
        val entry = sim.proposeOnLeader("scale-probe".encodeToByteArray())
        sim.awaitCommit(entry.index)
        // Do NOT settle after commit — heartbeats running post-commit would generate
        // additional AppendEntriesAccepted at the same matchIndex, inflating the count.
        // awaitCommit already guarantees all followers have committed; the first round
        // of replication RPCs is complete.

        collections.forEach { it.cancel() }
        traceChannel.close()

        // Drain and count replication AppendEntries (entryCount > 0) from the leader
        // and the first-round follower acceptances at the proposed entry's index.
        val events = buildList { for (e in traceChannel) add(e) }
        val appendEntriesWithPayload = events.count {
            it is RaftTraceEvent.AppendEntries &&
                it.from == leaderId &&
                it.entryCount > 0
        }
        // Count distinct followers that accepted at matchIndex == entry.index.
        // Using distinct-by-follower rather than a raw count ensures we count one
        // acceptance per follower even when a heartbeat triggers a second confirmation.
        val replicationAcceptances = events
            .filterIsInstance<RaftTraceEvent.AppendEntriesAccepted>()
            .filter { it.from == leaderId && it.matchIndex == entry.index }
            .distinctBy { it.to }
            .size

        val followers = n - 1
        assertAll(
            {
                assertEquals(
                    followers,
                    appendEntriesWithPayload,
                    "N=$n: leader should send exactly N-1=$followers AppendEntries with entry payload",
                )
            },
            {
                assertEquals(
                    followers,
                    replicationAcceptances,
                    "N=$n: leader should receive exactly N-1=$followers AppendEntriesAccepted " +
                        "at matchIndex=${entry.index}",
                )
            },
        )
    }
}
