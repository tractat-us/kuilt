package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole

/**
 * An M-voter Raft mesh — a complete-graph (K_M) cluster of voter [RaftNode]s.
 *
 * [VoterMesh] is the common base for [ServerCluster] (JVM/Android relay facade).
 * In tests it is instantiated directly via [voterMeshFromNodes] without any real
 * network sockets, driven under virtual time by [us.tractat.kuilt.raft.test.MultiNodeRaftSim].
 *
 * ## Voter nodes
 *
 * [voterNodes] holds the live node map. Node lifetimes are tied to [scope]: cancelling
 * the scope stops all voter coroutines.
 *
 * ## Committed stream
 *
 * [committed] is a convenience accessor over the first voter — suitable for tests and
 * single-consumer deployments. In multi-consumer scenarios, collect directly from
 * [voterNodes].
 *
 * @see voterMeshFromNodes for the test construction path.
 * @see us.tractat.kuilt.cluster.ServerCluster for the JVM/Android relay facade.
 */
public class VoterMesh internal constructor(
    /** Live voter nodes — keys are [NodeId]s. */
    public val voterNodes: Map<NodeId, RaftNode>,
    internal val scope: CoroutineScope,
) {
    /**
     * The committed log stream from the first voter — convenience for single-consumer scenarios.
     *
     * For multi-consumer or leader-pin scenarios, read directly from [voterNodes].
     */
    public val committed: Flow<Committed>
        get() = voterNodes.values.first().committed

    /**
     * Suspend until a voter in the mesh holds [RaftRole.Leader]; return it.
     *
     * Races all voters' [RaftNode.role] StateFlows: the first voter to emit [RaftRole.Leader]
     * wins and is returned. Under virtual time (test dispatcher) the test scheduler drives this;
     * in tests prefer [us.tractat.kuilt.raft.test.MultiNodeRaftSim.awaitLeader] for bounded
     * await with election-thrash detection.
     */
    public suspend fun awaitLeader(): RaftNode {
        // Fast path: a leader is already elected.
        voterNodes.values.firstOrNull { it.role.value is RaftRole.Leader }?.let { return it }
        // Slow path: race all voters' role flows — channelFlow fans out over each voter and
        // sends the node reference the moment its role becomes Leader. .first() takes the
        // earliest winner and cancels the remaining coroutines.
        return channelFlow {
            voterNodes.values.forEach { node ->
                launch {
                    node.role.first { it is RaftRole.Leader }
                    send(node)
                }
            }
        }.first()
    }

    /** Cancel the owning scope — stops all voter coroutines. */
    public fun close() {
        scope.cancel()
    }
}

/**
 * Construct a [VoterMesh] from pre-built voter nodes.
 *
 * Intended for tests that wire nodes via [us.tractat.kuilt.raft.test.MultiNodeRaftSim]
 * (virtual time) and want to verify [VoterMesh] behaviour without real transports.
 *
 * @param voterNodes Pre-wired voter nodes keyed by [NodeId].
 * @param scope Scope for [VoterMesh.close].
 */
public fun voterMeshFromNodes(
    voterNodes: Map<NodeId, RaftNode>,
    scope: CoroutineScope,
): VoterMesh = VoterMesh(voterNodes = voterNodes, scope = scope)
