package us.tractat.kuilt.webrtc.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch

private val log = KotlinLogging.logger {}

/**
 * [Seam] implementation backed by an open [RtcPeerConnectionFacade] data channel.
 *
 * Stamp each incoming frame with [remoteId] as the sender and a monotonically
 * increasing sequence number. The data channel is point-to-point, so
 * [broadcast] and [sendTo] are equivalent.
 */
internal class WebRTCPeerLink(
    override val selfId: PeerId,
    private val remoteId: PeerId,
    private val facade: RtcPeerConnectionFacade,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequenceCounter = SequenceCounter()
    private val _peers = MutableStateFlow(setOf(selfId, remoteId))

    override val peers: StateFlow<Set<PeerId>> get() = _peers

    override val incoming: Flow<Swatch> =
        facade.incomingBytes.map { bytes ->
            Swatch(payload = bytes, sender = remoteId, sequence = sequenceCounter.next())
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
        facade.sendBytes(payload)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        require(peer == remoteId) { "WebRTC link is point-to-point: $peer not in peer set" }
        facade.sendBytes(payload)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        log.debug { "Seam closing self=$selfId remote=$remoteId reason=$reason" }
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
