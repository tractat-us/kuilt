package us.tractat.kuilt.core.partition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.OpaqueFrame
import us.tractat.kuilt.core.TransportPeerId
import us.tractat.kuilt.core.PeerLink

/**
 * A [PeerLink] view that exposes only frames from [filteredSender].
 *
 * This is necessary for multi-detector test setups: when multiple
 * [HeartbeatPartitionDetector] instances all share the same host [PeerLink],
 * they cannot each consume from the host's [PeerLink.incoming] directly
 * (which is a channel-backed cold flow — only one consumer sees each frame).
 *
 * The solution: the [Mesh] broadcasts the host's incoming frames into a
 * [SharedFlow], then each detector gets a [FilteredPeerLink] that filters
 * the shared flow by sender. All detectors see all frames; each only reacts
 * to frames from its monitored peer.
 *
 * [send] is still forwarded to the underlying link so pings reach their peer.
 */
internal class FilteredPeerLink(
    private val delegate: PeerLink,
    private val filteredSender: TransportPeerId,
    private val sharedIncoming: SharedFlow<OpaqueFrame>,
) : PeerLink {
    override val selfId: TransportPeerId get() = delegate.selfId
    override val peers: StateFlow<Set<TransportPeerId>> get() = delegate.peers
    override val incoming: Flow<OpaqueFrame> = sharedIncoming.filter { it.sender == filteredSender }

    override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(payload)

    override suspend fun sendTo(
        peer: TransportPeerId,
        payload: ByteArray,
    ) = delegate.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason) = delegate.close(reason)
}
