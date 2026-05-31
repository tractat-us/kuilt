package us.tractat.kuilt.webrtc.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

private val log = KotlinLogging.logger {}

/**
 * [Seam] implementation backed by an open [RtcPeerConnectionFacade] data channel.
 *
 * Stamp each incoming frame with the resolved [senderIdDeferred] as the sender and
 * a monotonically increasing sequence number. The data channel is point-to-point,
 * so [broadcast] and [sendTo] are equivalent.
 *
 * [userFrames] is the post-ID-exchange byte flow — frames after the peer-identity
 * handshake frame. The factory performs the ID exchange and passes the remaining
 * frames here. Defaults to [facade.incomingBytes] for tests that construct
 * [WebRTCPeerLink] directly with pre-coordinated [remoteId] values.
 *
 * [senderIdDeferred] is the remote peer's actual [PeerId], resolved asynchronously
 * once the ID-exchange frame arrives. Defaults to an immediately-completed deferred
 * holding [remoteId], preserving existing test-construction semantics.
 */
internal class WebRTCPeerLink(
    override val selfId: PeerId,
    private val remoteId: PeerId,
    private val facade: RtcPeerConnectionFacade,
    private val userFrames: Flow<ByteArray> = facade.incomingBytes,
    private val senderIdDeferred: Deferred<PeerId> = CompletableDeferred(remoteId),
) : Seam {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequenceCounter = SequenceCounter()
    private val _peers = MutableStateFlow(setOf(selfId, remoteId))

    override val peers: StateFlow<Set<PeerId>> get() = _peers

    // WebRTC data channel is open at construction — fabric is immediately live.
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> get() = _state

    override val incoming: Flow<Swatch> =
        userFrames.map { bytes ->
            Swatch(payload = bytes, sender = senderIdDeferred.await(), sequence = sequenceCounter.next())
        }

    private var closed = false

    init {
        log.debug { "Seam created self=$selfId remote=$remoteId" }
        // Shrink the peer set when the remote closes the channel.
        scope.launch {
            facade.awaitDataChannelClose()
            log.debug { "Seam data channel closed by remote self=$selfId remote=$remoteId" }
            _peers.value = setOf(selfId)
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        if (_peers.value.none { it != selfId }) {
            log.warn { "webrtc.send dropped — no connected peers selfId=${selfId.value} bytes=${payload.size}" }
            return
        }
        facade.sendBytes(payload)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        facade.sendBytes(payload)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        log.debug { "Seam closing self=$selfId remote=$remoteId reason=$reason" }
        _state.value = SeamState.Torn(reason)
        try {
            facade.close()
        } finally {
            scope.cancel()
        }
    }
}

private class SequenceCounter {
    private var next: Long = 0L

    fun next(): Long = next++
}
