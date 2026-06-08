package us.tractat.kuilt.raft

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class InMemoryRaftNetwork(
    /**
     * The per-message payload limit reported to the engine via [RaftTransport.maxPayloadBytes],
     * or `null` (the default) for an effectively unbounded transport. A tiny value forces
     * InstallSnapshot to span many chunks so the chunking path is exercised in tests.
     */
    private val maxPayloadBytes: Int? = null,
) {
    private val channels = mutableMapOf<NodeId, Channel<RaftEnvelope>>()
    private val _peers = MutableStateFlow<Set<NodeId>>(emptySet())
    private val dropped = mutableSetOf<Pair<NodeId, NodeId>>()

    fun transport(id: NodeId): RaftTransport {
        val ch = Channel<RaftEnvelope>(Channel.UNLIMITED)
        channels[id] = ch
        _peers.update { it + id }
        val limit = maxPayloadBytes
        return object : RaftTransport {
            override val selfId = id
            override val peers: StateFlow<Set<NodeId>> = _peers.asStateFlow()
            override val incoming: Flow<RaftEnvelope> = ch.receiveAsFlow()
            override val maxPayloadBytes: Int? = limit
            override suspend fun sendTo(peer: NodeId, message: ByteArray) {
                if ((id to peer) !in dropped) channels[peer]?.send(RaftEnvelope(id, message))
            }
        }
    }

    fun partition(a: Set<NodeId>, b: Set<NodeId>) {
        a.forEach { from -> b.forEach { to -> dropped += from to to; dropped += to to from } }
    }

    fun heal() { dropped.clear() }
    fun dropLink(from: NodeId, to: NodeId) { dropped += from to to }

    /** Inject [bytes] from [from] directly into [to]'s channel, bypassing all partition/drop rules. */
    suspend fun deliver(from: NodeId, to: NodeId, bytes: ByteArray) {
        channels[to]?.send(RaftEnvelope(from, bytes))
    }
}
