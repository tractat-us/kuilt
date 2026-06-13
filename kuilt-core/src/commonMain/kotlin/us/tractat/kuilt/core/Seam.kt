package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.internal.MappedStateFlow

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
 *
 * ## Collecting incoming frames
 *
 * Collect [incoming] exactly once per `Seam`. For multiple consumers, wrap with
 * `shareIn` in a coroutine scope you control.
 *
 * @sample us.tractat.kuilt.core.sampleIncomingFanout
 */
public interface Seam {
    /** This peer's own identifier. */
    public val selfId: PeerId

    /** Live set of peers currently connected. Includes [selfId]. */
    public val peers: StateFlow<Set<PeerId>>

    /** The fabric's lifecycle as observed by this peer. */
    public val state: StateFlow<SeamState>

    /**
     * Per-ply lifecycle breakdown. Single-ply fabrics report a one-entry map
     * keyed by [PlyId.Sole]. Invariant: `state.value` equals the rollup of
     * `plies.value.values` under "any ply Woven ⇒ Woven".
     */
    public val plies: StateFlow<Map<PlyId, SeamState>>
        get() = MappedStateFlow(state) { mapOf(PlyId.Sole to it) }

    /**
     * Frames received from peers, in send order, delivered to **a single collector**.
     * Cold/single-collection semantics: collect once per [Seam]; fan-out consumers
     * wrap with `shareIn`. A second concurrent collector is unsupported and will race.
     *
     * **Termination contract:** this flow **completes** (the collection terminates normally)
     * once the seam reaches [SeamState.Torn] — whether via a local [close] call or a
     * remote disconnect. Consumers that own resources tied to the incoming stream (e.g.
     * [us.tractat.kuilt.crdt.replicator.SeamReplicator]) use `.onCompletion { }` to
     * self-clean when the seam tears, without requiring the caller to call their own
     * `close()` explicitly. Every [Loom] implementation must honour this contract.
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
