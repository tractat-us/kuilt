package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.assertTrue

class RaftSimulation(
    val nodeIds: List<NodeId>,
    private val scope: CoroutineScope,
    private val raftConfig: RaftConfig = RaftConfig(),
    /**
     * Scope used to create per-node child scopes. In tests, pass [TestScope.backgroundScope]
     * so that the node coroutines (infinite heartbeat/election loops) are cancelled when the
     * test body finishes without causing [UncompletedCoroutinesError].
     */
    private val nodeScope: CoroutineScope = scope,
    private val nodeFactory: (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode,
) {
    val network = InMemoryRaftNetwork()
    val storages: Map<NodeId, InMemoryRaftStorage> = nodeIds.associateWith { InMemoryRaftStorage() }
    private val scopes: MutableMap<NodeId, CoroutineScope> = mutableMapOf()
    val nodes: MutableMap<NodeId, RaftNode> = mutableMapOf()

    init { nodeIds.forEach { start(it) } }

    private fun start(id: NodeId) {
        val child = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
        scopes[id] = child
        nodes[id] = nodeFactory(id, network.transport(id), storages.getValue(id), child)
    }

    fun crash(id: NodeId) { scopes[id]?.cancel(); scopes.remove(id); nodes.remove(id) }
    fun restart(id: NodeId) { start(id) }
    fun partition(a: Set<NodeId>, b: Set<NodeId>) = network.partition(a, b)
    fun heal() = network.heal()
    fun dropLink(from: NodeId, to: NodeId) = network.dropLink(from, to)

    suspend fun checkInvariants() {
        // 1. Election Safety: at most one leader
        val leaders = nodes.values.filter { it.role.value is RaftRole.Leader }
        assertTrue(leaders.size <= 1, "Election Safety violated: ${leaders.size} leaders simultaneously")

        // 2. State Machine Safety: no two nodes have different commands at the same committed index
        val minCommit = nodes.values.minOfOrNull { it.commitIndex.value } ?: 0L
        if (minCommit > 0L) {
            val snapshots = nodes.entries.associate { (id, _) ->
                id to storages.getValue(id).entries(1L)
            }
            val reference = snapshots.values.firstOrNull() ?: return
            snapshots.values.drop(1).forEach { other ->
                (1..minCommit).forEach { idx ->
                    val ref = reference.firstOrNull { it.index == idx }
                    val oth = other.firstOrNull { it.index == idx }
                    if (ref != null && oth != null) {
                        assertTrue(ref.command.contentEquals(oth.command),
                            "State Machine Safety violated at index $idx")
                    }
                }
            }
        }
    }

    fun leader(): RaftNode? = nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
    fun followers(): List<RaftNode> = nodes.values.filter { it.role.value is RaftRole.Follower }
}
