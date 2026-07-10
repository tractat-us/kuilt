package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
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
 * - **[state]** is the composed lifecycle: **Woven** while *either* tier is Woven, **Torn** only
 *   once *both* tiers are Torn (a broadcast still reaches whichever tier survives), **Weaving**
 *   otherwise — the same "any-live ⇒ live" rollup [us.tractat.kuilt.core.composite.CompositeSeam]
 *   uses. [close] closes both tiers.
 *
 * ## Thread safety
 *
 * Correct under a **multi-threaded** dispatcher, by real primitives — never single-thread
 * confinement. [peers] and [state] are backed by [MutableStateFlow]s written from a *single*
 * `combine` collector coroutine each (so their updates are already serialized); [incoming] flows
 * through a bounded [Spool]. The only cross-coroutine race — [close] versus those pumps — is
 * closed by a single-shot atomic flag plus cancelling the internal [scope] before publishing the
 * terminal `Torn`/empty values. There is no lock because there is no unguarded shared mutable
 * state: every field is either a thread-safe flow primitive or the atomic close latch.
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

    // Single-shot teardown latch: only the first close() publishes Torn and tears down.
    private val closed = atomic(false)

    override val selfId: PeerId = localTier.selfId

    // Union roster. Written only by the single combine-collector below (serialized), plus the
    // terminal empty published by close() after the pump is cancelled.
    private val _peers = MutableStateFlow(localTier.peers.value + peerTier.peers.value)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Composed lifecycle. Same single-writer discipline as _peers.
    private val _state = MutableStateFlow(rollup(localTier.state.value, peerTier.state.value))
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    // Merged inbound. This seam is the SOLE collector of both tiers' incoming; the merged stream
    // is itself single-collection (Spool). Closed when both tiers' incoming complete (below).
    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Counts tiers whose incoming is still live; the merged spool closes when it hits zero.
    private val liveIncoming = atomic(2)

    init {
        // Union roster pump: one collector, so _peers writes are serialized.
        combine(localTier.peers, peerTier.peers) { a, b -> a + b }
            .onEach { if (!closed.value) _peers.value = it }
            .launchIn(scope)

        // Composed lifecycle pump.
        combine(localTier.state, peerTier.state) { l, p -> rollup(l, p) }
            .onEach { if (!closed.value) _state.value = it }
            .launchIn(scope)

        // Sole collection of each tier's incoming, teed into the one merged spool. When a tier's
        // incoming completes (that tier torn), decrement; the merged incoming completes only once
        // BOTH have — matching "Torn ⇔ both tiers Torn".
        localTier.incoming
            .onEach { spool.deliver(it) }
            .onCompletion { closeSpoolIfBothDone() }
            .launchIn(scope)
        peerTier.incoming
            .onEach { spool.deliver(it) }
            .onCompletion { closeSpoolIfBothDone() }
            .launchIn(scope)
    }

    private fun closeSpoolIfBothDone() {
        if (liveIncoming.decrementAndGet() == 0) spool.close()
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { "TieredSeam is Torn" }
        // Tee to BOTH tiers, independently. Best-effort per side: the point of bonding two tiers
        // is that one failing must not stop the other from carrying the broadcast. kuilt-core is
        // logger-free by contract, so a failed side is swallowed here (surfaced by the enclosing
        // logging layer if a tier's own send path reports it).
        runCatchingCancellable { localTier.broadcast(payload) }
        runCatchingCancellable { peerTier.broadcast(payload) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { "TieredSeam is Torn" }
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
        // Single-shot: only the first caller tears down.
        if (!closed.compareAndSet(expect = false, update = true)) return
        // Stop the pumps first, then publish the terminal values so a lingering pump emission
        // cannot overwrite them (the pumps also guard on `closed`).
        scope.cancel()
        _state.value = SeamState.Torn(reason)
        _peers.value = emptySet()
        spool.close()
        localTier.close(reason)
        peerTier.close(reason)
    }

    private companion object {
        /** Any-live ⇒ live; Torn only when both tiers are Torn (first tier's reason); else Weaving. */
        fun rollup(local: SeamState, peer: SeamState): SeamState =
            when {
                local is SeamState.Woven || peer is SeamState.Woven -> SeamState.Woven
                local is SeamState.Torn && peer is SeamState.Torn -> local
                else -> SeamState.Weaving
            }
    }
}
