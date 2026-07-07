@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.time.Duration

class InMemoryRaftNetwork(
    /**
     * The per-message payload limit reported to the engine via [RaftTransport.maxPayloadBytes],
     * or `null` (the default) for an effectively unbounded transport. A tiny value forces
     * InstallSnapshot to span many chunks so the chunking path is exercised in tests.
     */
    private val maxPayloadBytes: Int? = null,
    /**
     * Scope hosting delayed deliveries for links given a [setLinkLatency] — pass the test's
     * `backgroundScope` (via [RaftSimulation]'s `nodeScope`) so in-flight messages are dropped at
     * teardown. Required only when [setLinkLatency] is used; zero-latency links never touch it.
     */
    private val deliveryScope: CoroutineScope? = null,
) {
    private val channels = mutableMapOf<NodeId, Channel<RaftEnvelope>>()
    private val _peers = MutableStateFlow<Set<NodeId>>(emptySet())
    private val dropped = mutableSetOf<Pair<NodeId, NodeId>>()
    private val latencies = mutableMapOf<Pair<NodeId, NodeId>, Duration>()

    /** One decoded, in-order record of a send attempted on the network — see [recording] / [sent]. */
    internal data class Sent(val from: NodeId, val to: NodeId, val message: RaftMessage)

    /**
     * Opt-in message tap (default off, zero cost to every other test). When `true`, each attempted
     * send — *before* the drop filter, so it captures the sender's decision, not delivery — is decoded
     * and appended to [sent]. Used by the §3.10 leadership-transfer tests to observe *when* a leader
     * emits a `TimeoutNow` relative to the target catching up (issue #1229), a decision no external
     * cluster-state assertion can see under the deterministic in-order harness.
     */
    var recording: Boolean = false

    /** In-order log of decoded sends captured while [recording] is on. */
    internal val sent: MutableList<Sent> = mutableListOf()

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
                if (recording) sent += Sent(id, peer, Cbor.decodeFromByteArray(RaftMessage.serializer(), message))
                if ((id to peer) in dropped) return
                val latency = latencies[id to peer]
                if (latency == null) {
                    channels[peer]?.send(RaftEnvelope(id, message))
                } else {
                    // Delayed delivery off the sender's actor loop, so latency never stalls the
                    // engine. Constant per-link latency + FIFO virtual-time scheduling preserves
                    // per-link message order.
                    checkNotNull(deliveryScope).launch {
                        delay(latency)
                        channels[peer]?.send(RaftEnvelope(id, message))
                    }
                }
            }
        }
    }

    fun partition(a: Set<NodeId>, b: Set<NodeId>) {
        a.forEach { from -> b.forEach { to -> dropped += from to to; dropped += to to from } }
    }

    fun heal() { dropped.clear() }
    fun dropLink(from: NodeId, to: NodeId) { dropped += from to to }

    /**
     * Give the directed link `from → to` a one-way delivery [latency] (virtual time). Deliveries are
     * launched on [deliveryScope] (required — fails fast here if absent) so the sender's actor loop
     * never stalls; constant latency preserves per-link FIFO order under [kotlinx.coroutines.test.StandardTestDispatcher].
     * An RTT > 0 makes a chunked InstallSnapshot's one-chunk-in-flight ack cycle consume virtual
     * time, so a multi-chunk transfer genuinely spans heartbeat intervals (issue #1226).
     */
    fun setLinkLatency(from: NodeId, to: NodeId, latency: Duration) {
        checkNotNull(deliveryScope) { "setLinkLatency requires a deliveryScope" }
        latencies[from to to] = latency
    }

    /** Inject [bytes] from [from] directly into [to]'s channel, bypassing all partition/drop rules. */
    suspend fun deliver(from: NodeId, to: NodeId, bytes: ByteArray) {
        channels[to]?.send(RaftEnvelope(from, bytes))
    }
}
