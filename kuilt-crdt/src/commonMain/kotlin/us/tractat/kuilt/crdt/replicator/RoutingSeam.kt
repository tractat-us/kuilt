package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * Splits a [Seam]'s single [incoming] flow into two distinct routing channels,
 * identified by a **1-byte discriminator** prefix on every frame.
 *
 * ## Why this exists
 *
 * [Seam.incoming] is single-collection per the kuilt contract: collect it once
 * per [Seam]; fan-out consumers must use `shareIn`. When [SeamReplicator] already
 * owns the incoming flow of a seam, a [BoundedCounterTransferCoordinator] cannot
 * add a second collector. [RoutingSeam] solves this by owning the *single* collect
 * on the underlying seam and routing each frame to one of two typed sub-flows via
 * its first byte.
 *
 * ## Discriminator contract
 *
 * - [REPLICATOR_TAG] (`0x00`): frames destined for [SeamReplicator].
 * - [COORDINATOR_TAG] (`0x01`): frames destined for [BoundedCounterTransferCoordinator].
 *
 * Callers obtain a [Seam] view for each channel via [replicatorView] and
 * [coordinatorView]. Each view strips the discriminator byte before delivering
 * frames to its collector, and prepends the discriminator on [broadcast]/[sendTo].
 *
 * ## Avoiding SeamReplicator changes
 *
 * [SeamReplicator] is actively changing (PR #202 chaos suite). Introducing a routing
 * layer via a Seam wrapper avoids touching that class while enabling a second logical
 * channel on the same underlying transport.
 *
 * @param delegate the underlying [Seam] whose [incoming] this class owns.
 * @param scope a [CoroutineScope] for the shared upstream collector.
 */
public class RoutingSeam(
    private val delegate: Seam,
    scope: CoroutineScope,
) {
    public companion object {
        /** Discriminator for [SeamReplicator] frames. */
        public const val REPLICATOR_TAG: Byte = 0x00

        /** Discriminator for [BoundedCounterTransferCoordinator] frames. */
        public const val COORDINATOR_TAG: Byte = 0x01
    }

    /**
     * A single shared subscription on the underlying [incoming] flow. Both
     * routing views subscribe to this rather than to [delegate.incoming] directly,
     * so there is exactly one collection of the underlying seam.
     */
    private val sharedIncoming = delegate.incoming
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 0)

    /** A [Seam] view that routes only [REPLICATOR_TAG] frames. */
    public val replicatorView: Seam = RoutedView(REPLICATOR_TAG)

    /** A [Seam] view that routes only [COORDINATOR_TAG] frames. */
    public val coordinatorView: Seam = RoutedView(COORDINATOR_TAG)

    private fun taggedPayload(tag: Byte, payload: ByteArray): ByteArray {
        val tagged = ByteArray(payload.size + 1)
        tagged[0] = tag
        payload.copyInto(tagged, destinationOffset = 1)
        return tagged
    }

    private fun strippedPayload(swatch: Swatch): Swatch =
        swatch.copy(payload = swatch.payload.copyOfRange(1, swatch.payload.size))

    private fun belongsTo(tag: Byte, swatch: Swatch): Boolean =
        swatch.payload.isNotEmpty() && swatch.payload[0] == tag

    private inner class RoutedView(private val tag: Byte) : Seam {
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        override val incoming: Flow<Swatch> = sharedIncoming
            .filter { swatch -> belongsTo(tag, swatch) }
            .map { swatch -> strippedPayload(swatch) }

        override suspend fun broadcast(payload: ByteArray) =
            delegate.broadcast(taggedPayload(tag, payload))

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) =
            delegate.sendTo(peer, taggedPayload(tag, payload))

        override suspend fun close(reason: CloseReason) = delegate.close(reason)
    }
}
