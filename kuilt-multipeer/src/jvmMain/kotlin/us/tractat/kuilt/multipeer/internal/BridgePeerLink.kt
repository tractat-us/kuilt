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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
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

    @Volatile
    private var closing: Boolean = false

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
            if (peer == selfId) return@PeerStateCallback
            if (isConnected == 1) {
                _peers.update { it + peer }
                if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
            } else {
                // MC has no dedicated error callback; .notConnected is the
                // closest session-level error surface (unexpected drops fire here).
                // Suppress the warn when closing — that .notConnected is from our
                // own mc_session_close, not an unexpected drop.
                if (!closing) {
                    log.warn { "mc.session.error selfId=${selfId.value} peer=$peerId" }
                }
                _peers.update { it - peer }
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
        if (closing) return
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
        if (closing) return
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        val sent = nativeLib.mc_session_send_to(sessionHandle, peer.value, payload, payload.size)
        if (sent < 0) {
            error("mc_session_send_to failed for ${peer.value}; peer may be disconnected")
        }
    }

    override suspend fun close(reason: CloseReason) {
        if (closing) return
        // Set closing before mc_session_close so the peer-state callback sees it
        // when MC fires .notConnected for the clean disconnect — suppressing the
        // spurious mc.session.error warn.
        closing = true
        _state.value = SeamState.Torn(reason)
        bridge.close()
        spool.close()
        scope.cancel()
        nativeLib.mc_session_close(sessionHandle)
    }
}
