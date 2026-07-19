package us.tractat.kuilt.nw

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * JNA façade over `libkuilt.dylib` — the macOS-only Kotlin/Native shared library
 * that exposes Apple's Network.framework (`RealNwApi`) to the JVM.
 *
 * The dylib is built from the `macosArm64` target of this same module (see
 * `binaries.sharedLib` in `build.gradle.kts`) and bundled inside the JVM jar at
 * `darwin-aarch64/libkuilt.dylib`. JNA picks the right architecture automatically
 * when [Native.load] runs from inside a Mac JVM.
 *
 * **Loading is platform-gated**: [load] returns `null` on non-macOS hosts
 * (Linux/Windows) so the JVM target compiles and runs portably; the fabric
 * reports [FabricAvailability.Unavailable] there ([jvmAvailability]) and
 * [nwHost]/[nwJoin] fail fast with an actionable "macOS-only" error.
 *
 * ## JNA C-string / callback lifetime contract (read before adding methods)
 * - A `const char*` delivered to a JNA callback survives ONLY for the duration of
 *   the call. Copy strings out immediately (JNA maps them to Java `String`s, so
 *   they are already copied); never retain the byte pointer.
 * - Byte-buffer callbacks pass a raw [Pointer]; copy via
 *   `pointer.getByteArray(0, len)` inside the callback, before it returns.
 * - The JVM caller MUST hold a strong reference to every registered [Callback]
 *   object for the whole runtime lifetime, otherwise JNA may release the
 *   trampoline and the K/N side will SIGSEGV when it next fires the callback.
 *   [BridgeNwApi] holds all six as fields for exactly this reason.
 */
internal interface NwNativeLib : Library {
    @Suppress("ktlint:standard:function-naming")
    fun kuilt_protocol_version(): Int

    /**
     * Builds a runtime wrapping `RealNwApi(NwPskMaterial(psk, identity))` and
     * returns its opaque handle, or `null` on invalid arguments. Pair every
     * successful create with exactly one [nw_runtime_destroy].
     */
    @Suppress("ktlint:standard:function-naming")
    fun nw_runtime_create(psk: ByteArray, pskLen: Int, identity: ByteArray, identityLen: Int): Pointer?

    /**
     * Gracefully tears the runtime down and releases the handle. Idempotent only
     * across `null`; passing the same non-null pointer twice is a use-after-free.
     */
    @Suppress("ktlint:standard:function-naming")
    fun nw_runtime_destroy(handle: Pointer?)

    /**
     * Creates an in-process loopback rendezvous, returning its opaque handle. Shared by exactly one
     * host/joiner runtime pair built with [nw_runtime_create_loopback]: the host publishes its bound
     * port into it, the joiner awaits that port before dialling `127.0.0.1:port`. Pair every
     * successful create with exactly one [nw_loopback_rendezvous_destroy].
     */
    @Suppress("ktlint:standard:function-naming")
    fun nw_loopback_rendezvous_create(): Pointer?

    /**
     * Disposes a loopback rendezvous handle. Destroy the host/joiner runtimes (via
     * [nw_runtime_destroy]) BEFORE the rendezvous. Idempotent only across `null`; passing the same
     * non-null pointer twice is a use-after-free.
     */
    @Suppress("ktlint:standard:function-naming")
    fun nw_loopback_rendezvous_destroy(handle: Pointer?)

    /**
     * Builds a direct-loopback runtime wrapping `RealNwApi(NwPskMaterial(psk, identity), loopback)`
     * over the shared [rendezvous], returning its opaque handle or `null` on invalid arguments
     * (including a null [rendezvous]). [dial] selects the role: `0` = HOST (publishes its bound
     * port, never dials), non-zero = JOINER (awaits the port, then dials). This is the CI path a
     * JVM↔JVM `SeamConformanceSuite` uses to prove the TLS-PSK link through the real dylib. Pair
     * every successful create with exactly one [nw_runtime_destroy].
     */
    @Suppress("ktlint:standard:function-naming")
    fun nw_runtime_create_loopback(
        psk: ByteArray,
        pskLen: Int,
        identity: ByteArray,
        identityLen: Int,
        rendezvous: Pointer?,
        dial: Int,
    ): Pointer?

