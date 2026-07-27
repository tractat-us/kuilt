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
 * - [broadcast] while [SeamState.Weaving] (the fabric is forming, or a recoverable multipath
 *   rollup has no live ply right now — e.g. a composite whose every ply is currently torn), or
 *   [SeamState.Woven] with no other peers: **best-effort** — never throws; delivery is simply not
 *   guaranteed until [SeamState.Woven]. A fully-degraded but recoverable composite [broadcast] is
 *   therefore a best-effort zero-target no-op, not an error. (A tiered union whose *both* tiers are
 *   torn is instead terminal [SeamState.Torn] — its one-shot merged `incoming` cannot recover — so a
 *   send there throws, per the `Torn` rule below.)
 * - [sendTo] when the addressed peer is absent from [peers]: throws
 *   [PeerNotConnected].
 * - Either call when [SeamState.Torn]: throws [IllegalStateException]. `Torn` is the *only*
 *   send-state that throws (it is unconditionally terminal — see [SeamState]).
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

    /**
     * Live set of peers currently connected. Includes [selfId].
     *
     * **Initial value invariant:** The initial value of this `StateFlow` is `{ selfId }` —
     * this peer is included from the moment a `Seam` is created, even before any remote
     * peers connect. This makes `peers.value.size > 1` a reliable sentinel for "at least
     * one remote peer is connected."
     *
     * Every subsequent emission reflects the current connected peer set, always including
     * this peer's own id. Remote peers are added when connections complete and removed
     * when connections drop.
     */
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
     * Live capability of the fabric carrying this session — its role(s) and
     * whether it is usable right now. Updates as radios, permissions, and network
     * paths change.
     *
     * Default: a roleless [FabricAvailability.Unknown] floor — a fabric with no live path
     * observer reports "cannot tell", never a confident `Available`. Fabrics with real OS
     * observers override this to make it reactive; a fabric that does so declares
     * `reportsLiveCapability = true` in its `SeamCapabilities`.
     */
    public val capability: StateFlow<TransportCapability>
        get() = us.tractat.kuilt.core.internal.StaticUnknownCapability

    /**
     * Frames received from peers, in send order, delivered to **a single collector**.
     * Cold/single-collection semantics: collect once per [Seam]; fan-out consumers
     * wrap with `shareIn`. A second concurrent collector is unsupported and will race.
     *
     * **Termination contract:** this flow **completes** (the collection terminates normally)
     * once the seam reaches [SeamState.Torn] — whether via a local [close] call or a
     * remote disconnect. Consumers that own resources tied to the incoming stream (e.g.
     * [us.tractat.kuilt.quilter.Quilter]) use `.onCompletion { }` to
     * self-clean when the seam tears, without requiring the caller to call their own
     * `close()` explicitly. Every [Loom] implementation must honour this contract.
     */
    public val incoming: Flow<Swatch>

    /**
     * Send to all other peers. Suspends until accepted by the local transport.
     *
     * A send failure must **not** be reported as a cancellation — see [sendTo].
     */
    public suspend fun broadcast(payload: ByteArray)

    /**
     * Send to one peer. Suspends until accepted by the local transport.
     *
     * ## A send failure must NOT be reported as a cancellation
     *
     * Throw an ordinary exception when the frame cannot be handed to the transport. An implementation
     * must **not** let a `CancellationException` out of this method (or out of [broadcast]) unless it
     * is signalling the *caller's* own cancellation, because the caller cannot tell the two apart: the
     * idiomatic guard ([runCatchingCancellable]) rethrows any `CancellationException`, and a rethrown
     * one **cancels** the calling coroutine rather than failing it — no failure handler runs, and there
     * is not even a stack trace to find it by.
     *
     * The trap is `withTimeout(sendTimeout) { … }`. `withTimeout` throws `TimeoutCancellationException`
     * — which *is* a `CancellationException` — **to its caller**, without cancelling that caller's job.
     * Convert it before it escapes: `withTimeoutOrNull` plus an explicit throw, or catch it and rethrow
     * as a plain `Exception`.
     *
     * This is the same obligation [Loom.weave] carries, for the same reason, and it is stated on both
     * because a contract only one method carries is what let a real bug through: a long-lived consumer
     * loop that sends per recipient is cancelled — not failed — by one such throw, so it stops sending
     * for the rest of the session in complete silence. A caller that cannot afford to trust this
     * should guard with `try`/`catch` plus `currentCoroutineContext().ensureActive()` rather than
     * [runCatchingCancellable]; `CompositeSeam.reconcile` and `SeamRoom`'s admit fan-out writer are the
     * in-tree patterns.
     */
    public suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    )

    /** Disconnect from the session. Idempotent. */
    public suspend fun close(reason: CloseReason = CloseReason.Normal)
}
