package us.tractat.kuilt.multipeer

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * A [MultipeerNativeLib] fake that delivers data between two in-process sessions,
 * modelling the host↔joiner MC session pair for [MultipeerConformanceTest].
 *
 * ## Session identity
 * - [HOST_SESSION]   — handle returned by [mc_runtime_open] (the advertising side).
 * - [JOINER_SESSION] — handle returned by [mc_runtime_join] (the browsing/joining side).
 *
 * ## Delivery model
 * When `mc_session_broadcast(HOST_SESSION, data)` fires, the joiner's registered
 * [MultipeerNativeLib.DataCallback] receives the payload with [hostPeerId] as sender.
 * Vice-versa for broadcasts from the joiner. Delivery is synchronous and ordered —
 * each call routes directly to the callback without buffering.
 *
 * ## Peer-state handshake
 * Both sides start with `peers = {selfId}`. As soon as both sessions have registered
 * their [MultipeerNativeLib.PeerStateCallback]s (i.e. both [BridgePeerLink] constructors
 * have run), [firePeerConnected] signals both sides about the other, bringing each
 * `peers` set to `{selfId, remotePeerId}` — satisfying the conformance suite's
 * `peersReportsSelfIdAndAtLeastTwoAfterJoin` invariant.
 *
 * @param hostPeerId  The display-name / PeerId string of the host factory.
 * @param joinerPeerId The display-name / PeerId string of the joiner factory.
 */
internal class DeliveringFakeMultipeerNativeLib(
    private val hostPeerId: String,
    private val joinerPeerId: String,
) : MultipeerNativeLib {

    companion object {
        val HOST_SESSION: Pointer = Pointer(0x10L)
        val JOINER_SESSION: Pointer = Pointer(0x11L)
        private val FAKE_RUNTIME: Pointer = Pointer(0x01L)
        private val FAKE_BROWSER: Pointer = Pointer(0x02L)
    }

    // Callbacks registered by each BridgePeerLink constructor.
    private var hostDataCallback: MultipeerNativeLib.DataCallback? = null
    private var joinerDataCallback: MultipeerNativeLib.DataCallback? = null
    private var hostPeerStateCallback: MultipeerNativeLib.PeerStateCallback? = null
    private var joinerPeerStateCallback: MultipeerNativeLib.PeerStateCallback? = null

    override fun fireworks_mc_protocol_version(): Int = MultipeerNativeLib.EXPECTED_PROTOCOL_VERSION

    override fun mc_runtime_create(displayName: String, serviceType: String): Pointer = FAKE_RUNTIME

    override fun mc_runtime_destroy(handle: Pointer?) = Unit

    override fun mc_runtime_close(handle: Pointer?) = Unit

    override fun mc_runtime_display_name(handle: Pointer?, buf: ByteArray, bufLen: Int): Int = 0

    /** Host opens a session → returns HOST_SESSION. */
    override fun mc_runtime_open(handle: Pointer?): Pointer = HOST_SESSION

    /** Joiner joins a discovered peer → returns JOINER_SESSION. */
    override fun mc_runtime_join(runtime: Pointer?, peerHandle: String): Pointer = JOINER_SESSION

    override fun mc_session_close(session: Pointer?) = Unit

    /**
     * Routes broadcast from [session] to the other session's data callback.
     * Delivery is synchronous — the callback fires on the calling coroutine, preserving
     * send-order for the conformance suite's ordering invariant.
     */
    override fun mc_session_broadcast(session: Pointer?, data: ByteArray, len: Int): Int {
        val mem = Memory(len.toLong()).also { it.write(0, data, 0, len) }
        when (session) {
            HOST_SESSION -> joinerDataCallback?.invoke(hostPeerId, mem, len)
            JOINER_SESSION -> hostDataCallback?.invoke(joinerPeerId, mem, len)
        }
        return len
    }

    override fun mc_browser_start(
        runtime: Pointer?,
        peerFoundCb: MultipeerNativeLib.PeerFoundCallback,
    ): Pointer = FAKE_BROWSER

    override fun mc_browser_set_peer_lost_callback(
        browser: Pointer?,
        peerLostCb: MultipeerNativeLib.PeerLostCallback,
    ) = Unit

    override fun mc_browser_stop(browser: Pointer?) = Unit

    override fun mc_session_set_data_callback(session: Pointer?, cb: MultipeerNativeLib.DataCallback) {
        when (session) {
            HOST_SESSION -> hostDataCallback = cb
            JOINER_SESSION -> joinerDataCallback = cb
        }
    }

    /**
     * Stores the peer-state callback for [session] and fires the peer-connected
     * handshake as soon as both callbacks are registered — telling each side about
     * the other and completing the virtual MC session establishment.
     */
    override fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.PeerStateCallback,
    ) {
        when (session) {
            HOST_SESSION -> hostPeerStateCallback = cb
            JOINER_SESSION -> joinerPeerStateCallback = cb
        }
        firePeerConnectedIfReady()
    }

    override fun mc_session_send_to(
        session: Pointer?,
        peerHandle: String,
        data: ByteArray,
        len: Int,
    ): Int = len

    // ── peer-state handshake ──────────────────────────────────────────────────

    private fun firePeerConnectedIfReady() {
        val hostCb = hostPeerStateCallback ?: return
        val joinerCb = joinerPeerStateCallback ?: return
        // Both BridgePeerLink constructors have run — the session is "connected".
        // Fire isConnected=1 for the remote peer on each side.
        hostCb.invoke(joinerPeerId, /* isConnected = */ 1)
        joinerCb.invoke(hostPeerId, /* isConnected = */ 1)
    }
}
