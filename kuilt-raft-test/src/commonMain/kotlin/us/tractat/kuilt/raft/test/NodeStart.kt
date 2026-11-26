package us.tractat.kuilt.raft.test

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTransport
import kotlin.random.Random

/**
 * One start of one node in a [MultiNodeRaftSim]: everything a `nodeFactory` needs to build the
 * [RaftNode] for it. The sim makes one at construction for each node, and another on every
 * [MultiNodeRaftSim.restart].
 *
 * The point of the type is [random]. A restart is a new process, so it needs a new seed, and the
 * sim, not the factory, picks it. A factory that seeds [RaftConfig.random] from [random] cannot
 * bring a restarted [us.tractat.kuilt.raft.ClientIdentity.Auto] node back under its old client id,
 * because it never chooses a seed at all (#3425, #3659):
 *
 * ```kotlin
 * MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope) { start ->
 *     start.scope.raftNode(cluster, start.transport, start.storage, baseConfig.copy(random = start.random))
 * }
 * ```
 *
 * Only the sim makes one, so its constructor is internal.
 *
 * @property id The node being started.
 * @property transport The node's transport on [MultiNodeRaftSim.network].
 * @property storage The node's durable storage, the same [MultiNodeRaftSim.storages] entry on every
 *   start, so a restart boots from what the last incarnation wrote.
 * @property scope This start's own scope, a child of the sim's node scope. [MultiNodeRaftSim.crash]
 *   cancels it.
 * @property random A fresh [Random] seeded for this start of this node: the same seed the sim's
 *   default factory gives it, a pure function of the sim's `baseSeed`, the node's index in
 *   [MultiNodeRaftSim.nodeIds] and [start]. A node built from it draws exactly what a
 *   default-factory node would. It is a new instance on every start; anything else a factory draws
 *   from it moves the draws the node sees after.
 * @property start Which start of this node this is: `0` at construction, `k` on its `k`-th restart.
 */
public class NodeStart internal constructor(
    public val id: NodeId,
    public val transport: RaftTransport,
    public val storage: RaftStorage,
    public val scope: CoroutineScope,
    public val random: Random,
    public val start: Int,
) {
    override fun toString(): String = "NodeStart(id=$id, start=$start)"
}
