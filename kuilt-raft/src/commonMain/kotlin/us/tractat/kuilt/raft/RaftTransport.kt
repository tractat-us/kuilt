package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public data class RaftEnvelope(val from: NodeId, val bytes: ByteArray)

public interface RaftTransport {
    public val selfId: NodeId
    public val peers: StateFlow<Set<NodeId>>
    public suspend fun sendTo(peer: NodeId, message: ByteArray)
    public val incoming: Flow<RaftEnvelope>
}
