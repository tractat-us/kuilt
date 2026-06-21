@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_BASE_CONFIG
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_SEED
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * M=1 exactly-once happy path (epic #485, S1c-1).
 *
 * Proves that a learner-client's retry with the same `requestId` is deduplicated by
 * [ClientSessionTable]: the committed log contains the command exactly once, and the
 * retry coalesces to the original committed entry (same log index).
 *
 * Both the voter and the learner client start with the same [ClusterConfig]
 * (`voters = {v1}`, `learners = {client}`) so the voter replicates to the client from
 * the first heartbeat — no dynamic membership change required.
 *
 * Virtual time is driven by [StandardTestDispatcher]; no `advanceUntilIdle()`.
 */
class ExactlyOnceHappyPathTest {

    /**
     * Single-voter cluster (`v1`) with one learner client.
     *
     * Flow:
     * 1. `v1` elects itself leader (single-voter — no election contention).
     * 2. Client proposes `"action:A"` with `requestId = 1`.
     * 3. The leader commits the entry and replicates to the learner; `awaitCommit` confirms both.
     * 4. Client retries with the same `requestId = 1` — the leader's dedup cache coalesces
     *    the retry to the original committed entry (same index returned).
     * 5. [ClientSessionTable.shouldApply] on the voter's replay sees the command exactly once.
     */
    @Test
    fun `m1 single server - retry with same requestId is deduplicated`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val voterId = NodeId("v1")
            val clientId = NodeId("client")
            val config = ClusterConfig(voters = setOf(voterId), learners = setOf(clientId))

            val sim = MultiNodeRaftSim(
                nodeIds = listOf(voterId),
                scope = this,
                nodeScope = backgroundScope,
                nodeFactory = voterAndLearnerFactory(config),
            )

            // Wire the learner client into the same network and background scope.
            val clientScope = CoroutineScope(
                backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]),
            )
            val clientNode = clientScope.raftNode(
                clusterConfig = config,
                transport = sim.network.transport(clientId),
                storage = InMemoryRaftStorage(),
                raftConfig = MULTI_NODE_SIM_BASE_CONFIG.copy(
                    random = Random(MULTI_NODE_SIM_SEED + sim.nodeIds.size),
                ),
                identity = ClientIdentity.Durable(ClientId(clientId.value)),
            )

            val leader = sim.awaitLeader()

            val command = "action:A".encodeToByteArray()
            val entry = clientNode.propose(command, requestId = 1L)

            sim.awaitCommit(index = entry.index, on = listOf(voterId))

            val entriesOnVoter = replayEntries(leader, upToIndex = entry.index)
            assertExactlyOnce(command, entriesOnVoter, "voter after initial propose")

            val retried = clientNode.propose(command, requestId = 1L)
            assertEquals(
                entry.index,
                retried.index,
                "retry must coalesce to the original committed entry",
            )

            val entriesAfterRetry = replayEntries(leader, upToIndex = retried.index)
            assertExactlyOnce(command, entriesAfterRetry, "voter after retry")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Replay committed user entries on [node] from index 1 up to [upToIndex] inclusive.
     *
     * No-ops are withheld from `committedFrom`, so every [Committed.Entry] here is application data.
     * Call only after `sim.awaitCommit(upToIndex)` has already confirmed that index is committed.
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
     * Assert [command] appears exactly once (by content) when applied through [ClientSessionTable].
     * Filters by both dedup guard and byte-content equality so unrelated entries are ignored.
     */
    private fun assertExactlyOnce(command: ByteArray, entries: List<LogEntry>, context: String) {
        val table = ClientSessionTable()
        val count = entries.count { entry ->
            table.shouldApply(entry.dedupKey) && entry.command.contentEquals(command)
        }
        assertEquals(1, count, "$context: command must appear exactly once, got $count")
    }
}

// ── Private factory ───────────────────────────────────────────────────────────

/**
 * Node factory that gives every voter the full learner [config] so the voter replicates to
 * the learner client from the first heartbeat. Per-node seeded [Random] for distinct election
 * timeouts; [ClientId] bound to node id for stable dedup identity.
 */
private fun voterAndLearnerFactory(
    config: ClusterConfig,
): (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode = { id, transport, storage, scope ->
    val nodeIndex = config.allMembers.sortedBy { it.value }.indexOf(id).toLong()
    val nodeConfig = MULTI_NODE_SIM_BASE_CONFIG.copy(random = Random(MULTI_NODE_SIM_SEED + nodeIndex))
    scope.raftNode(
        clusterConfig = config,
        transport = transport,
        storage = storage,
        raftConfig = nodeConfig,
        identity = ClientIdentity.Durable(ClientId(id.value)),
    )
}
