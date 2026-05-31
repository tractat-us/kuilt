package us.tractat.kuilt.multipeer.internal

import com.sun.jna.Pointer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.MultipeerNativeLib

private val log = KotlinLogging.logger {}

/**
 * JVM-side `Seam` proxying through the macOS K/N MC bridge.
 *
 * Callbacks registered on construction populate live flows:
 *  - `mc_session_set_peer_state_callback` → [_peers] gets remote peers
 *    added/removed as MC fires `peer didChangeState`.
 *  - `mc_session_set_data_callback` → [_incoming] receives every frame
 *    (sender + payload) in arrival order.
 *
 * The callback objects are held as fields so JNA's trampoline survives the
 * lifetime of the link. Releasing them (by setting the fields to null
 * inside [close]) is what eventually lets JNA free the trampoline; the
 * underlying `mc_session_close` cancels the K/N pump first.
 */
internal class BridgePeerLink(
    private val nativeLib: MultipeerNativeLib,
    private val sessionHandle: Pointer,
    override val selfId: PeerId,
) : Seam {
    private val _peers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Starts Weaving; transitions to Woven on first peer-connected callback.
    private val _state: MutableStateFlow<SeamState> = MutableStateFlow(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val incomingChannel: Channel<Swatch> = Channel(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    @Volatile
    private var closing: Boolean = false

    // Strong refs so JNA trampolines aren't GC'd before the K/N side
    // finishes pumping. Cleared in close().
    private var dataCallback: MultipeerNativeLib.DataCallback? =
        MultipeerNativeLib.DataCallback { peerId, data, len ->
            val bytes = if (len > 0) data.getByteArray(0, len) else ByteArray(0)
            val frame = Swatch(payload = bytes, sender = PeerId(peerId))
            incomingChannel.trySend(frame)
        }

    private var peerStateCallback: MultipeerNativeLib.PeerStateCallback? =
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
        nativeLib.mc_session_set_data_callback(sessionHandle, dataCallback!!)
        nativeLib.mc_session_set_peer_state_callback(sessionHandle, peerStateCallback!!)
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
        incomingChannel.close()
        nativeLib.mc_session_close(sessionHandle)
        // Now safe to drop the trampolines — the K/N pumps are cancelled.
        dataCallback = null
        peerStateCallback = null
    }
}
