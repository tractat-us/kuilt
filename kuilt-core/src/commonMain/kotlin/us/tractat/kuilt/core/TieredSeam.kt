package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * Bond a **local-tier** and a **peer-tier** [Seam] — two views of the *same* node onto
 * two disjoint peer sets — into one [Seam] whose roster is the union of both.
 *
 * The motivating case (game-overlay slice 6) is a federated per-game seam: the local tier is
 * a room's `RoomHubSeam` (the members physically connected to this server) and the peer tier
 * is `NamedMux(coreMesh).channel(gameId)` (the *other* servers, one hop across the core mesh).
 * A per-game broadcast must reach the local room **and** cross to the other servers — two
 * transports, two rosters — so this presents them as one seam. The primitive itself knows
 * nothing about rooms, games, or clusters: it is a generic seam composition, beside
 * [us.tractat.kuilt.core.composite.CompositeSeam] / [RoomHubSeam] / [NamedMux].
 *
 * ## Contract
 *
 * - **[peers]** is the live **union** `local.peers ∪ peer.peers`, recomputed whenever either
 *   tier's roster changes. The two rosters are **assumed disjoint**; if they overlap, the
 *   union simply dedups by [PeerId] (a shared id resolves to one entry, and [sendTo] routes it
 *   to the local tier — see below).
 * - **[incoming]** is the **merge** of both tiers' `incoming`. This seam becomes the **sole
 *   collector** of *both* underlying seams' `incoming` (started eagerly on [scope]) and its own
 *   [incoming] is itself single-collection (collect once, per the ADR-034 contract). **Callers
 *   must not collect either underlying seam's `incoming` elsewhere** — exactly as [NamedMux] and
 *   [RoomHubSeam] own the collection of what they wrap.
 * - **[broadcast]** **tees to BOTH** tiers (the frame reaches the local room and crosses to the
 *   other servers). Each side is best-effort and independent: a failure on one tier never
 *   prevents the other from being attempted.
 * - **[sendTo]** routes to whichever tier **owns** the addressed peer — `peer ∈ local.peers` →
 *   `local.sendTo`, else `peer ∈ peer.peers` → `peer.sendTo`, else the peer is unknown to both
 *   and the frame is **dropped** (a silent no-op — `kuilt-core` is logger-free by contract). It
 *   **never fans to both**: unicast stays single-addressee across the union, preserving the
 *   ADR-005 single-addressee leak boundary. (A shared/overlapping id resolves to the local tier,
 *   since it is checked first.)
 * - **[selfId]** — both tiers are the same node, so `local.selfId` must equal `peer.selfId`;
 *   construction throws [IllegalArgumentException] otherwise.
 * - **[state]** is the composed lifecycle: **Woven** while *either* tier is Woven (the surviving tier
 *   carries), **Weaving** while forming, and **Torn** — **terminal, latched** — once *both* tiers are
 *   Torn or [close] is called. Both-tiers-torn is genuinely terminal here (not a revivable rollup)
 *   because this union's [incoming] is a **one-shot merge** that completes permanently when both tiers'
 *   `incoming` complete — so reporting a recoverable `Weaving` would contradict a terminally-completed
 *   `incoming`. This **differs from** [us.tractat.kuilt.core.composite.CompositeSeam], whose persistent
 *   spool survives ply churn, so *its* all-plies-torn rollup is recoverable `Weaving` (#1367). [close]
 *   closes both tiers.
 *
 * ## Thread safety
 *
 * Correct under a **multi-threaded** dispatcher, by real primitives — never single-thread
 * confinement. [state] runs through a [SeamStateGate]: the `combine` state pump publishes via
 * `update()` (a no-op once torn) and [close] latches `Torn` via `tear()`, so no in-flight pump write
 * can overwrite the terminal state and `tear()`'s single-shot return subsumes the old close latch.
 * [peers] is written from a single `combine` collector, but that write races [close]'s roster
 * collapse, so both are guarded by a small [reentrantLock] with the torn-check folded into the same
 * critical section — a post-close peers emission cannot resurrect the roster. [incoming] flows
 * through a bounded [Spool].
 *
 * @param scope **required** parent scope for the union/incoming pumps — no real-dispatcher
 *   default (a default would silently decouple the pumps from a test's virtual clock). The
 *   internal coroutines run on a child of this scope so [close] cancels them without tearing the
 *   caller's scope.
 * @param policy governs the merged inbound [Spool]'s capacity/overflow. Defaults to
 *   [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 */
public fun tieredSeam(
    local: Seam,
    peer: Seam,
    scope: CoroutineScope,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Seam = TieredSeam(local, peer, scope, policy)

internal class TieredSeam(
    private val localTier: Seam,
    private val peerTier: Seam,
    parentScope: CoroutineScope,
    policy: DeliveryPolicy,
) : Seam {

    init {
        require(localTier.selfId == peerTier.selfId) {
            "tieredSeam bonds two tiers of the SAME node, but local.selfId=${localTier.selfId} " +
                "!= peer.selfId=${peerTier.selfId}"
        }
    }

    // Child of the injected scope so close() cancels the pumps without tearing the caller's scope.
    // The SupervisorJob must go on the RIGHT of `plus`: on a Job-key collision the right operand
    // wins, so this scope's Job is our SupervisorJob (a child of the parent Job — parent
    // cancellation still propagates down) and `scope.cancel()` cancels only our pumps. If the
    // SupervisorJob were on the left it would be dropped and scope.cancel() would tear the caller.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    override val selfId: PeerId = localTier.selfId

    // Guards the _peers write (combine collector) against the roster collapse, with the collapse
    // marker folded into the same critical section so a post-collapse emission cannot resurrect it.
    private val peersLock = reentrantLock()

    // Set by [collapseRoster], read by the union pump — both under [peersLock], never apart. It is
    // NOT a second lifecycle latch ([stateGate] is still the single-shot close gate); it exists
    // because the collapse must be published BEFORE `Torn` becomes observable, so the pump can no
    // longer key its guard on `state`: in the window between the collapse and the latch this seam is
    // not yet Torn, and a union emission landing there would republish the roster PERMANENTLY (the
    // pump's next trigger is another tier edge, and a torn tier has none). Guarding on a marker set
    // in the same critical section as the collapse makes that window unrepresentable rather than
    // narrow — check-and-write are one atomic step, not the check-then-set [SeamStateGate] bans.
    private var collapsed = false

    // Union roster. Written by the single combine-collector below and by [collapseRoster], both
    // under peersLock.
    private val _peers = MutableStateFlow(localTier.peers.value + peerTier.peers.value)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Composed lifecycle, terminal-latched. The state pump feeds recoverable rollups (Woven/Weaving)
    // through update(); a both-tiers-torn rollup and close() both latch the terminal Torn via tear()
    // (single-shot). No in-flight rollup can clobber the terminal state, and a latched Torn never
    // reverts even if a tier's state later flaps.
    private val stateGate = SeamStateGate(rollup(localTier.state.value, peerTier.state.value))
    override val state: StateFlow<SeamState> = stateGate.state

    // Merged inbound. This seam is the SOLE collector of both tiers' incoming; the merged stream
    // is itself single-collection (Spool). Closed when both tiers' incoming complete (below).
    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Counts tiers whose incoming is still live; the merged spool closes when it hits zero.
    private val liveIncoming = atomic(2)

    init {
        // Union roster pump: one collector. The write + collapse-check are one critical section under
        // peersLock, so a roster collapse is never overwritten — see [collapsed].
        combine(localTier.peers, peerTier.peers) { a, b -> a + b }
            .onEach { union -> peersLock.withLock { if (!collapsed) _peers.value = union } }
            .launchIn(scope)

        // Composed lifecycle pump. A recoverable rollup (Woven/Weaving) is a derived write via
        // update() — a no-op once close() has latched Torn. A both-tiers-torn rollup is TERMINAL for a
        // tiered union (its one-shot merged `incoming` completes and never re-subscribes), so it is
        // latched via tear() — self-driven death of both tiers publishes a terminal Torn, not a
        // revivable one. The normal close() path tears first and wins the single-shot latch; this pump
        // then no-ops. Either way the latch means a later tier flap can never move state off Torn.
        //
        // The terminal branch collapses the roster FIRST, for the same reason [close] does: this is
        // the *self-driven* death path (both tiers torn with nobody calling close), and it publishes
        // exactly the same terminal `Torn` a consumer waits on. Latching here without collapsing
        // would leave a torn union advertising the pre-death roster forever.
        combine(localTier.state, peerTier.state) { l, p -> rollup(l, p) }
            .onEach { s ->
                if (s is SeamState.Torn) {
                    collapseRoster()
                    stateGate.tear(s.reason)
                } else {
                    stateGate.update(s)
                }
            }
            .launchIn(scope)

        // Sole collection of each tier's incoming, teed into the one merged spool. When a tier's
        // incoming completes (that tier torn), decrement; the merged incoming completes only once
        // BOTH tiers' incoming have — matching "terminal Torn ⇔ both tiers torn". This one-shot merge
        // (no re-subscribe) is exactly why both-tiers-torn is a terminal, latched Torn, not a
        // recoverable Weaving — see the state pump and rollup above (#1367).
        localTier.incoming
            .onEach { spool.deliver(it) }
            .onCompletion { closeSpoolIfBothDone() }
            .launchIn(scope)
        peerTier.incoming
            .onEach { spool.deliver(it) }
            .onCompletion { closeSpoolIfBothDone() }
            .launchIn(scope)
    }

    /**
     * Collapse [peers] to `{ selfId }` and shut the union pump out of it, as one critical section.
     *
     * [Seam.peers] requires a `Torn` seam's roster to be exactly `{ selfId }` — **not** `emptySet()`:
     * `peers` always includes this peer's own id, so collapsing to empty overshoots the contract in
     * the other direction. And it requires the collapse to be published *before* the terminal latch,
     * which is why every caller runs this **ahead of** [SeamStateGate.tear] rather than after it.
     *
     * Idempotent, so both call sites — [close] (including a losing second one) and the state pump's
     * self-driven terminal branch — may run it freely.
     */
    private fun collapseRoster() = peersLock.withLock {
        collapsed = true
        _peers.value = setOf(selfId)
    }

    private fun closeSpoolIfBothDone() {
        if (liveIncoming.decrementAndGet() == 0) spool.close()
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "TieredSeam is Torn" }
        // Tee to BOTH tiers, independently. Best-effort per side: the point of bonding two tiers
        // is that one failing must not stop the other from carrying the broadcast. kuilt-core is
        // logger-free by contract, so a failed side is swallowed here (surfaced by the enclosing
        // logging layer if a tier's own send path reports it).
        runCatchingCancellable { localTier.broadcast(payload) }
        runCatchingCancellable { peerTier.broadcast(payload) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "TieredSeam is Torn" }
        // Route to the owning tier — NEVER fan to both (that would breach the single-addressee
        // leak boundary, ADR-005). Local is checked first, so a shared/overlapping id resolves
        // there. An id owned by neither tier is dropped (silent — kuilt-core is logger-free).
        when {
            peer in localTier.peers.value -> localTier.sendTo(peer, payload)
            peer in peerTier.peers.value -> peerTier.sendTo(peer, payload)
            else -> Unit
        }
    }

    override suspend fun close(reason: CloseReason) {
        // Collapse the roster to { selfId } BEFORE latching Torn: [Seam.peers] makes that an ORDERED
        // obligation, so a consumer woken by the terminal state already observes the collapse. The
        // resurrection hazard the old latch-first order guarded against is handled by [collapsed]
        // instead, which is strictly stronger — it closes the window rather than relying on the pump
        // reading a `state` that, mid-close, has not been latched yet.
        //
        // Still single-shot via the gate: tear() returns false for a loser. The collapse ahead of it
        // is idempotent, so an unwinnable second close costs one identical write. Correctness does
        // not depend on cancelling the pumps first — the gate makes a late update() a harmless no-op
        // — so the scope is cancelled last, purely to release the pump coroutines.
        //
        // [peersLock] is deliberately NOT held across `tear`: that write resumes `state` collectors,
        // which can run consumer code inline and re-enter this seam. Holding peersLock across it
        // would invert the lock order against the union pump.
        collapseRoster()
        if (!stateGate.tear(reason)) return
        spool.close()
        localTier.close(reason)
        peerTier.close(reason)
        scope.cancel()
    }

    private companion object {
        // Any-live ⇒ Woven; both tiers torn ⇒ TERMINAL Torn (carrying the local tier's reason);
        // otherwise Weaving. Unlike [us.tractat.kuilt.core.composite.CompositeSeam] — whose persistent
        // [Spool] survives ply churn so its all-plies-torn rollup is recoverable [SeamState.Weaving]
        // (#1367) — a tiered union's [incoming] is a ONE-SHOT merge: it completes permanently when both
        // tiers' `incoming` complete, with no re-subscribe. So both-tiers-torn is genuinely terminal
        // and the state pump latches it via `tear()` (a recoverable Weaving would contradict a
        // terminally-completed `incoming` and hang a `state.first { it is Torn }` waiter).
        fun rollup(local: SeamState, peer: SeamState): SeamState =
            when {
                local is SeamState.Woven || peer is SeamState.Woven -> SeamState.Woven
                local is SeamState.Torn && peer is SeamState.Torn -> local
                else -> SeamState.Weaving
            }
    }
}
