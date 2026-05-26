package us.tractat.kuilt.core.partition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * A [Seam] view that exposes only frames from [filteredSender].
 *
 * This is necessary for multi-detector test setups: when multiple
 * [HeartbeatPartitionDetector] instances all share the same host [Seam],
 * they cannot each consume from the host's [Seam.incoming] directly
 * (which is a channel-backed cold flow — only one consumer sees each frame).
 *
 * The solution: the [Mesh] broadcasts the host's incoming frames into a
 * [SharedFlow], then each detector gets a [FilteredSeam] that filters
 * the shared flow by sender. All detectors see all frames; each only reacts
 * to frames from its monitored peer.
 *
 * [send] is still forwarded to the underlying link so pings reach their peer.
 */
internal class FilteredSeam(
    private val delegate: Seam,
    private val filteredSender: PeerId,
    private val sharedIncoming: SharedFlow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val incoming: Flow<Swatch> = sharedIncoming.filter { it.sender == filteredSender }

    override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(payload)

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) = delegate.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason) = delegate.close(reason)
}
