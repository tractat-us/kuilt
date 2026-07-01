package us.tractat.kuilt.nearby

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch

private val log = KotlinLogging.logger("us.tractat.kuilt.nearby.NearbySeam")

/**
 * [Seam] implementation backed by a Nearby Connections link.
 *
 * Receives data via [NearbyApi.payloadReceived], reassembles chunks with
 * per-endpoint [ChunkCodec.Reassembler]s, stamps each complete [Swatch] with
 * the remote's stable [PeerId] (exchanged during the connect handshake) and a
 * per-endpoint monotonic sequence number, then pushes it to [incoming].
 *
 * [broadcast] and [sendTo] chunk-encode the payload and call
 * [NearbyApi.sendBytesPayload] for each chunk.
 *
 * @param selfId              This peer's stable identity.
 * @param endpointPeers       Mutable map from Nearby endpointId → remote [PeerId],
 *                            pre-populated with the just-connected peer after handshake.
 *                            Shared with the owning [NearbyLoom] so later joiners update it.
 * @param endpointPeersMutex  The single [Mutex] that guards [endpointPeers]. Created once
 *                            by [NearbyLoom] and passed here so both sides serialise every
 *                            read and write on the same lock instance.
 * @param api                 The [NearbyApi] instance.
 * @param sharedPeers         The shared [MutableStateFlow] of the whole session's peer set
 *                            (owned by [NearbyLoom]).
 * @param scope               Coroutine scope for the receive loop; cancelled on [close].
 * @param maxChunkPayload     Per-chunk payload cap forwarded to [ChunkCodec].
 * @param msgIdCounter        Shared monotonic counter for message IDs (use one per seam).
 * @param policy              Delivery policy for the inbound [Spool]. Defaults to
 *                            [DeliveryPolicy.Reliable] (bounded, backpressured).
 */
internal class NearbySeam(
    override val selfId: PeerId,
    private val endpointPeers: MutableMap<String, PeerId>,
    private val endpointPeersMutex: Mutex,
    private val api: NearbyApi,
    private val sharedPeers: MutableStateFlow<Set<PeerId>>,
    private val scope: CoroutineScope,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
    private val msgIdCounter: MsgIdCounter,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam {

    override val peers: StateFlow<Set<PeerId>> = sharedPeers.asStateFlow()

    // Starts Weaving; transitions to Woven when the first remote peer joins.
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Guards only the `closed` flag. All `endpointPeers` access uses `endpointPeersMutex`.
    private val closedMutex = Mutex()
    private var closed = false

    // Per-endpoint reassemblers and sequence counters — keyed by endpointId.
    // Accessed only within endpointPeersMutex, so no separate guard needed.
    private val reassemblers = mutableMapOf<String, ChunkCodec.Reassembler>()
    private val sequences = mutableMapOf<String, Long>()

    // UNDISPATCHED so both loops subscribe to their event flows synchronously at
    // construction — before any handshake/data events can be emitted.
    private val receiveJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { receiveLoop() }
    private val disconnectJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { disconnectLoop() }

    // Watch peers: transition Weaving → Woven when the first remote peer appears.
    private val wovenWatcher: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        sharedPeers.collect { peers ->
            if (_state.value is SeamState.Weaving && peers.any { it != selfId }) {
                _state.value = SeamState.Woven
            }
        }
    }

    // ── receive ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop() {
        api.payloadReceived.collect { event ->
            // Snapshot (swatch) under the lock, then deliver OUTSIDE it so that a
            // SUSPEND-policy backpressure stall never holds endpointPeersMutex.
            val frame = endpointPeersMutex.withLock {
                if (closed) return@collect
                // Ignore payloads from unknown endpoints (e.g. not yet connected).
                val remotePeerId = endpointPeers[event.endpointId] ?: return@collect
                assembleFrame(event.endpointId, event.bytes, remotePeerId)
            } ?: return@collect
            spool.deliver(frame)
        }
    }

    /**
     * Reassemble a chunk and build the [Swatch] — called under [endpointPeersMutex].
     * Returns `null` if the chunk is incomplete or malformed.
     */
    private fun assembleFrame(endpointId: String, bytes: ByteArray, remotePeerId: PeerId): Swatch? {
        val chunk = ChunkCodec.decodeChunk(bytes) ?: return null
        val reassembler = reassemblers.getOrPut(endpointId) { ChunkCodec.Reassembler() }
        val payload = reassembler.feed(chunk) ?: return null
        val seq = nextSequence(endpointId)
        return Swatch(payload = payload, sender = remotePeerId, sequence = seq)
    }

    private fun nextSequence(endpointId: String): Long {
        val current = sequences.getOrElse(endpointId) { 0L }
        val next = current + 1
        sequences[endpointId] = next
        return next
    }

    // ── disconnect detection ──────────────────────────────────────────────────

    private suspend fun disconnectLoop() {
        api.endpointDisconnected.collect { event ->
            endpointPeersMutex.withLock {
                if (closed) return@collect
                val peerId = endpointPeers.remove(event.endpointId) ?: return@collect
                reassemblers.remove(event.endpointId)?.reset()
                sequences.remove(event.endpointId)
                sharedPeers.update { it - peerId }
            }
        }
    }

    // ── send ──────────────────────────────────────────────────────────────────

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        val endpoints = endpointPeersMutex.withLock { endpointPeers.keys.toList() }
        if (endpoints.isEmpty()) {
            log.warn { "nearby.send dropped — no connected peers selfId=${selfId.value} bytes=${payload.size}" }
            return
        }
        sendToEndpoints(endpoints, payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self" }
        val endpointId = endpointPeersMutex.withLock { endpointIdFor(peer) }
            ?: throw PeerNotConnected(peer)
        sendToEndpoints(listOf(endpointId), payload)
    }

    private suspend fun sendToEndpoints(endpoints: List<String>, payload: ByteArray) {
        val msgId = msgIdCounter.next()
        val chunks = ChunkCodec.encode(payload, msgId, maxChunkPayload)
        for (endpointId in endpoints) {
            for (chunk in chunks) {
                api.sendBytesPayload(endpointId, chunk)
            }
        }
    }

    private fun endpointIdFor(peer: PeerId): String? =
        endpointPeers.entries.firstOrNull { it.value == peer }?.key

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        closedMutex.withLock {
            if (closed) return
            closed = true
        }
        _state.value = SeamState.Torn(reason)
        // Cancel the entire scope — this cleans up the receive/disconnect loops
        // AND the background accept coroutine launched by NearbyLoom.open() into
        // the same scope, preventing coroutine leaks between tests.
        scope.coroutineContext[Job]?.cancel()
        spool.close()
        val endpoints = endpointPeersMutex.withLock { endpointPeers.keys.toList() }
        for (endpointId in endpoints) {
            api.disconnect(endpointId)
        }
        sharedPeers.update { it - selfId }
    }

    private fun checkNotClosed() {
        check(!closed) { "NearbySeam for $selfId is closed" }
    }
}

/** Monotonically increasing message ID counter. Not thread-safe; callers must synchronise. */
internal class MsgIdCounter {
    private var value = 0
    fun next(): Int = ++value
}
