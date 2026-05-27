package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer

/**
 * Fake [MultipeerNativeLib] for threading tests.
 *
 * Captures the [MultipeerNativeLib.PeerFoundCallback] registered by
 * [mc_browser_start] so tests can fire it from an arbitrary thread to
 * simulate Darwin GCD callbacks. All other methods are no-ops.
 */
internal class FakeMultipeerNativeLib : MultipeerNativeLib {
    private var capturedCallback: MultipeerNativeLib.PeerFoundCallback? = null
    private val fakeBrowserHandle = Pointer(0xDEADBEEFL)

    /** Fires [MultipeerNativeLib.PeerFoundCallback.invoke] with the given arguments. */
    fun fireFoundPeer(
        handle: String,
        displayName: String,
    ) {
        capturedCallback?.invoke(handle, displayName)
    }

    override fun fireworks_mc_protocol_version(): Int = MultipeerNativeLib.EXPECTED_PROTOCOL_VERSION

    override fun mc_runtime_create(
        displayName: String,
        serviceType: String,
    ): Pointer = Pointer(0x1L)

    override fun mc_runtime_destroy(handle: Pointer?) = Unit

    override fun mc_runtime_close(handle: Pointer?) = Unit

    override fun mc_runtime_display_name(
        handle: Pointer?,
        buf: ByteArray,
        bufLen: Int,
    ): Int = 0

    override fun mc_runtime_open(handle: Pointer?): Pointer = Pointer(0x2L)

    override fun mc_session_close(session: Pointer?) = Unit

    override fun mc_session_broadcast(
        session: Pointer?,
        data: ByteArray,
        len: Int,
    ): Int = len

    override fun mc_browser_start(
        runtime: Pointer?,
        peerFoundCb: MultipeerNativeLib.PeerFoundCallback,
    ): Pointer {
        capturedCallback = peerFoundCb
        return fakeBrowserHandle
    }

    override fun mc_browser_set_peer_lost_callback(
        browser: Pointer?,
        peerLostCb: MultipeerNativeLib.PeerLostCallback,
    ) = Unit

    override fun mc_browser_stop(browser: Pointer?) {
        capturedCallback = null
    }

    override fun mc_runtime_join(
        runtime: Pointer?,
        peerHandle: String,
    ): Pointer = Pointer(0x3L)

    override fun mc_session_set_data_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.DataCallback,
    ) = Unit

    override fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.PeerStateCallback,
    ) = Unit

    override fun mc_session_send_to(
        session: Pointer?,
        peerHandle: String,
        data: ByteArray,
        len: Int,
    ): Int = len
}
