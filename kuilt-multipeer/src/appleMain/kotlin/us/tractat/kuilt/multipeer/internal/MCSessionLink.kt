package us.tractat.kuilt.multipeer.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSProgress
import platform.Foundation.NSURL
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionDelegateProtocol
import platform.MultipeerConnectivity.MCSessionSendDataMode
import platform.MultipeerConnectivity.MCSessionState
import platform.darwin.NSObject
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.internal.MCSessionLink")

/**
 * `Seam` backed by an `MCSession`.
 *
 * The class owns a private `MCSessionDelegate` that fans MC callbacks
 * (`peer didChangeState`, `didReceiveData`) onto:
 *  - [peers] — set of connected peer IDs (always includes [selfId]).
 *  - [incoming] — frames received from any other peer.
 *
 * **MC-delegate-to-coroutine delivery bridge.** MC delegate callbacks fire on
 * the framework's private queue (non-suspending). Frames from `didReceiveData`
 * are deposited into a bounded [bridge] channel via [Channel.trySend] (never
 * blocks the callback thread). A single dedicated drain coroutine then forwards
 * them to [spool] in FIFO order — preserving delivery ordering while keeping
 * the delegate callback non-blocking and bounded (no UNLIMITED).
 *
 * Two-way mapping from `MCPeerID` ↔ [PeerId]: the peer's `displayName` is the
 * wire identity (Apple exposes no stable cross-process id). The advertised
 * display name embeds a per-device nonce (see [MultipeerPeerId]), so two
 * default-named "iPhone" devices no longer collapse to one [PeerId]. As
 * defence-in-depth, peer membership is keyed by the underlying `MCPeerID`
 * device identity through a [PeerIdentityRegistry]: a second distinct device
 * that still (pathologically) hit one id is REFUSED rather than merged, and a
 * disconnect only ever evicts the device that actually holds the id — so a drop
 * can never evict the wrong peer (#1494 / the #1466 class).
 *
 * @param policy Governs the inbound [Spool]'s capacity and overflow behaviour.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The context for the delivery drain coroutine (scheduling only).
 *   Production callers use the default [Dispatchers.Default]; tests pass a context
 *   derived from the test scheduler so virtual-time control works.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MCSessionLink(
    private val localPeerId: MCPeerID,
    internal val session: MCSession,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : Seam {
    override val selfId: PeerId = MultipeerPeerId.peerId(localPeerId.displayName)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Membership keyed by the MCPeerID device identity, not by the bare PeerId,
    // so a disconnect evicts only the device that holds the id (never the wrong
    // peer), and a distinct device colliding on one id is refused, not merged.
    // selfId is never registered — it is always folded into `_peers` below.
    //
    // The registry is internally lock-guarded (MC fires didChangeState with no
    // cross-peer serialization guarantee) and is the source of truth for the
    // peer set; each mutation republishes `registry.peers + selfId` to `_peers`,
    // a StateFlow whose value write is itself atomic.
    private val registry = PeerIdentityRegistry<MCPeerID>()

    private val _peers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Starts Weaving; transitions to Woven on first MCSessionStateConnected callback.
    private val _state: MutableStateFlow<SeamState> = MutableStateFlow(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    // Bounded staging channel: the MC delegate callback deposits frames here non-suspendingly.
    // DROP_OLDEST overflow so the callback never blocks; the single drain coroutine forwards
    // to the spool in FIFO order, applying the spool's own policy from there.
    private val bridge: Channel<Swatch> =
        Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    val delegate: MCSessionDelegateProtocol = SessionDelegate()

    // Written by close() before disconnect(); read by the MC delegate callback.
    // Means "we issued session.disconnect() — suppress the .notConnected warn".
    // The self-driven drop path leaves it false (the drop IS unexpected, so it
    // must still warn) and calls no disconnect (ARC reclaims the dropped session).
    // No @Volatile here — K/N's memory model (since 1.7.20) makes plain var
    // writes visible across threads; @Volatile is JVM-only.
    private var closing: Boolean = false

    // SEPARATE single-shot latch for the seam's terminal state+resource teardown
    // (state→Torn, bridge/spool close, scope cancel). Distinct from `closing`:
    // the self-driven drop path tears the seam down (so `state` reaches Torn and
    // `incoming` completes per the Seam contract) WITHOUT calling disconnect(),
    // while close() does both. One CAS winner across both paths so a drop followed
    // by a consumer close() never double-closes bridge/spool or re-cancels scope.
    private val tornDown = atomic(false)

    init {
        // Single drain coroutine: forwards frames from the MC delegate bridge to the spool in
        // FIFO order. Running a single coroutine (rather than one per frame) preserves ordering.
        scope.launch {
            for (frame in bridge) {
                spool.deliver(frame)
            }
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        // A Torn seam rejects sends per the shared Seam contract — latched either by close() or by
        // the delegate's last-peer drop. `BridgePeerLink` has carried this since #1390; the Apple
        // half never got it, so a torn link warn-dropped the frame and told the caller it went out
        // (#2444).
        //
        // It must sit AHEAD of the `connectedPeers` read, not merely somewhere in the method. The
        // two tears differ in what the session reports afterwards: close() calls
        // session.disconnect(), so `connectedPeers` empties and the send would fall into the
        // no-peers warn-drop below; the last-peer drop issues no disconnect, so `connectedPeers`
        // can still name a peer MC has since lost and the send would look ordinary. Only a check
        // that runs before the read covers both.
        check(_state.value !is SeamState.Torn) { "broadcast on a Torn seam" }
        val targets = session.connectedPeers
        if (targets.isEmpty()) {
            log.warn { "mc.session.send dropped — no connected peers localPeer=${selfId.value} bytes=${payload.size}" }
            return
        }
        log.debug { "mc.session.send localPeer=${selfId.value} targets=${targets.size} bytes=${payload.size}" }
        session.sendData(
            data = payload.toNSData(),
            toPeers = targets,
            withMode = MCSessionSendDataMode.MCSessionSendDataReliable,
            error = null,
        )
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        // Ahead of BOTH the self-send guard and the `connectedPeers` lookup (#2444). Ahead of the
        // lookup because otherwise a closed link answers with PeerNotConnected — which blames the
        // addressee for the seam's own death, and is an IllegalStateException, so the conformance
        // suite's `assertFailsWith<IllegalStateException>` could not tell the two apart.
        // `MCSessionLinkTornSendTest` is what pins the distinction.
        check(_state.value !is SeamState.Torn) { "sendTo on a Torn seam" }
        // `connectedPeers` is the remotes MC has connected, never this device, so without this a
        // self-send fell out as PeerNotConnected — false for an id `peers` names (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val target =
            session.connectedPeers
                .filterIsInstance<MCPeerID>()
                .firstOrNull { it.displayName == peer.value }
                ?: throw PeerNotConnected(peer)
        log.debug { "mc.session.send localPeer=${selfId.value} toPeer=${peer.value} bytes=${payload.size}" }
        session.sendData(
            data = payload.toNSData(),
            toPeers = listOf(target),
            withMode = MCSessionSendDataMode.MCSessionSendDataReliable,
            error = null,
        )
    }

    override suspend fun close(reason: CloseReason) {
        // Set closing before disconnect() so the MC delegate sees it when
        // session:peer:didChangeState fires .notConnected for the clean
        // disconnect — suppressing the spurious mc.session.error warn.
        closing = true
        // Terminal state+resource teardown (single-shot; a no-op if a self-driven
        // drop already tore the seam down). close() is the only path that calls
        // session.disconnect().
        tearDown(reason)
        session.disconnect()
    }

    /**
     * Single-shot terminal teardown — latch [SeamState.Torn], close the MC-delegate
     * [bridge] and the [spool] (completing [incoming] per the `Seam` contract),
     * cancel [scope]. Shared by the self-driven drop path and [close]; the [tornDown]
     * CAS makes it run once even if a drop and a consumer [close] interleave. Issues no
     * `session.disconnect()` — ARC reclaims the dropped `MCSession`; only [close]
     * disconnects. The latched `Torn` is what the owning factory's `ActiveSeamSlot`
     * reads to free its single-session slot on the next weave.
     *
     * Collapses the roster to `{ selfId }` **before** latching `Torn` (#1816): a torn fabric can
     * reach nobody, and a decorator folding this seam reads whatever is left here as still
     * reachable until the member is detached. The collapse sits ahead of the [tornDown] CAS, as in
     * `LinkSeam.tearDown`: it is idempotent, so running it on a losing caller costs nothing and
     * buys the stronger post-condition that `peers` is collapsed once *any* `tearDown` has
     * returned. Previously only the remote-drop path reached `{ selfId }`, leaving a locally-closed
     * link advertising its pre-close roster forever (#1851).
     *
     * Clearing [registry] is **part of** that collapse, not housekeeping. The registry — not
     * `_peers` — is the source of truth the delegate recomputes the roster from
     * (`registry.peers + selfId`), and [close] follows this call with `session.disconnect()`,
     * which makes MC fire `.notConnected` for every peer that was connected. Were a stale binding
     * left behind, the first such callback would republish the *other* still-bound peers onto an
     * already-`Torn` seam and undo the collapse — so on an N-peer session the roster would only
     * re-converge if every remaining callback arrived, which is the very thing #1851 says cannot
     * be relied on. `MeshSeam.tearDown` clears its `links` map for the same reason.
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

    private inner class SessionDelegate :
        NSObject(),
        MCSessionDelegateProtocol {
        override fun session(
            session: MCSession,
            peer: MCPeerID,
            didChangeState: MCSessionState,
        ) {
            val peerId = MultipeerPeerId.peerId(peer.displayName)
            val stateName =
                when (didChangeState) {
                    MCSessionState.MCSessionStateConnected -> "[Connected]"
                    MCSessionState.MCSessionStateConnecting -> "[Connecting]"
                    MCSessionState.MCSessionStateNotConnected -> "[Not Connected]"
                    else -> "[Unknown($didChangeState)]"
                }
            log.info { "mc.session.stateChange localPeer=${selfId.value} peer=${peer.displayName} to=$stateName" }
            when (didChangeState) {
                MCSessionState.MCSessionStateConnected -> {
                    when (registry.bind(peerId, peer)) {
                        PeerIdentityRegistry.BindResult.BOUND -> {
                            _peers.value = registry.peers + selfId
                            if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
                        }
                        PeerIdentityRegistry.BindResult.ALREADY_BOUND -> Unit // duplicate connect callback
                        PeerIdentityRegistry.BindResult.COLLISION ->
                            // A DIFFERENT device advertising an id already held. Refuse the
                            // merge (the incumbent keeps the id) and surface it — with
                            // per-device nonces this should be unreachable in practice.
                            log.error {
                                "mc.session.collision localPeer=${selfId.value} peer=${peer.displayName} " +
                                    "id=${peerId.value} — refusing to merge two distinct devices onto one id"
                            }
                    }
                }
                MCSessionState.MCSessionStateNotConnected -> {
                    // MCSession has no dedicated error callback; .notConnected is the
                    // closest session-level error surface (unexpected drops fire here).
                    // Suppress the warn when closing — that .notConnected is from our
                    // own session.disconnect(), not an unexpected drop.
                    if (!closing) {
                        log.warn { "mc.session.error localPeer=${selfId.value} peer=${peer.displayName}" }
                    }
                    // Identity-scoped removal: only the device that actually holds the
                    // id is evicted, so a colliding newcomer's drop leaves the incumbent.
                    registry.unbind(peerId, peer)
                    val remaining = _peers.updateAndGet { registry.peers + selfId }
                    // Terminal peer-level drop. When the last remote peer is gone
                    // the whole session is dead — tear the seam down (latch Torn,
                    // complete `incoming`) so the Seam contract holds on a remote
                    // disconnect. The latched Torn is the sole signal the owning
                    // factory's ActiveSeamSlot reads to free its single-session slot
                    // on the next weave — no side-channel callback. No
                    // session.disconnect() here: ARC reclaims the dropped MCSession;
                    // only close() disconnects.
                    if (remaining == setOf(selfId)) {
                        tearDown(CloseReason.RemoteRequested)
                    }
                }
                else -> Unit // Connecting — wait for terminal state
            }
        }

        override fun session(
            session: MCSession,
            didReceiveData: NSData,
            fromPeer: MCPeerID,
        ) {
            log.debug { "mc.session.receive localPeer=${selfId.value} fromPeer=${fromPeer.displayName} bytes=${didReceiveData.length}" }
            val frame =
                Swatch(
                    payload = didReceiveData.toByteArray(),
                    sender = MultipeerPeerId.peerId(fromPeer.displayName),
                )
            // Non-suspending deposit into the bridge channel. The drain coroutine
            // forwards to spool.deliver in FIFO order.
            bridge.trySend(frame)
        }

        // MultipeerConnectivity defines five other delegate callbacks
        // (streams, resource transfers, certificate validation). This library
        // doesn't use any, but the protocol requires them. Auto-accept the
        // certificate; everything else is a no-op.
        override fun session(
            session: MCSession,
            didReceiveStream: NSInputStream,
            withName: String,
            fromPeer: MCPeerID,
        ) = Unit

        override fun session(
            session: MCSession,
            didStartReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            withProgress: NSProgress,
        ) = Unit

        override fun session(
            session: MCSession,
            didFinishReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            atURL: NSURL?,
            withError: NSError?,
        ) = Unit

        override fun session(
            session: MCSession,
            didReceiveCertificate: List<*>?,
            fromPeer: MCPeerID,
            certificateHandler: (Boolean) -> Unit,
        ) {
            certificateHandler(true)
        }
    }
}
