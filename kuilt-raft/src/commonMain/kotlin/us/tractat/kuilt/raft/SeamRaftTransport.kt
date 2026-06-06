package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * Adapts a kuilt-core [Seam] to the [RaftTransport] interface.
 *
 * This is the standard bridge between the kuilt fabric layer and the Raft
 * consensus layer. It maps [PeerId] ↔ [NodeId] by their string values and
 * forwards sends and receives through the underlying [Seam].
 *
 * **Peer reachability.** [peers] reflects [Seam.peers] — nodes connected on
 * the fabric, not necessarily all configured voters. A voter absent from the
 * [Seam] is simply unreachable at the transport layer; the Raft engine handles
 * this as an ordinary network partition.
 *
 * **Incoming filtering.** Swatches with a `null` sender (e.g. broadcast
 * frames from the fabric layer itself) are silently dropped; only frames with
 * a known sender are forwarded as [RaftEnvelope]s.
 *
 * This class is the only place in `kuilt-raft` that imports from `kuilt-core`.
 * All other Raft types are transport-agnostic.
 */
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
