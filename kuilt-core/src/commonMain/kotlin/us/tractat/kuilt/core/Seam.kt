package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One peer's view of a multi-peer session.
 *
 * Symmetry: every peer in the session holds an identical Seam — there
 * is no client/server distinction at this layer. A two-peer topology
 * (e.g. the existing WebSocket transport) is just the degenerate case
 * with `peers.value.size == 2`.
 *
 * **Fabric lifecycle:** [state] tracks whether the fabric can carry frames.
 * Wait for [SeamState.Woven] before sending on fabrics that may take time
 * to establish their link (radio/mesh transports). Relay transports reach
 * [SeamState.Woven] essentially immediately.
 *
 * **Send semantics:**
 * - [broadcast] while [SeamState.Weaving] or [SeamState.Woven] with no
 *   other peers: defined no-op, never silent.
 * - [sendTo] when the addressed peer is absent from [peers]: throws
 *   [PeerNotConnected].
 * - Either call when [SeamState.Torn]: throws [IllegalStateException].
 */
public interface Seam {
    /** This peer's own identifier. */
    public val selfId: PeerId

    /** Live set of peers currently connected. Includes [selfId]. */
    public val peers: StateFlow<Set<PeerId>>

    /** The fabric's lifecycle as observed by this peer. */
    public val state: StateFlow<SeamState>

    /**
     * Frames received from peers, in send order, delivered to **a single collector**.
     * Cold/single-collection semantics: collect once per [Seam]; fan-out consumers
     * wrap with `shareIn`. A second concurrent collector is unsupported and will race.
     */
    public val incoming: Flow<Swatch>

    /** Send to all other peers. Suspends until accepted by the local transport. */
    public suspend fun broadcast(payload: ByteArray)

    /** Send to one peer. Suspends until accepted by the local transport. */
    public suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    )

    /** Disconnect from the session. Idempotent. */
    public suspend fun close(reason: CloseReason = CloseReason.Normal)
}
