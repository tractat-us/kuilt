package us.tractat.kuilt.multipeer

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA façade over `libkuilt.dylib` — the macOS-only Kotlin/Native
 * shared library that exposes Apple's MultipeerConnectivity to the JVM.
 *
 * The dylib is built from the macOS K/N targets of this same module
 * (see `binaries.sharedLib` in `build.gradle.kts`) and bundled inside the
 * JVM jar at `darwin-aarch64/libkuilt.dylib`. JNA picks the right
 * architecture automatically when [Native.load] runs from inside a Mac JVM.
 *
 * **Loading is platform-gated**: [load] returns `null` on non-macOS hosts
 * (Linux/Windows) so the JVM target compiles and runs portably; calls to
 * MC there will surface an actionable "macOS-only" error to the lobby.
 */
internal interface MultipeerNativeLib : Library {
    @Suppress("ktlint:standard:function-naming")
    fun kuilt_protocol_version(): Int

    /**
     * Creates a `MultipeerPeerLinkFactory` and returns its opaque handle.
     * Returns `null` if the underlying ObjC objects could not be created
     * (e.g. invalid arguments).
     *
     * The caller owns the handle: pair every successful create with exactly
     * one [mc_runtime_destroy].
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_create(
        displayName: String,
        serviceType: String,
    ): Pointer?

    /**
     * Releases a runtime handle. Idempotent only across `null`; passing the
     * same non-null pointer twice is a use-after-free.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_destroy(handle: Pointer?)

    /**
     * Stops advertising and disconnects any active session, but keeps the
     * runtime handle alive. Pair with [mc_runtime_destroy] for full cleanup,
     * or call this alone when the JVM-side factory is being reused but the
     * current host/session needs to be torn down.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_close(handle: Pointer?)

    /**
     * Copies the runtime's display name into [buf], NUL-terminated. Returns
     * the number of bytes written (excluding the NUL), or `-1` if the
     * buffer is too small / inputs are null.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_display_name(
        handle: Pointer?,
        buf: ByteArray,
        bufLen: Int,
    ): Int

    /**
     * Opens the runtime as a host: creates an `MCSession`, starts advertising,
     * auto-accepts invitations. Returns an opaque session handle, or `null`
     * if the runtime is already hosting or open() failed.
     *
     * Pair with exactly one [mc_session_close].
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_open(handle: Pointer?): Pointer?

    /**
     * Closes the session, stops advertising, and disposes the session
     * handle. Use-after-free if called twice with the same pointer.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_session_close(session: Pointer?)

    /**
     * Broadcasts [data] (the first [len] bytes) to every connected peer.
     * Returns the number of bytes sent or `-1` on invalid arguments.
     * Empty peer set is a non-error.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_session_broadcast(
        session: Pointer?,
        data: ByteArray,
        len: Int,
    ): Int

    /**
     * `(handle: const char*, displayName: const char*) -> void`. JNA
     * delivers the cdecl strings as Java `String`s by default; the underlying
     * `const char*` survives only for the duration of the call. Don't retain
     * pointers across invocations — copy the strings out immediately.
     *
     * The JVM caller MUST hold a strong reference to the callback object
     * for the entire lifetime of the browse session, otherwise JNA may
     * release the trampoline and the K/N side will SIGSEGV when it next
     * fires the callback.
     */
    fun interface PeerFoundCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(
            handle: String,
            displayName: String,
        )
    }

    /**
     * Starts an MC browse session for the given runtime, forwarding every
     * `foundPeer` event to [peerFoundCb]. Returns an opaque browser handle
     * to pair with [mc_browser_stop]. Returns `null` on invalid arguments
     * or load failure.
     *
     * For `peerLost` forwarding call [mc_browser_set_peer_lost_callback]
     * once the browser handle is in hand.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_browser_start(
        runtime: Pointer?,
        peerFoundCb: PeerFoundCallback,
    ): Pointer?

    /**
     * `(handle: const char*) -> void`. Same JNA lifetime contract as
     * [PeerFoundCallback]: hold a strong JVM reference for the lifetime of
     * the browse session; copy the string out immediately because the
     * underlying `const char*` survives only for the duration of the call.
     */
    fun interface PeerLostCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(handle: String)
    }

    /**
     * Registers [peerLostCb] against a running [browser] session. Called
     * once, right after [mc_browser_start], so the JVM side learns when
     * peers go away (e.g. an iPhone stops hosting). Without this hook the
     * JVM-side `visiblePeers` snapshot would accumulate ghosts until the
     * browse session ends.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_browser_set_peer_lost_callback(
        browser: Pointer?,
        peerLostCb: PeerLostCallback,
    )

    /**
     * Stops the browse session and disposes the handle. Use-after-free if
     * called twice with the same pointer.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_browser_stop(browser: Pointer?)

    /**
     * `(peerId: const char*, data: const char*, len: int) -> void`. Holds
     * the same lifetime contract as [PeerFoundCallback] — keep a strong
     * JVM reference until the session closes. The data pointer is valid
     * only for the duration of the call; copy out via
     * `Pointer.getByteArray(0, len)` immediately.
     */
    fun interface DataCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(
            peerId: String,
            data: Pointer,
            len: Int,
        )
    }

    /**
     * `(peerId: const char*, isConnected: int) -> void`. Same lifetime
     * contract. `isConnected` is `1` for connect, `0` for disconnect.
     */
    fun interface PeerStateCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(
            peerId: String,
            isConnected: Int,
        )
    }

    /**
     * Joins a discovered peer's session as the client. [peerHandle] must
     * have been surfaced earlier via the [PeerFoundCallback] of an active
     * `mc_browser_start` so the K/N side's known-peer map can resolve it
     * to a live `MCPeerID`. Returns an opaque session handle or `null`.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_runtime_join(
        runtime: Pointer?,
        peerHandle: String,
    ): Pointer?

    /**
     * Attaches a data-receive callback to the session. Cancels and replaces
     * any previously-attached pump.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_session_set_data_callback(
        session: Pointer?,
        cb: DataCallback,
    )

    /**
     * Attaches a peer-state callback. Reports remote peers only — the local
     * peer is excluded.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: PeerStateCallback,
    )

    /**
     * Sends [data] (first [len] bytes) to a single connected peer.
     * Returns the byte count or `-1` on invalid input / disconnected peer.
     */
    @Suppress("ktlint:standard:function-naming")
    fun mc_session_send_to(
        session: Pointer?,
        peerHandle: String,
        data: ByteArray,
        len: Int,
    ): Int

    companion object {
        const val LIBRARY_NAME: String = "kuilt"

        /**
         * Bridge ABI version that this Kotlin code expects. Must match the
         * `PROTOCOL_VERSION` constant compiled into `Bridge.kt` on the
         * macOS K/N side. A mismatch indicates a stale or wrong-arch dylib
         * is on the classpath — almost certainly a Gradle config bug.
         */
        const val EXPECTED_PROTOCOL_VERSION: Int = 1

        /**
         * Loads the dylib if available, else returns `null`. Idempotent —
         * JNA caches `Native.load` calls per (name, interface) pair, so
         * repeated calls return the same proxy.
         *
         * Not available on Linux/Windows: returns `null`.
         */
        fun load(): MultipeerNativeLib? {
            if (!isMacOs) return null
            return runCatching { Native.load(LIBRARY_NAME, MultipeerNativeLib::class.java) }
                .getOrNull()
        }

        private val isMacOs: Boolean
            get() =
                System
                    .getProperty("os.name")
                    .orEmpty()
                    .lowercase()
                    .contains("mac")
    }
}
