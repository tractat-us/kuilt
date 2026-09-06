package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer

/**
 * Fake [MultipeerNativeLib] for threading and identity tests.
 *
 * Captures the [MultipeerNativeLib.PeerFoundCallback] registered by
 * [mc_browser_start] so tests can fire it from an arbitrary thread to
 * simulate Darwin GCD callbacks.
 *
 * It also models the two halves of the identity ABI, because a fake that cannot
 * represent them cannot fail a property about them: [createdSelfId] records the
 * `selfId` the factory passed to [mc_runtime_create], and [wireDisplayName], when
 * set, is what [mc_runtime_display_name] reports back. Leaving `wireDisplayName`
 * null keeps the historical "writes nothing" behaviour, which is the fallback path.
 * Everything else is a no-op.
 */
internal class FakeMultipeerNativeLib : MultipeerNativeLib {
    private var capturedCallback: MultipeerNativeLib.PeerFoundCallback? = null
    private val fakeBrowserHandle = Pointer(0xDEADBEEFL)

    /** The `selfId` argument of the last [mc_runtime_create] call, or null if never called. */
    var createdSelfId: String? = null
        private set

    /** Wire name [mc_runtime_display_name] reports; null means "writes nothing" (`0`). */
    var wireDisplayName: String? = null

    /** Fires [MultipeerNativeLib.PeerFoundCallback.invoke] with the given arguments. */
    fun fireFoundPeer(
        handle: String,
        displayName: String,
    ) {
        capturedCallback?.invoke(handle, displayName)
    }

    override fun kuilt_protocol_version(): Int = MultipeerNativeLib.EXPECTED_PROTOCOL_VERSION

    override fun mc_runtime_create(
        displayName: String,
        serviceType: String,
        selfId: String,
    ): Pointer {
        createdSelfId = selfId
        return Pointer(0x1L)
    }

    override fun mc_runtime_destroy(handle: Pointer?) = Unit

    override fun mc_runtime_close(handle: Pointer?) = Unit

    override fun mc_runtime_display_name(
        handle: Pointer?,
        buf: ByteArray,
        bufLen: Int,
    ): Int {
        val bytes = wireDisplayName?.toByteArray(Charsets.UTF_8) ?: return 0
        if (bytes.size + 1 > bufLen) return -1
        bytes.copyInto(buf)
        return bytes.size
    }

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
