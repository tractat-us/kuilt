package us.tractat.kuilt.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch

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
 * @param selfId         This peer's stable identity.
 * @param endpointPeers  Mutable map from Nearby endpointId → remote [PeerId],
 *                       pre-populated with the just-connected peer after handshake.
 *                       Shared with the owning [NearbyLoom] so later joiners update it.
 * @param api            The [NearbyApi] instance.
 * @param sharedPeers    The shared [MutableStateFlow] of the whole session's peer set
 *                       (owned by [NearbyLoom]).
 * @param scope          Coroutine scope for the receive loop; cancelled on [close].
 * @param maxChunkPayload  Per-chunk payload cap forwarded to [ChunkCodec].
 * @param msgIdCounter   Shared monotonic counter for message IDs (use one per seam).
 */
internal class NearbySeam(
    override val selfId: PeerId,
    private val endpointPeers: MutableMap<String, PeerId>,
    private val api: NearbyApi,
    private val sharedPeers: MutableStateFlow<Set<PeerId>>,
    private val scope: CoroutineScope,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
    private val msgIdCounter: MsgIdCounter,
) : Seam {

    override val peers: StateFlow<Set<PeerId>> = sharedPeers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    private val mutex = Mutex()
    private var closed = false

    // Per-endpoint reassemblers and sequence counters — keyed by endpointId.
    private val reassemblers = mutableMapOf<String, ChunkCodec.Reassembler>()
    private val sequences = mutableMapOf<String, Long>()

    private val receiveJob: Job = scope.launch { receiveLoop() }
    private val disconnectJob: Job = scope.launch { disconnectLoop() }

    // ── receive ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop() {
        api.payloadReceived.collect { event ->
            mutex.withLock {
                if (closed) return@collect
                // Ignore payloads from unknown endpoints (e.g. not yet connected).
                val remotePeerId = endpointPeers[event.endpointId] ?: return@collect
                processPayload(event.endpointId, event.bytes, remotePeerId)
            }
        }
    }

    private fun processPayload(endpointId: String, bytes: ByteArray, remotePeerId: PeerId) {
        val chunk = ChunkCodec.decodeChunk(bytes) ?: return
        val reassembler = reassemblers.getOrPut(endpointId) { ChunkCodec.Reassembler() }
        val payload = reassembler.feed(chunk) ?: return
        val seq = nextSequence(endpointId)
        incomingChannel.trySend(Swatch(payload = payload, sender = remotePeerId, sequence = seq))
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
            mutex.withLock {
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
        val endpoints = mutex.withLock { endpointPeers.keys.toList() }
        sendToEndpoints(endpoints, payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self" }
        val endpointId = mutex.withLock { endpointIdFor(peer) }
            ?: error("No connected endpoint for peer $peer")
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
        mutex.withLock {
            if (closed) return
            closed = true
        }
        receiveJob.cancel()
        disconnectJob.cancel()
        incomingChannel.close()
        val endpoints = mutex.withLock { endpointPeers.keys.toList() }
        for (endpointId in endpoints) {
            api.disconnect(endpointId)
        }
        sharedPeers.update { it - selfId }
    }

    private fun checkNotClosed() {
        check(!closed) { "NearbySeam for $selfId is closed" }
    }

    /** Register a new connected peer after a subsequent join. */
    internal fun addPeer(endpointId: String, peerId: PeerId) {
        endpointPeers[endpointId] = peerId
        sharedPeers.update { it + peerId }
    }
}

/** Monotonically increasing message ID counter. Not thread-safe; callers must synchronise. */
internal class MsgIdCounter {
    private var value = 0
    fun next(): Int = ++value
}
