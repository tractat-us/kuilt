package us.tractat.kuilt.examples

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport

/**
 * A no-peer inner [RaftTransport] for a client's `playerRelayTransport`.
 *
 * With no direct peers, the player relay transport wraps **every** Raft send as a
 * `RaftRelay(dest = target)` addressed to its single relay server — the production
 * client dialect the `RaftRelayHub` on the server decodes. Used by the hand-wired
 * `clusterClientWithNode` E2E tests so they speak the same dialect as the production
 * `clusterClient` path.
 */
internal fun noPeerInnerTransport(id: NodeId): RaftTransport = object : RaftTransport {
    override val selfId: NodeId = id
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
    override val incoming: Flow<RaftEnvelope> = emptyFlow()
    override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
}
