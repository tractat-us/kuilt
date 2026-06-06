package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A raw message from another cluster member, as received by the transport.
 *
 * @param from The sender's [NodeId].
 * @param bytes The serialised Raft RPC payload. Opaque to callers of
 *   [RaftTransport]; the engine deserialises it internally.
 */
public data class RaftEnvelope(val from: NodeId, val bytes: ByteArray)

/**
 * The messaging seam between the Raft engine and any underlying network layer.
 *
 * [RaftTransport] decouples the engine from the wire — the same engine code
 * runs over WebSocket ([SeamRaftTransport]), in-process channels (tests), or
 * any other point-to-point fabric. Implement this interface to plug in a
 * custom transport.
 *
 * **Reachability vs. membership.** [peers] reflects which nodes the transport
 * can currently reach, not which nodes are configured voters. A node may be a
 * configured voter but momentarily absent from [peers] (network partition),
 * or absent from the cluster config entirely (unknown peer). The engine
 * handles both cases.
 *
 * **Single collection.** [incoming] is a hot flow; collect it once. The
 * engine calls [RaftTransport.incoming] exactly once per [RaftNode] lifetime.
 */
public interface RaftTransport {
    /** This node's own stable identifier. Must match its entry in [ClusterConfig]. */
    public val selfId: NodeId

    /**
     * The set of peers currently reachable via this transport.
     *
     * Updated as connections open and close. The engine uses this to determine
     * which followers it can heartbeat and replicate to.
     */
    public val peers: StateFlow<Set<NodeId>>

    /**
     * Sends [message] to [peer].
     *
     * Fire-and-forget: the engine does not await acknowledgement at the
     * transport layer (Raft's own AppendEntries/RequestVote RPCs carry
     * success/failure in-band). May silently drop if [peer] is unreachable.
     */
    public suspend fun sendTo(peer: NodeId, message: ByteArray)

    /**
     * Hot flow of inbound [RaftEnvelope]s from other cluster members.
     *
     * The engine collects this flow for the node's entire lifetime. Do not
     * collect it from application code.
     */
    public val incoming: Flow<RaftEnvelope>

    /**
     * The maximum number of bytes a single [sendTo] payload can carry, or `null` if effectively
     * unbounded (e.g. WebSocket). Fabrics with hard framing limits (e.g. ~32 KiB on some LAN radios)
     * return that limit so kuilt-raft can size InstallSnapshot chunks to fit. Defaulted — existing
     * transports need no change.
     */
    public val maxPayloadBytes: Int? get() = null
}