    /** `(endpointId: char*, serviceName: char*) -> void`. Strong-ref + copy-out contract as above. */
    fun interface EndpointFoundCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(endpointId: String, serviceName: String)
    }

    /**
     * `(connectionId: char*, endpointId: char*, serviceName: char*) -> void`.
     * `endpointId`/`serviceName` are empty for an inbound (host-role) connection.
     */
    fun interface ConnectionOpenedCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(connectionId: String, endpointId: String, serviceName: String)
    }

    /**
     * `(connectionId: char*, data: char*, len: int) -> void`. The [data] pointer
     * is valid only for the duration of the call; copy out via
     * `data.getByteArray(0, len)` immediately.
     */
    fun interface BytesReceivedCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(connectionId: String, data: Pointer, len: Int)
    }

    /** `(connectionId: char*, reason: char*) -> void`. Empty [reason] ⇒ graceful/`null`. */
    fun interface ConnectionClosedCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(connectionId: String, reason: String)
    }

    /**
     * `(connectionId: char*, reason: char*) -> void` (#1539). The drop-tolerant native `closedConnections`
     * STATE signal: fires once per newly-latched close marker in `RealNwApi.closedConnections` (a monotone
     * map, id → reason). Empty [reason] ⇒ graceful/`null`, matching [ConnectionClosedCallback].
     *
     * Unlike [ConnectionClosedCallback] — the lossy per-event close stream that can DROP a `failed`/`cancelled`
     * close at the K/N `tryEmit` or JVM staging boundary and strand a zombie peer — this is sourced from the
     * transport's authoritative monotone STATE, so a close it delivers can never be dropped. The bridge latches
     * each marker into its own drop-tolerant `closedConnections` state (and prunes the closed connection's
     * viability entry) off THIS callback, not the droppable event. Stage 1 of #1522's deferred follow-up.
     */
    fun interface ConnectionClosedStateCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(connectionId: String, reason: String)
    }

    /**
     * `(connectionId: char*, viable: int) -> void` (#1507). [viable] is `1` when the connection's path is
     * up (`ready`) and `0` when it is lost (`ready → waiting`). Fires once per per-connection change; the
     * bridge applies each as a latest-wins delta into its drop-tolerant `connectionViability` state (#1509).
     * Entry removals are not delivered here — the bridge prunes a closed connection off the drop-tolerant
     * [ConnectionClosedStateCallback] (#1539).
     */
    fun interface ViabilityCallback : Callback {
        @Suppress("ktlint:standard:function-naming")
        fun invoke(connectionId: String, viable: Int)
    }

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_endpoint_found_callback(handle: Pointer?, cb: EndpointFoundCallback)

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_connection_opened_callback(handle: Pointer?, cb: ConnectionOpenedCallback)

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_bytes_received_callback(handle: Pointer?, cb: BytesReceivedCallback)

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_connection_closed_callback(handle: Pointer?, cb: ConnectionClosedCallback)

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_connection_closed_state_callback(handle: Pointer?, cb: ConnectionClosedStateCallback)

    @Suppress("ktlint:standard:function-naming")
    fun nw_set_connection_viability_callback(handle: Pointer?, cb: ViabilityCallback)

    /** All ops return `0` on success, `<0` on error. */
    @Suppress("ktlint:standard:function-naming")
    fun nw_start_listening(handle: Pointer?, serviceName: String, serviceType: String): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_stop_listening(handle: Pointer?): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_start_browsing(handle: Pointer?, serviceType: String): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_stop_browsing(handle: Pointer?): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_connect(handle: Pointer?, endpointId: String): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_disconnect(handle: Pointer?, connectionId: String): Int

    @Suppress("ktlint:standard:function-naming")
    fun nw_send(handle: Pointer?, connectionId: String, data: ByteArray, len: Int): Int

    companion object {
        const val LIBRARY_NAME: String = "kuilt"

        /**
         * Bridge ABI version this Kotlin code expects. Must match the
         * `PROTOCOL_VERSION` compiled into `Bridge.kt` on the macOS K/N side. A
         * mismatch means a stale or wrong-arch dylib is on the classpath.
         *
         * Bumped to `2` for the `nw_set_connection_closed_state_callback` export (#1539): the bridge now
         * registers that callback at construction, so a stale dylib lacking the symbol must fail the fast
         * ABI check ([NwFabric] `createRuntime`) rather than the later JNA `UnsatisfiedLinkError`.
         */
        const val EXPECTED_PROTOCOL_VERSION: Int = 2

        /** The reason attached to [FabricAvailability.Unavailable] off macOS-arm64. */
        const val UNAVAILABLE_REASON: String =
            "kuilt-nw's JVM bridge is macOS-only (Apple Network.framework over a native dylib); " +
                "it loads only on a macOS-arm64 host. Use mDNS/WebSocket for cross-platform LAN " +
                "on Linux/Windows."

        /**
         * Loads the dylib if available, else returns `null`. Idempotent — JNA
         * caches `Native.load` per (name, interface) pair. Returns `null` on
         * Linux/Windows (never even attempts the load).
         */
        fun load(): NwNativeLib? {
            if (!isMacOs) return null
            return runCatchingCancellable { Native.load(LIBRARY_NAME, NwNativeLib::class.java) }.getOrNull()
        }

        /**
         * Pure availability mapping: [FabricAvailability.Available] iff the dylib
         * is [loaded], else [FabricAvailability.Unavailable]. Split out from
         * [jvmAvailability] so both branches are testable without a real dylib.
         */
        fun availabilityFor(loaded: Boolean): FabricAvailability =
            if (loaded) FabricAvailability.Available else FabricAvailability.Unavailable(UNAVAILABLE_REASON)

        /** Fabric availability on this JVM: [FabricAvailability.Available] only on macOS-arm64 with the dylib present. */
        fun jvmAvailability(): FabricAvailability = availabilityFor(load() != null)

        private val isMacOs: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    }
}
