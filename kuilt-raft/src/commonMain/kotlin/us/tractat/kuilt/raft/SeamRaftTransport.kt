@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.raft

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam

private val log = KotlinLogging.logger("us.tractat.kuilt.raft.SeamRaftTransport")

private fun Set<PeerId>.toNodeIds(): Set<NodeId> = mapTo(mutableSetOf()) { NodeId(it.value) }

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
        override val value: Set<NodeId> get() = seam.peers.value.toNodeIds()
        override val replayCache: List<Set<NodeId>> get() = listOf(value)

        // Collect the source StateFlow directly and transform inside the lambda.
        // seam.peers.collect returns Nothing (a StateFlow never completes), so the
        // override's Nothing return type is satisfied without a cast. Mapping with
        // .map{} first would downgrade to a Flow whose collect returns Unit.
        override suspend fun collect(collector: FlowCollector<Set<NodeId>>): Nothing =
            seam.peers.collect { set -> collector.emit(set.toNodeIds()) }
    }

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        try {
            seam.sendTo(PeerId(peer.value), message)
        } catch (_: PeerNotConnected) {
            // Contract (RaftTransport.sendTo): "may silently drop if peer is unreachable."
            // A voter absent from the Seam is an ordinary partition; Raft retries on the next
            // replication/heartbeat round. Swallowing here keeps a dropped follower from
            // crashing the engine. PeerNotConnected is an IllegalStateException, never a
            // CancellationException, so structured-concurrency cancellation still propagates.
        } catch (tooLarge: PayloadTooLarge) {
            // A seam that publishes a payload budget refuses an over-budget frame while Woven
            // (#2047) — a refusal independent of peer reachability, and one this transport cannot
            // let escape: `RaftEngine.send` invokes this **unguarded**, so a throw here fails the
            // engine coroutine rather than one message.
            //
            // Dropped, therefore, but loudly: unlike a partition this is a misconfiguration, not
            // weather. The engine will retry the frame forever and never make progress on that
            // peer, so the log line is the only thing that names why. The structural fix is for
            // this transport to publish `maxPayloadBytes` so the engine chunks to fit — a
            // consensus-behaviour change, tracked separately rather than smuggled in here.
            log.warn {
                "raft.send.drop self=${selfId.value} to=${peer.value} " +
                    "reason=payload-over-budget ${tooLarge.message}"
            }
        }
    }

    override val incoming: Flow<RaftEnvelope> =
        seam.incoming
            .filter { it.sender != null }
            .map { RaftEnvelope(NodeId(requireNotNull(it.sender) { "sender absent after non-null filter" }.value), it.toByteArray()) }
}
