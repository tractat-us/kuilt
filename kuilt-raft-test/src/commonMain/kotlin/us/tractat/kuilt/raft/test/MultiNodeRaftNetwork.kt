package us.tractat.kuilt.raft.test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport

/**
 * An in-process [RaftTransport] network backed by [Channel]s — the published equivalent of the
 * `:kuilt-raft` private test network. Obtain a per-node transport via [transport]; control
 * network partitions via [partition], [dropLink], and [heal].
 *
 * Designed for use with [MultiNodeRaftSim]: call [MultiNodeRaftSim.network] to reach this instance
 * rather than constructing it directly unless you need manual wiring.
 *
 * ## Partition control
 *
 * - [partition] — drop all messages in both directions between two sets of nodes.
 * - [dropLink] — drop messages from one node to another (unidirectional).
 * - [heal] — restore all links (clear every drop rule).
 *
 * Dropped messages are silently discarded; no error is delivered to the sender. This mirrors
 * real network behaviour where a partitioned peer is simply unreachable.
 *
 * @param maxPayloadBytes Reported to the engine via [RaftTransport.maxPayloadBytes]. `null`
 *   (default) means unbounded. A small value forces [us.tractat.kuilt.raft.Committed.Install]
 *   snapshot chunking to span multiple messages — useful for exercising the chunk-reassembly path.
 */
public class MultiNodeRaftNetwork(
    private val maxPayloadBytes: Int? = null,
) {
    private val channels = mutableMapOf<NodeId, Channel<RaftEnvelope>>()
    private val _peers = MutableStateFlow<Set<NodeId>>(emptySet())
    private val dropped = mutableSetOf<Pair<NodeId, NodeId>>()

    /**
     * Return a [RaftTransport] for [id], registering it with the network so other nodes can send
     * to it. Call once per node before constructing [us.tractat.kuilt.raft.RaftNode].
     */
    public fun transport(id: NodeId): RaftTransport {
        val ch = Channel<RaftEnvelope>(Channel.UNLIMITED)
        channels[id] = ch
        _peers.update { it + id }
        val limit = maxPayloadBytes
        return object : RaftTransport {
            override val selfId: NodeId = id
            override val peers: StateFlow<Set<NodeId>> = _peers.asStateFlow()
            override val incoming: Flow<RaftEnvelope> = ch.receiveAsFlow()
            override val maxPayloadBytes: Int? = limit
            override suspend fun sendTo(peer: NodeId, message: ByteArray) {
                if ((id to peer) !in dropped) channels[peer]?.send(RaftEnvelope(id, message))
            }
        }
    }

    /**
     * Partition nodes into two groups — messages in both directions between any node in [a] and
     * any node in [b] are silently dropped.
     */
    public fun partition(a: Set<NodeId>, b: Set<NodeId>) {
        a.forEach { from -> b.forEach { to -> dropped += from to to; dropped += to to from } }
    }

    /** Drop all messages from [from] to [to] (unidirectional). */
    public fun dropLink(from: NodeId, to: NodeId) { dropped += from to to }

    /** Restore all links — clear every drop and partition rule. */
    public fun heal() { dropped.clear() }

    /**
     * Inject [bytes] from [from] directly into [to]'s channel, bypassing all partition and drop
     * rules. Used in edge-case tests that need to deliver a specific crafted message regardless of
     * current partition state.
     */
    public suspend fun deliver(from: NodeId, to: NodeId, bytes: ByteArray) {
        channels[to]?.send(RaftEnvelope(from, bytes))
    }
}
