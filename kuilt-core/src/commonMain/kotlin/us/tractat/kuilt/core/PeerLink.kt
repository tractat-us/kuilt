package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One peer's view of a multi-peer session.
 *
 * Symmetry: every peer in the session holds an identical PeerLink — there
 * is no client/server distinction at this layer. A two-peer topology
 * (e.g. the existing WebSocket transport) is just the degenerate case
 * with `peers.value.size == 2`.
 */
public interface PeerLink {
    /** This peer's own identifier. */
    public val selfId: TransportPeerId

    /** Live set of peers currently connected. Includes [selfId]. */
    public val peers: StateFlow<Set<TransportPeerId>>

    /** Frames received from any other peer, in arrival order. */
    public val incoming: Flow<OpaqueFrame>

    /** Send to all other peers. Suspends until accepted by the local transport. */
    public suspend fun broadcast(payload: ByteArray)

    /** Send to one peer. Suspends until accepted by the local transport. */
    public suspend fun sendTo(
        peer: TransportPeerId,
        payload: ByteArray,
    )

    /** Disconnect from the session. Idempotent. */
    public suspend fun close(reason: CloseReason = CloseReason.Normal)
}
