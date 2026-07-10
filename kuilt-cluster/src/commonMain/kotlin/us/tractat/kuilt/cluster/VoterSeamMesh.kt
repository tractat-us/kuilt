package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode

/**
 * Wire M voter [us.tractat.kuilt.raft.RaftNode]s over **real [Seam]s** into a [VoterMesh].
 *
 * This is the transport-agnostic sibling of `serverCluster`'s in-process
 * `buildVoterChannelMesh`: where that path bonds voters over in-JVM
 * `Channel<RaftEnvelope>`s (so "3 servers" are 3 nodes in one process), this path takes one
 * already-woven [Seam] per voter — each a fully-connected view of the other M-1 servers — and
 * wraps it in [SeamRaftTransport]. The voters can therefore live in **separate processes on
 * separate machines**: the only thing crossing between them is the real fabric each seam rides.
 *
 * It says nothing about *how* the seams are formed. A deployment supplies real WebSocket/TCP
 * seams (e.g. a `meshSeam` of server-to-server connections — see `voterMeshOverWebSockets` on
 * JVM/Android); a test supplies loopback ones. That separation is deliberate: which fabric bonds
 * the servers, and how they discover and dial each other, is a deployment concern, not a property
 * of the consensus core.
 *
 * ## Identity alignment
 *
 * Each entry's [NodeId] key **must** equal its seam's `selfId` mapped through [SeamRaftTransport]
 * (`NodeId(seam.selfId.value)`) — that is how the Raft engine names itself and its peers on the
 * wire. A mismatch is a wiring bug, so it fails fast rather than silently mis-addressing votes.
 *
 * ## Lifecycle
 *
 * Every voter node runs in a child scope of a mesh scope derived from the receiver, mirroring
 * `buildVoterChannelMesh`. [VoterMesh.close] cancels the mesh scope, stopping all voter coroutines.
 * The caller owns the seams' own lifecycles (this function neither closes nor tears them down).
 *
 * @param voterSeams One woven [Seam] per voter, keyed by that voter's [NodeId]. Non-empty; the
 *   key set becomes the [ClusterConfig.voters] set. An odd count is recommended for clean quorum.
 * @param raftConfig Raft timing and election RNG. **Required** — no default; seed
 *   [RaftConfig.random] per deployment so election timeouts break symmetry.
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 */
public fun CoroutineScope.voterMeshOverSeams(
    voterSeams: Map<NodeId, Seam>,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): VoterMesh {
    require(voterSeams.isNotEmpty()) { "voterSeams must be non-empty" }
    val clusterConfig = ClusterConfig(voters = voterSeams.keys.toSet())
    val meshScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

    val voterNodes = voterSeams.mapValues { (id, seam) ->
        val transport = SeamRaftTransport(seam)
        require(transport.selfId == id) {
            "voter seam selfId ${transport.selfId} must equal its NodeId key $id"
        }
        // One child scope per voter so a single node's teardown is independent — matches
        // buildVoterChannelMesh. All child scopes are children of meshScope, which VoterMesh.close cancels.
        val childScope = CoroutineScope(meshScope.coroutineContext + Job(meshScope.coroutineContext[Job]))
        childScope.raftNode(clusterConfig, transport, storageFactory(id), raftConfig)
    }
    return VoterMesh(voterNodes = voterNodes, scope = meshScope)
}
