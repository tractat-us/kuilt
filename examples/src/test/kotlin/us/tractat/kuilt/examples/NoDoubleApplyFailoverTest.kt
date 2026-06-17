@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LeadershipTransferException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_BASE_CONFIG
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M=3 done-when integration test for epic #485, S1c-2.
 *
 * Proves the exactly-once guarantee under a mid-flight leader change followed by
 * entry-server death. The client round-robins to a surviving server and retries
 * with the same `requestId`; [ClientSessionTable] skips the duplicate and the
 * action appears exactly once in committed state on every surviving voter.
 *
 * ## Design
 *
 * - Uses [MultiNodeRaftSim] (`:kuilt-raft-test`) for the 3-voter cluster: per-node
 *   seeded RNG, bounded `await*` helpers with active election-thrash detection.
 * - The learner client is a 4th node wired manually into `sim.network` then
 *   registered in `sim.nodes` so `sim.awaitCommit` covers it.
 * - Virtual time throughout (`raftSimTest` wires `StandardTestDispatcher`).
 * - No `advanceUntilIdle()` — bounded `sim.await*` only.
 *
 * ## What is proven
 *
 * After the leader change, two committed entries carry the same
 * [us.tractat.kuilt.raft.DedupKey] (the new leader's cache is cold).
 * [ClientSessionTable.shouldApply] applied to the committed log applies the first
 * and skips the second — count on every surviving voter == 1.
 */
class NoDoubleApplyFailoverTest {

    @Test
    fun `m3 leader-change and entry-server-kill — no double-apply`() = raftSimTest(n = 3) { sim ->
        val clientId = NodeId("client")
        val voterIds = sim.nodeIds.toSet()
        val learnerConfig = ClusterConfig(voters = voterIds, learners = setOf(clientId))

        // ── Add the learner client as a 4th node via sim.network ─────────────
        val clientNode = CoroutineScope(backgroundScope.coroutineContext + Job()).raftNode(
            clusterConfig = learnerConfig,
            transport = sim.network.transport(clientId),
            storage = InMemoryRaftStorage(),
            raftConfig = MULTI_NODE_SIM_BASE_CONFIG.copy(random = Random(485L + 99)),
            clientId = ClientId("client"),
        )
        // Register so sim.awaitCommit(on = setOf(clientId)) works.
        sim.nodes[clientId] = clientNode

        // ── Step 1: elect a leader among the 3 voters ───────────────────────
        val initialLeader = sim.awaitLeader(among = voterIds)
        val initialLeaderId = sim.nodes.entries.first { it.value === initialLeader }.key

        // ── Step 2: add the learner (learner-set-only change — skips joint consensus) ─
        addLearner(sim, voterIds, learnerConfig)

        // ── Step 3: client proposes "action:A" with requestId = 1 ────────────
        val command = "action:A".encodeToByteArray()
        val firstEntry = proposeFromClient(clientNode, command, requestId = 1L)
        sim.awaitCommit(firstEntry.index, on = voterIds)
        sim.awaitCommit(firstEntry.index, on = setOf(clientId))

        // ── Step 4: set up ServerClusterReconnect starting on the initial leader ─
        val endpoints = sim.nodeIds.map { NodeId2Tag(it) }
        val initialLeaderIndex = sim.nodeIds.indexOf(initialLeaderId)
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
            selector = RoundRobinEndpointSelector(startIndex = initialLeaderIndex),
        )
        assertEquals(
            initialLeaderId.value,
            reconnect.currentEndpoint().peerKey,
            "reconnect must start on the initial leader's endpoint",
        )

        // ── Step 5: transfer leadership, then kill the initial entry server ──
        val transferTarget = sim.nodeIds.first { it != initialLeaderId }
        transferLeadershipBestEffort(sim, from = initialLeaderId, to = transferTarget)

        // Confirm the surviving 2-voter quorum has a leader before crashing the old one.
        val survivingVoters = voterIds - initialLeaderId
        sim.awaitLeader(among = survivingVoters)

        sim.crash(initialLeaderId)
        // Remove from sim.nodes so awaitCommit does not poll the crashed node.
        sim.nodes.remove(initialLeaderId)

        // ── Step 6: round-robin to the next endpoint ─────────────────────────
        reconnect.onTransportTear()
        assertTrue(
            reconnect.currentEndpoint().peerKey != initialLeaderId.value,
            "ServerClusterReconnect must advance past the killed endpoint",
        )

        // ── Step 7: retry with the same requestId ───────────────────────────
        // The new leader's dedup cache is cold; it appends a NEW entry with the same
        // DedupKey — the duplicate-commit scenario the consumer must guard against.
        val retried = proposeFromClient(clientNode, command, requestId = 1L)

        sim.awaitCommit(retried.index, on = survivingVoters)
        sim.awaitCommit(retried.index, on = setOf(clientId))

        // ── Step 8: assert exactly-once on each surviving voter ───────────────
        for (voterId in survivingVoters) {
            val entries = replayEntries(sim.nodes.getValue(voterId), upToIndex = retried.index)
            assertExactlyOnce(command, entries, "action:A on voter $voterId (no double-apply)")
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Change membership to [target] on the current leader, retrying on transient failures.
     * Uses [MultiNodeRaftSim.awaitLeader] so the churn detector bounds the wait.
     */
    private suspend fun addLearner(sim: MultiNodeRaftSim, voterIds: Set<NodeId>, target: ClusterConfig) {
        while (true) {
            val leader = sim.awaitLeader(among = voterIds)
            try {
                leader.changeMembership(target)
                return
            } catch (_: NotLeaderException) {
                delay(1)
            } catch (_: MembershipChangeInProgressException) {
                delay(1)
            }
        }
    }

    /**
     * Propose [command] with [requestId] from [clientNode], retrying on transient failures.
     *
     * [RaftNode.propose] on a learner forwards to the leader and waits for the
     * leader to commit; the only retriable exception is [LeadershipLostException]
     * (leader stepped down mid-forward). [NotLeaderException] signals a terminal
     * misconfiguration — we still retry after a 1 ms pause as a guard against the
     * narrow window where the leader has just stepped down but the learner hasn't
     * observed a new one yet.
     */
    private suspend fun proposeFromClient(clientNode: RaftNode, command: ByteArray, requestId: Long): LogEntry {
        while (true) {
            try {
                return clientNode.propose(command, requestId)
            } catch (_: LeadershipLostException) {
                delay(1)
            } catch (_: NotLeaderException) {
                delay(1)
            }
        }
    }

    /**
     * Initiate a graceful leadership transfer, tolerating expected failure modes.
     *
     * [LeadershipTransferException] means the transfer timed out (the target didn't win
     * fast enough). [NotLeaderException] means the leader stepped down before the call.
     * In both cases the cluster will elect a new leader; [sim.awaitLeader] after this
     * call confirms it.
     */
    private suspend fun transferLeadershipBestEffort(sim: MultiNodeRaftSim, from: NodeId, to: NodeId) {
        val fromNode = sim.nodes[from] ?: return
        try {
            fromNode.transferLeadership(to)
        } catch (_: LeadershipTransferException) {
            // Transfer timed out — the cluster will self-elect.
        } catch (_: NotLeaderException) {
            // Already stepped down.
        }
    }

    /**
     * Replay user entries on [node] from index 1 up to [upToIndex] inclusive.
     *
     * [RaftNode.committedFrom] withholds internal no-ops; every [Committed.Entry]
     * here is a user-data entry. [transformWhile] stops after emitting [upToIndex].
     *
     * The caller must have confirmed [upToIndex] is committed (via [MultiNodeRaftSim.awaitCommit])
     * before calling — otherwise the flow hangs waiting for an entry that never arrives.
     */
    private suspend fun replayEntries(node: RaftNode, upToIndex: Long): List<LogEntry> =
        node.committedFrom(1L)
            .filterIsInstance<Committed.Entry>()
            .transformWhile { item ->
                emit(item.entry)
                item.entry.index < upToIndex
            }
            .toList()

    /**
     * Apply [ClientSessionTable] to [entries] and assert [command] appears exactly once.
     *
     * Two entries may carry the same [us.tractat.kuilt.raft.DedupKey] when the new leader's
     * cache is cold and a client retries a proposal that already committed. The table's
     * [ClientSessionTable.shouldApply] call is the consumer-side dedup fence.
     */
    private fun assertExactlyOnce(command: ByteArray, entries: List<LogEntry>, context: String) {
        val table = ClientSessionTable()
        val count = entries.count { entry ->
            table.shouldApply(entry.dedupKey) && entry.command.contentEquals(command)
        }
        assertEquals(1, count, "$context: expected command exactly once, got $count")
    }
}

// ── Private fixture ───────────────────────────────────────────────────────────

/** A [Tag] backed by a [NodeId] — a voter's identity as a [ServerClusterReconnect] endpoint. */
private data class NodeId2Tag(val nodeId: NodeId) : Tag {
    override val displayName: String = nodeId.value
    override val peerKey: String = nodeId.value
}
