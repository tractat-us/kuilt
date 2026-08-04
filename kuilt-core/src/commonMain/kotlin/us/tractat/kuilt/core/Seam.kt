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
 * - [sendTo] of a payload over [maxPayloadBytes]: **should** throw [PayloadTooLarge]; [broadcast]
 *   should instead drop it (best-effort, as above). An obligation on an implementation that
 *   publishes a budget, not a guarantee every in-tree seam already meets — see [maxPayloadBytes].
 *   Note it is the one refusal that does **not** depend on the seam's state — a `Woven` seam
 *   raises it — so a caller catching only [PeerNotConnected] does not cover addressed sends on a
 *   seam that publishes a budget.
 * - Either call when [SeamState.Torn]: throws [IllegalStateException]. `Torn` is the only
 *   *state-driven* refusal (it is unconditionally terminal — see [SeamState]); the payload-size
 *   refusal above is orthogonal to state.
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
     *
     * ## Membership, not one-hop reachability
     *
     * A peer here is one this seam can carry a frame to — **not** necessarily over a direct link.
     * A `Seam` may be a *view* whose route is longer than one hop: a room's channel view publishes
     * the admitted roster, and on a star fabric a co-spoke in that roster is reached by relaying
     * through the host (#1994). So do not read this set as "peers I hold a link to". On a star that
     * is a strictly smaller set, and the gap between the two is what #1994 was: a view published the
     * roster here while routing [sendTo] through the transport, so it named peers it could not then
     * address.
     *
     * The obligation the set does carry is that pairing. A peer in `peers` must be addressable by
     * [sendTo] — by whatever hop count the implementation uses — so an implementation that publishes
     * a peer it has no route to is the bug, not the caller that believed it.
     *
     * ## A Torn seam has no reachable peers — collapse this to `{ selfId }`
     *
     * Once the seam reaches [SeamState.Torn] this set **must** be exactly `{ selfId }`. A torn fabric
     * can reach nobody, so a remote peer left here is a claim [sendTo] immediately disproves by
     * throwing [PeerNotConnected] for a peer `peers` calls reachable. Publish the collapsed roster
     * **before, or atomically with, latching `Torn`**, so a consumer woken by the terminal state
     * already observes the collapse. `LinkSeam.tearDown` collapses then tears; `MeshSeam.tearDown`
     * does both inside one lock section — either shape is fine.
     *
     * This is load-bearing, not tidiness. A seam that *folds other seams* —
     * [us.tractat.kuilt.core.composite.CompositeSeam] bonding plies — decides which peers are
     * reachable from each member's `peers`, and its liveness test is only that the member is still
     * attached. A member that latches `Torn` without collapsing therefore keeps contributing peers to
     * that fold until it is detached, leaving the composite advertising a peer reachable only through a
     * dead transport. Before this was stated, what closed that gap was a *convention* every in-tree
     * fabric happened to follow rather than a rule an implementor could read (#1816).
     *
     * Asserted by `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn`, gated on the
     * `collapsesPeersOnTear` capability so a fabric that does not honour it yet declares a **tracked**
     * gap rather than passing silently.
     *
     * **What the TCK can and cannot see.** It asserts the terminal *value* — a `Torn` seam's `peers` is
     * `{ selfId }` — and not the *ordering*, because it must work against a fabric whose seams it drives
     * through a dispatcher: a collector that resumes after `close()` returns always reads the settled
     * value, so an ordering assertion there would pass for every implementation and prove nothing. That
     * makes the ordering clause above no less binding, only unenforceable from a portable suite. Pin it
     * per fabric with an *inline* collector, as `CompositeCloseCollapseOrderTest` does — it reads `peers`
     * from inside the `Torn` write itself, and a fabric that latches before collapsing fails it while the
     * TCK obligation stays green.
     */
    public val peers: StateFlow<Set<PeerId>>

    /** The fabric's lifecycle as observed by this peer. */
    public val state: StateFlow<SeamState>

    /**
     * The largest `payload` a single [broadcast] or [sendTo] may carry, or `null` when this seam
     * cannot tell.
     *
     * `null` means **unknown, not unbounded** — it is the honest answer from a fabric with no frame
     * ceiling it can name, and a caller must treat it as "no guidance", never as "any size is
     * fine". A non-null value is a promise: a payload of that size or smaller will not be refused
     * by this seam *for being too big*. (It may still fail for every other reason a send can fail.)
     *
     * ## Why the contract needs this at all
     *
     * A framed fabric rejects an oversize frame — `:kuilt-stream`'s `framed()` throws
     * `FrameTooLargeException` — and every layer that *wraps* a payload before handing it down
     * spends some of that ceiling on its own header. Without a published limit those two facts meet
     * only at run time, in the failure: a payload that fits when sent directly overflows once a
     * decorator wraps it, and the caller learns the difference from a fabric-level error it had no
     * way to anticipate (#2047). Publishing the number lets a wrapper subtract its own cost and
     * hand the caller a bound it can actually respect.
     *
     * ## What a decorator owes
     *
     * A [Seam] that decorates another and adds bytes reports the delegate's limit **less its own
     * overhead**, floored at zero — the idiom `inner.maxPayloadBytes?.let { (it - cost).coerceAtLeast(0) }`.
     * Subtract **unconditionally**, even when the overhead is only paid on some routes: a limit
     * that moves with routing is a TOCTOU trap, because the route can change between the caller's
     * check and its send. A stable, conservative bound is the only useful one.
     *
     * A decorator that adds no bytes delegates unchanged. Leaving the default in place is safe but
     * lossy — it discards a bound the fabric underneath does know.
     *
     * An implementation that publishes a limit should refuse an over-budget payload with
     * [PayloadTooLarge] rather than letting a fabric-level error out, subject to each method's own
     * contract: [sendTo] is addressed and reports, [broadcast] is best-effort and drops.
     *
     * ## Publishing is not yet enforcing (#2069)
     *
     * The in-tree fabric seams publish this number but do **not** pre-check against it, and the
     * consequence is worse than a leaked error. `LinkSeam.sendTo` enqueues and returns *success*;
     * its write loop then meets the fabric's own oversize error and tears the whole seam down
     * asynchronously, after the caller was told the send was accepted. `MeshSeam.sendTo` routes the
     * same failure into `removePeer`, evicting a healthy recipient as though its link had died. The
     * fix is a per-conn pre-check inside each seam against the **immutable**
     * [us.tractat.kuilt.core.fabric.Connection.maxFrameBytes] rather than against this live
     * aggregate — which also removes the check-then-send race, since a mesh's minimum can tighten
     * between a caller's read and the write. Tracked in #2069, along with the missing TCK case that
     * would have caught it.
     *
     * ## A reading, not a lease
     *
     * This value may move. A mesh reports the minimum across its live links, so a peer attaching
     * over a tighter transport lowers it. Read it per send; a caller that reads once and trusts the
     * value for a whole batch can be refused part-way through, correctly.
     */
    public val maxPayloadBytes: Int? get() = null

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
     * A payload over [maxPayloadBytes] is **dropped**, not reported — this call is best-effort, and
     * its most common caller is a timer-driven replication loop that a throw would kill. Read the
     * budget and size to it; [sendTo] is the call that tells you.
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
     *
     * @throws PeerNotConnected if [peer] is absent from [peers].
     * @throws PayloadTooLarge if [payload] exceeds [maxPayloadBytes] — the obligation on a seam
     *   that publishes a budget, and what a caller must be ready for. Independent of [state] (a
     *   `Woven` seam raises it), so a `catch (PeerNotConnected)` written against the state-driven
     *   refusals alone does not cover it. **Not yet met by the in-tree fabric seams**, which
     *   publish a budget but do not pre-check it — see [maxPayloadBytes] and #2069.
     */
    public suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    )

    /**
     * Disconnect from the session. Idempotent.
     *
     * ## A close failure must NOT be reported as a cancellation
     *
     * The obligation [sendTo] states, verbatim and for the same reason: throw an ordinary exception
     * when teardown fails, and never let a `CancellationException` out of this method unless it is
     * signalling the *caller's* own cancellation. The trap is identical — `withTimeout(closeTimeout) { … }`
     * throws `TimeoutCancellationException`, which *is* a `CancellationException`, **to its caller**
     * without cancelling that caller's job. Bound teardown with `withTimeoutOrNull` plus an explicit
     * throw instead, or catch and rethrow as a plain `Exception`.
     *
     * `close` carries it because best-effort cleanup is where the masquerade does the most damage. A
     * library tearing down N things loops over them under a guard; one callee-minted cancellation
     * **cancels that loop** rather than failing one item, so every remaining close is skipped — the
     * leak the cleanup existed to prevent, with no failure handler and no stack trace. The caller
     * cannot simply defend its way out: [runCatchingCancellable] rethrows every
     * `CancellationException` **by type**, and type is exactly what cannot separate "my job was
     * cancelled" from "the callee minted one". (That is why using it inside a
     * `withContext(NonCancellable)` shield is forbidden repo-wide by the
     * `forbidRunCatchingCancellableUnderNonCancellable` build guard: inside the shield our job is
     * never cancelled, so every `CancellationException` reachable there is necessarily callee-minted.)
     * Stating the obligation converts "every caller must defend" into "an implementation that does
     * this is non-conforming" (#1826).
     *
     * ## Every consumer-implemented suspend surface carries this
     *
     * Exhaustively, the surfaces a kuilt call site invokes under a best-effort guard are:
     * [Loom.weave], [broadcast], [sendTo], this method, and
     * [us.tractat.kuilt.core.fabric.Connection.send]/[us.tractat.kuilt.core.fabric.Connection.close];
     * outside `:kuilt-core`, `Room.leave`. Each points here or at [sendTo] rather than restating it.
     *
     * Asserted by `SeamConformanceSuite.closeDoesNotReportFailureAsCancellation`.
     */
    public suspend fun close(reason: CloseReason = CloseReason.Normal)
}
