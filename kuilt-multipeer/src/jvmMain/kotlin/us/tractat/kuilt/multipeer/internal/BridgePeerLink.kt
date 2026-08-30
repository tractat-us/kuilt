package us.tractat.kuilt.multipeer.internal

import com.sun.jna.Pointer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerIdentityRegistry
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.MultipeerNativeLib
import kotlin.coroutines.CoroutineContext

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.internal.BridgePeerLink")

/**
 * JVM-side `Seam` proxying through the macOS K/N MC bridge.
 *
 * Callbacks registered on construction populate live flows:
 *  - `mc_session_set_peer_state_callback` → [_peers] gets remote peers
 *    added/removed as MC fires `peer didChangeState`.
 *  - `mc_session_set_data_callback` → frames are routed through a bounded
 *    [Spool] governed by [policy].
 *
 * **JNA-to-coroutine delivery bridge.** The MC data callback fires on a JNA
 * thread (non-suspending). Frames are deposited into a bounded [bridge]
 * channel via [Channel.trySend] (never blocks), then a single dedicated drain
 * coroutine forwards them to [spool] in FIFO order. This preserves delivery
 * ordering while keeping the JNA callback non-blocking. The bridge channel is
 * sized by [policy.capacity]; overflow on the bridge drops the oldest frame
 * (lossy at the JNA boundary) before reaching the spool's own policy.
 *
 * The callback objects are held as fields so JNA's trampoline survives the
 * lifetime of the link. Releasing them (by setting the fields to null
 * inside [close]) is what eventually lets JNA free the trampoline; the
 * underlying `mc_session_close` cancels the K/N pump first.
 *
 * @param policy Governs the inbound [Spool]'s capacity and overflow behaviour.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The scope for the delivery drain coroutine (scheduling only).
 *   Production callers use the default [Dispatchers.Default]; tests pass a dispatcher
 *   derived from the test scheduler so virtual-time control works.
 */
