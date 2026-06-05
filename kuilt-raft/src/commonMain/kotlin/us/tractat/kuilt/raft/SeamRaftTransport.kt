package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/** Adapts a [Seam] to the [RaftTransport] interface. */
public class SeamRaftTransport(private val seam: Seam) : RaftTransport {

    override val selfId: NodeId get() = NodeId(seam.selfId.value)

    override val peers: StateFlow<Set<NodeId>> = object : StateFlow<Set<NodeId>> {
        override val value: Set<NodeId> get() = seam.peers.value.mapTo(mutableSetOf()) { NodeId(it.value) }
        override val replayCache: List<Set<NodeId>> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Set<NodeId>>): Nothing =
            seam.peers.map { set -> set.mapTo(mutableSetOf()) { NodeId(it.value) } }.collect(collector) as Nothing
    }

    override suspend fun sendTo(peer: NodeId, message: ByteArray): Unit =
        seam.sendTo(PeerId(peer.value), message)

    override val incoming: Flow<RaftEnvelope> =
        seam.incoming
            .filter { it.sender != null }
            .map { RaftEnvelope(NodeId(it.sender!!.value), it.payload) }
}
