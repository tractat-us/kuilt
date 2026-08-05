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
 * This class is the only place in `kuilt-raft` that names a `kuilt-core` *fabric* type — [Seam],
 * [PeerId], and the errors they raise. All other Raft types are transport-agnostic; the handful of
 * `kuilt-core` symbols they do reach for are utilities (`runCatchingCancellable`,
 * `checkNotUnderTestDispatcher`) and [PayloadTooLarge], which is the contract the fabric refuses by
 * and so travels with the budget rather than with the transport.
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

    /**
     * The seam's budget, republished **unchanged** (#2069).
     *
     * This transport hands `message` to [Seam.sendTo] exactly as the engine minted it and adds no
     * bytes of its own, so there is nothing to subtract. Contrast `RoutedRaftTransport`, which wraps
     * every frame in a `RaftRelay` envelope and therefore reports the delegate's limit less its own
     * `headerBudget` — a decorator subtracts what it costs, and this one costs nothing.
     *
     * A `get()` rather than a `val` initialiser because [Seam.maxPayloadBytes] is "a reading, not a
     * lease": a mesh reports the minimum across its live links, so a peer attaching over a tighter
     * transport lowers it. A snapshot taken at construction would go stale the first time the fabric
     * changed shape.
     *
     * The engine spends this on two things — chunking an `InstallSnapshot`, and refusing an
     * over-budget `propose` before it can enter the log (`RaftEngine.checkProposeFitsTransport`).
     */
    override val maxPayloadBytes: Int? get() = seam.maxPayloadBytes

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
            // (#2047) — a refusal independent of peer reachability. This catch is PERMANENT, not a
            // stopgap awaiting the propose-time bound of #2069, for three reasons:
            //
            //  1. `RaftTransport.sendTo` is best-effort by contract ("may silently drop"), and
            //     `RaftEngine.send` invokes it **unguarded** — a throw here fails the engine
            //     coroutine, not one message.
            //  2. The budget can move DOWN after an entry is already in the log. `maxPayloadBytes`
            //     is a reading, not a lease: a mesh peer attaching over a tighter link shrinks it.
            //     Nothing checked at propose time can close that race.
            //  3. The engine's three sizing gates — the propose bound, `boundedBatch` and
            //     `chunkBytes` (#2069/#2150) — all spend `HEADER_BUDGET` as a *reserve* for the
            //     envelope rather than measuring it. A consumer-supplied value large enough to
            //     exhaust that reserve, such as a very long `ClientId` (#2156), still arrives here.
            //
            // Dropped, therefore, but loudly: unlike a partition this is a misconfiguration, not
            // weather. The engine will retry the frame forever and never make progress on that
            // peer, so the log line is the only thing that names why.
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
