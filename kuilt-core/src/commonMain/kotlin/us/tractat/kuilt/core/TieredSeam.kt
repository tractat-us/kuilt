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
 * - **[state]** is the composed lifecycle: **Woven** while *either* tier is Woven, **Torn** only
 *   once *both* tiers are Torn (a broadcast still reaches whichever tier survives), **Weaving**
 *   otherwise — the same "any-live ⇒ live" rollup [us.tractat.kuilt.core.composite.CompositeSeam]
 *   uses. [close] closes both tiers.
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

    // Guards the _peers write (combine collector) against close()'s roster collapse, with the
    // torn-check folded into the same critical section so a post-close emission cannot resurrect it.
    private val peersLock = reentrantLock()

    // Union roster. Written by the single combine-collector below and by close()'s collapse, both
    // under peersLock.
    private val _peers = MutableStateFlow(localTier.peers.value + peerTier.peers.value)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Composed lifecycle, terminal-latched: the state pump feeds update() (revivable), close() latches
    // Torn via tear() (single-shot). No in-flight rollup can clobber the terminal state.
    private val stateGate = SeamStateGate(rollup(localTier.state.value, peerTier.state.value))
    override val state: StateFlow<SeamState> = stateGate.state

    // Merged inbound. This seam is the SOLE collector of both tiers' incoming; the merged stream
    // is itself single-collection (Spool). Closed when both tiers' incoming complete (below).
    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Counts tiers whose incoming is still live; the merged spool closes when it hits zero.
    private val liveIncoming = atomic(2)

    init {
        // Union roster pump: one collector. The write + torn-check are one critical section under
        // peersLock, so a close() that latched Torn (and collapsed the roster) is never overwritten.
        combine(localTier.peers, peerTier.peers) { a, b -> a + b }
            .onEach { union -> peersLock.withLock { if (state.value !is SeamState.Torn) _peers.value = union } }
            .launchIn(scope)

        // Composed lifecycle pump: a derived write. update() no-ops once close() has latched Torn.
        combine(localTier.state, peerTier.state) { l, p -> rollup(l, p) }
            .onEach { stateGate.update(it) }
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
        // Single-shot via the gate: tear() latches Torn and returns false for a loser. Latch BEFORE
        // collapsing the roster so a peers-pump emission that acquires peersLock after the collapse
        // sees Torn and no-ops (peers-before-state + no resurrection). Correctness no longer depends
        // on cancelling the pumps first — the gate makes a late update() a harmless no-op — so the
        // scope is cancelled last, purely to release the pump coroutines.
        if (!stateGate.tear(reason)) return
        peersLock.withLock { _peers.value = emptySet() }
        spool.close()
        localTier.close(reason)
        peerTier.close(reason)
        scope.cancel()
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
