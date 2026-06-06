@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Runs any [Quilted] CRDT live over a [Seam], providing eventually-consistent
 * multi-peer replication via a simple delta-propagation protocol.
 *
 * ## Protocol
 * - **[apply]** applies a local mutation, updates [state], and broadcasts a [ReplicatorMessage.Delta]
 *   to all current peers. Each delta is tagged with a monotonic [seq].
 * - On receiving a [ReplicatorMessage.Delta], the state is joined and an [ReplicatorMessage.Ack]
 *   is sent back to the original sender.
 * - On receiving an [ReplicatorMessage.Ack], the acker's progress is recorded; once every
 *   known peer has acked through a seq, all deltas at or below that seq are GC'd.
 * - On first contact with a new peer, a [ReplicatorMessage.FullState] is sent so the
 *   late joiner converges immediately without waiting for a delta replay.
 *
 * ## Deferred (Rung 12b)
 * Gap detection, per-neighbor receive-seq tracking, peer eviction, and `Resend`.
 *
 * @param replica this peer's [ReplicaId].
 * @param seam the [Seam] to ride. Collect [Seam.incoming] exactly once — this class
 *   takes sole ownership of the incoming stream.
 * @param initial the starting state (typically the CRDT's zero/empty value).
 * @param messageSerializer a [KSerializer] for [ReplicatorMessage]`<S>`, obtained via
 *   `ReplicatorMessage.serializer(stateSerializer)`.
 * @param scope the [CoroutineScope] to launch background coroutines under. In tests, pass
 *   `backgroundScope` from [kotlinx.coroutines.test.TestScope] so infinite-running collectors
 *   are cancelled cleanly at test end without raising [kotlinx.coroutines.test.UncompletedCoroutinesError].
 */
public class SeamReplicator<S : Quilted<S>>(
    public val replica: ReplicaId,
    private val seam: Seam,
    initial: S,
    private val messageSerializer: KSerializer<ReplicatorMessage<S>>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initial)
    public val state: StateFlow<S> = _state.asStateFlow()

    private var nextSeq: Long = 0L
    private val pendingDeltas: MutableMap<Long, S> = mutableMapOf()
    private val knownPeers: MutableSet<PeerId> = mutableSetOf()

    /** Per-peer acked-through-seq for MY deltas: ackedThrough[peer] = highest seq B has acked. */
    private val ackedThrough: MutableMap<PeerId, Long> = mutableMapOf()

    /** Exposed internally so tests can observe GC behaviour. */
    internal val pendingDeltasForTest: Map<Long, S> get() = pendingDeltas

    init {
        seam.incoming
            .onEach { swatch -> swatch.sender?.let { dispatch(it, swatch.payload) } }
            .launchIn(scope)

        seam.peers
            .onEach { currentPeers -> onPeersChanged(currentPeers) }
            .launchIn(scope)
    }

    /**
     * Apply a local mutation. Updates [state] synchronously; broadcasts a [ReplicatorMessage.Delta]
     * to all current peers asynchronously (fire-and-forget within [scope]).
     */
    public fun apply(patch: Patch<S>) {
        _state.update { it.piece(patch) }
        val seq = ++nextSeq
        pendingDeltas[seq] = patch.delta
        broadcastDelta(seq, patch.delta)
    }

    // ---- private helpers ----

    private fun broadcastDelta(seq: Long, delta: S) {
        val msg = ReplicatorMessage.Delta(sender = replica, seq = seq, delta = delta)
        val bytes = encode(msg)
        // Launch is used because broadcast is suspend but apply() is not.
        // Failures are non-fatal — best-effort delivery; lattice convergence tolerates drops.
        scope.launch { runCatching { seam.broadcast(bytes) } }
    }

    private fun onPeersChanged(currentPeers: Set<PeerId>) {
        val newPeers = currentPeers - seam.selfId - knownPeers
        knownPeers += currentPeers - seam.selfId
        newPeers.forEach { peer -> sendFullStateTo(peer) }
    }

    private fun sendFullStateTo(peer: PeerId) {
        val msg = ReplicatorMessage.FullState(sender = replica, state = _state.value)
        val bytes = encode(msg)
        scope.launch { runCatching { seam.sendTo(peer, bytes) } }
    }

    private fun dispatch(sender: PeerId, payload: ByteArray) {
        val msg = runCatching { decode(payload) }.getOrNull() ?: return
        when (msg) {
            is ReplicatorMessage.Delta -> onDelta(sender, msg)
            is ReplicatorMessage.Ack -> onAck(msg)
            is ReplicatorMessage.FullState -> onFullState(msg)
            is ReplicatorMessage.Resend -> onResend(msg)
        }
    }

    private fun onDelta(sender: PeerId, msg: ReplicatorMessage.Delta<S>) {
        _state.update { it.piece(msg.delta) }
        sendAck(to = sender, originalSender = msg.sender, seq = msg.seq)
    }

    private fun sendAck(to: PeerId, originalSender: ReplicaId, seq: Long) {
        val msg = ReplicatorMessage.Ack<S>(acker = replica, sender = originalSender, seq = seq)
        val bytes = encode(msg)
        scope.launch { runCatching { seam.sendTo(to, bytes) } }
    }

    private fun onAck(msg: ReplicatorMessage.Ack<S>) {
        if (msg.sender != replica) return
        val acker = PeerId(msg.acker.value)
        val current = ackedThrough[acker] ?: 0L
        if (msg.seq > current) ackedThrough[acker] = msg.seq
        gcPendingDeltas()
    }

    private fun gcPendingDeltas() {
        if (knownPeers.isEmpty()) return
        val universalAck = knownPeers.minOfOrNull { peer -> ackedThrough[peer] ?: 0L } ?: return
        pendingDeltas.keys.removeAll { it <= universalAck }
    }

    private fun onFullState(msg: ReplicatorMessage.FullState<S>) {
        _state.update { it.piece(msg.state) }
    }

    private fun onResend(msg: ReplicatorMessage.Resend<S>) {
        if (msg.sender != replica) return
        for (seq in msg.fromSeq..msg.toSeq) {
            val delta = pendingDeltas[seq] ?: continue
            broadcastDelta(seq, delta)
        }
    }

    private fun encode(msg: ReplicatorMessage<S>): ByteArray =
        Cbor.encodeToByteArray(messageSerializer, msg)

    private fun decode(bytes: ByteArray): ReplicatorMessage<S> =
        Cbor.decodeFromByteArray(messageSerializer, bytes)
}