internal class BridgePeerLink(
    private val nativeLib: MultipeerNativeLib,
    private val sessionHandle: Pointer,
    override val selfId: PeerId,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // The one implementation of "may this announced identity join, and who may take it away"
    // (#1821), shared with the Apple `MCSessionLink` and `:kuilt-nearby`. It is the source of truth
    // for the remote roster: every mutation republishes `registry.peers + selfId` into [_peers], and
    // the terminal-teardown test asks the registry rather than comparing [_peers] to `{ selfId }`.
    //
    // **`T` is the PeerId itself, and that is a real limit, not a shortcut.**
    // `MultipeerNativeLib.PeerStateCallback` carries `(peerId: String, isConnected: Int)` and
    // nothing else — the native bridge has already flattened the `MCPeerID` device identity that
    // the Apple half keys by into a bare string. So two devices sharing one display name have
    // become one identity BELOW this file, and `BindResult.COLLISION` is structurally unreachable
    // here; un-merging them needs a device handle on the ABI (#1539), not a change in this class.
    // What the registry does buy this path is the rest of the rules: a blank id is refused, a
    // self-dial is refused, and `unbind` is identity-scoped, so a drop for an id nothing holds
    // evicts nobody.
    private val registry = PeerIdentityRegistry<PeerId>(selfId)

    private val _peers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Starts Weaving; transitions to Woven on first peer-connected callback.
    private val _state: MutableStateFlow<SeamState> = MutableStateFlow(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    // Bounded staging channel: the JNA data callback deposits frames here non-suspendingly.
    // DROP_OLDEST overflow so the callback never blocks; the single drain coroutine forwards
    // to the spool in FIFO order, applying the spool's own policy from there.
    private val bridge: Channel<Swatch> =
        Channel(capacity = policy.capacity, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // CAS-latched by closeNow() before mc_session_close; read by the JNA
    // callbacks and the send paths. AtomicBoolean (not @Volatile) so a
    // concurrent seam.close() / factory.close() pair can never both pass the
    // latch and double-call mc_session_close — a use-after-free per the
    // native bridge contract ("passing the same non-null pointer twice").
    // Set ONLY by closeNow(): it means "native close issued / suppress the
    // .notConnected warn". The self-driven drop path leaves it false (the drop
    // IS unexpected, so it must still warn) and disposes no native handle.
    private val closing = AtomicBoolean(false)

    // SEPARATE single-shot latch for the seam's terminal state+resource teardown
    // (state→Torn, bridge/spool close, scope cancel). Distinct from `closing`:
    // the self-driven drop path tears the seam down (so `state` reaches Torn and
    // `incoming` completes per the Seam contract) WITHOUT issuing mc_session_close,
    // while an explicit close() does both. One CAS winner across both paths so a
    // drop followed by a consumer close() never double-closes bridge/spool or
    // re-cancels the scope.
    private val tornDown = AtomicBoolean(false)

    // Strong refs so JNA trampolines aren't GC'd before the K/N side
    // finishes pumping. Held for this link's whole lifetime — they outlive
    // mc_session_close (after which native never calls back), then fall away
    // with the link itself, so they can never be collected while still in use.
    private val dataCallback: MultipeerNativeLib.DataCallback =
        MultipeerNativeLib.DataCallback { peerId, data, len ->
            val bytes = if (len > 0) data.getByteArray(0, len) else ByteArray(0)
            val frame = Swatch(payload = bytes, sender = PeerId(peerId))
            // Non-suspending deposit into the bridge channel. The drain coroutine
            // forwards to spool.deliver in FIFO order.
            bridge.trySend(frame)
        }

    private val peerStateCallback: MultipeerNativeLib.PeerStateCallback =
        MultipeerNativeLib.PeerStateCallback { peerId, isConnected ->
            val peer = PeerId(peerId)
            // Self-connection guard, ahead of the branch so it covers EVERY state rather than just
            // `connected` — the placement `MCSessionLink`'s delegate argues for, and for the same
            // reason. The end-of-session test below asks "are there no bound remotes?", which cannot
            // by itself tell *before the first peer* from *after the last one*; a self `.notConnected`
            // reaching it would unbind nothing, find an empty registry, and tear down a Weaving link
            // that never had a peer.
            //
            // The RULE is still the registry's — [PeerIdentityRegistry.BindResult.REFUSED_SELF] below
            // is the same refusal and this line cannot drift from it, because both spell self-identity
            // as `selfId`. What is fabric-specific, and so lives here, is WHICH callback states the
            // refusal has to cover.
            if (peer == selfId) {
                log.info {
                    "mc.session.self-dial selfId=${selfId.value} peer=$peerId connected=$isConnected " +
                        "→ dropped (connected to own advertisement)"
                }
                return@PeerStateCallback
            }
            if (isConnected == 1) {
                when (registry.bind(peer, peer)) {
                    PeerIdentityRegistry.BindResult.BOUND -> {
                        _peers.value = registry.peers + selfId
                        if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
                    }
                    PeerIdentityRegistry.BindResult.ALREADY_BOUND -> Unit // duplicate connect callback
                    // See the [registry] comment: with `T` = the id itself this arm cannot fire.
                    // Left explicit rather than folded into a shared `else` so it stays visible as
                    // the thing the ABI, not this file, has to fix.
                    PeerIdentityRegistry.BindResult.COLLISION ->
                        log.error {
                            "mc.session.collision selfId=${selfId.value} peer=$peerId — " +
                                "refusing to merge two distinct devices onto one id"
                        }
                    // Unreachable from here — the guard above the branch already returned. Kept as
                    // an arm rather than folded into an `else` so a future edit that removes that
                    // guard still lands on a refusal instead of a silent bind (#1821).
                    PeerIdentityRegistry.BindResult.REFUSED_SELF ->
                        log.info {
                            "mc.session.self-dial selfId=${selfId.value} peer=$peerId → refused by the registry"
                        }
                    // A blank id from native. Admitting one used to put an unaddressable entry in
                    // `peers` that the old `remaining == setOf(selfId)` teardown test could never
                    // clear, so the seam stayed Woven after its last real peer had gone (#1821).
                    PeerIdentityRegistry.BindResult.REFUSED_BLANK ->
                        log.error { "mc.session.blank-id selfId=${selfId.value} — refusing a blank peer id" }
                }
            } else {
                // MC has no dedicated error callback; .notConnected is the
                // closest session-level error surface (unexpected drops fire here).
                // Suppress the warn when closing — that .notConnected is from our
                // own mc_session_close, not an unexpected drop.
                if (!closing.get()) {
                    log.warn { "mc.session.error selfId=${selfId.value} peer=$peerId" }
                }
                // Identity-scoped removal: a drop for an id this device does not hold — a refused
                // self-dial, a blank id, a peer that never bound — removes nothing, so it cannot
                // take a live peer with it.
                registry.unbind(peer, peer)
                _peers.value = registry.peers + selfId
                // Terminal peer-level drop. When the last remote peer is gone the
                // whole session is dead — tear the seam down (latch Torn, complete
                // `incoming`) so the Seam contract holds on a remote disconnect. The
                // latched Torn is the sole signal the owning factory's ActiveSeamSlot
                // reads to free its single-session slot on the next weave — no
                // side-channel callback. No mc_session_close here: it runs inside the
                // JNA callback and the native handle is disposed only by the consumer's
                // explicit close(). Mirrors the apple MCSessionLink behaviour.
                //
                // Asked of the REGISTRY ("are there no bound remotes?") rather than as the old
                // set-equality test `remaining == setOf(selfId)` on `_peers`. The two agree only
                // while `_peers` holds nothing but selfId and live remotes — which is exactly the
                // property a blank or self id used to break, wedging the seam Woven forever (#1821).
                if (registry.peers.isEmpty()) {
                    tearDown(CloseReason.RemoteRequested)
                }
            }
        }

    init {
        nativeLib.mc_session_set_data_callback(sessionHandle, dataCallback)
        nativeLib.mc_session_set_peer_state_callback(sessionHandle, peerStateCallback)

        // Single drain coroutine: forwards frames from the JNA bridge to the spool in FIFO order.
        // Running a single coroutine (rather than one per frame) preserves delivery ordering.
        scope.launch {
            for (frame in bridge) {
                spool.deliver(frame)
            }
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        // A Torn seam rejects sends per the shared Seam contract — set by an explicit
        // close() or a self-driven last-peer drop (tearDown). Checked before the
        // `closing` gate so a send racing an in-progress close() (closing latched, Torn
        // not yet set) still no-ops rather than throwing, protecting the native handle.
        check(_state.value !is SeamState.Torn) { "broadcast on a Torn seam" }
        if (closing.get()) return
        if (_peers.value.none { it != selfId }) {
            log.warn { "mc.session.send dropped — no connected peers localPeer=${selfId.value} bytes=${payload.size}" }
            return
        }
        nativeLib.mc_session_broadcast(sessionHandle, payload, payload.size)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        check(_state.value !is SeamState.Torn) { "sendTo on a Torn seam" }
        // Ahead of the `closing` short-circuit and the roster check: `_peers` includes selfId, so
        // without this the bridge handed its OWN id to `mc_session_send_to` as a native addressee
        // (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (closing.get()) return
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        val sent = nativeLib.mc_session_send_to(sessionHandle, peer.value, payload, payload.size)
        if (sent < 0) {
            error("mc_session_send_to failed for ${peer.value}; peer may be disconnected")
        }
    }

    override suspend fun close(reason: CloseReason) {
        closeNow(reason)
    }

    /**
     * Non-suspending body of [close] so the owning factory (whose `close()` is
     * not suspend) can share it. CAS-idempotent: exactly one caller wins the
     * latch, so `mc_session_close` runs exactly once per handle regardless of
     * how `Seam.close()` / factory close interleave.
     */
    internal fun closeNow(reason: CloseReason) {
        // Set closing before mc_session_close so the peer-state callback sees it
        // when MC fires .notConnected for the clean disconnect — suppressing the
        // spurious mc.session.error warn.
        if (!closing.compareAndSet(false, true)) return
        // Terminal state+resource teardown (single-shot; a no-op if a self-driven
        // drop already tore the seam down). This is the ONLY path that disposes the
        // native handle: mc_session_close runs exactly once per handle because
        // `closing` gates it above.
        tearDown(reason)
        nativeLib.mc_session_close(sessionHandle)
    }

    /**
     * Single-shot terminal teardown — latch [SeamState.Torn], close the JNA
     * [bridge] and the [spool] (completing [incoming] per the `Seam` contract),
     * cancel [scope]. Shared by the self-driven drop path and [closeNow]; the
     * [tornDown] CAS makes it run once even if a drop and a consumer [close]
     * interleave. Issues no native call — the native handle is disposed only by
     * [closeNow]. The latched `Torn` is what the owning factory's `ActiveSeamSlot`
     * reads to free its single-session slot on the next weave.
     *
     * Collapses [_peers] to `{ selfId }` **before** latching `Torn` (#1816): a torn fabric can
     * reach nobody, and a decorator folding this seam reads whatever is left here as still
     * reachable until the member is detached — so the roster must already be collapsed by the
     * time anyone can observe `Torn`. The collapse sits ahead of the [tornDown] CAS, as in
     * `LinkSeam.tearDown`: it is idempotent (always `setOf(selfId)`), so running it on a losing
     * caller costs nothing and buys the stronger post-condition that `peers` is collapsed once
     * *any* `tearDown` has returned. Previously only the remote-drop path reached `{ selfId }`,
     * leaving a locally-closed link advertising its pre-close roster forever (#1851).
     *
     * Clearing [registry] is **part of** that collapse, not housekeeping — the same argument
     * `MCSessionLink.tearDown` makes, and it becomes load-bearing here the moment [_peers] is
     * derived from the registry: [closeNow] follows this with `mc_session_close`, after which
     * native fires `.notConnected` for every peer that was connected, and the first such callback
     * would recompute `registry.peers + selfId` from stale bindings and republish peers that are
     * gone (#1851).
     */
    private fun tearDown(reason: CloseReason) {
        registry.clear()
        _peers.value = setOf(selfId)
        if (!tornDown.compareAndSet(false, true)) return
        _state.value = SeamState.Torn(reason)
        bridge.close()
        spool.close()
        scope.cancel()
    }
}
