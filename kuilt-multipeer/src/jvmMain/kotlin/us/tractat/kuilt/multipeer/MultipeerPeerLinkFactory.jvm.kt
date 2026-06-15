package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.multipeer.internal.BridgePeerLink

/**
 * JVM-side `MultipeerPeerLinkFactory`, backed by [MultipeerNativeLib] and
 * the macOS K/N `libkuilt.dylib`.
 *
 * Single-session per factory instance (matches the Apple-side semantics).
 * Calling [open] twice without an intervening `close` throws.
 *
 * Non-macOS hosts (Linux/Windows): the factory loads no native library and
 * every call throws with a clear error pointing to mDNS as the alternative
 * cross-platform LAN transport.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    private val displayName: String,
    internal val serviceType: String,
) : Loom {
    /**
     * Internal constructor for unit tests: accepts a pre-built [MultipeerNativeLib]
     * (or `null` to simulate a non-macOS host) and a pre-built runtime [Pointer] so
     * tests can exercise the browser/factory interaction without loading the real dylib.
     */
    internal constructor(
        displayName: String,
        serviceType: String,
        injectedLib: MultipeerNativeLib?,
        injectedRuntimeHandle: Pointer?,
    ) : this(displayName, serviceType) {
        nativeLibField = injectedLib
        runtimeHandleField = injectedRuntimeHandle
    }

    // Backing fields — set from the test constructor before any lazy access.
    // The primary constructor leaves them null, signalling "use the real path".
    private var nativeLibField: MultipeerNativeLib? = null
    private var runtimeHandleField: Pointer? = null

    private val nativeLib: MultipeerNativeLib? by lazy {
        nativeLibField ?: MultipeerNativeLib.load()
    }

    private val runtimeHandle: Pointer? by lazy {
        runtimeHandleField ?: nativeLib?.mc_runtime_create(displayName, serviceType)
    }

    /**
     * Returns the live runtime handle, creating it on first call. Fails if
     * the dylib is unavailable (non-macOS host) or `mc_runtime_create`
     * returned null. Used by the JVM-side `MultipeerServiceBrowser` to
     * attach a browse session to this factory.
     */
    internal fun requireRuntimeHandle(): Pointer = runtimeHandle ?: throwUnsupportedPlatform()

    private val mutableVisiblePeers: MutableStateFlow<Set<MultipeerAdvertisement>> = MutableStateFlow(emptySet())

    /**
     * JVM-side snapshot of visible peers. Updated reactively on both
     * `foundPeer` and `lostPeer` events forwarded from the K/N dylib bridge.
     */
    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> = mutableVisiblePeers.asStateFlow()

    /**
     * Pushes a found-peer advertisement into [visiblePeers], or removes
     * [handle] entirely when [advertisement] is `null`. Called from
     * `MultipeerServiceBrowser` JVM actual's JNA callbacks — `PeerFoundCallback`
     * passes a non-null advertisement, `PeerLostCallback` passes `null`.
     */
    internal fun setVisiblePeer(
        handle: String,
        advertisement: MultipeerAdvertisement?,
    ) {
        mutableVisiblePeers.update { current ->
            val filtered = current.filterNot { it.handle == handle }.toSet()
            if (advertisement != null) filtered + advertisement else filtered
        }
    }

    /**
     * Empties the visible-peers snapshot. Called when the JVM-side browse
     * session ends — `mc_browser_stop` won't fire `peerLost` for peers that
     * are still in range, so we drop the cache so the next browse session
     * starts clean.
     */
    internal fun clearVisiblePeers() {
        mutableVisiblePeers.value = emptySet()
    }

    @Volatile
    private var activeSession: Pointer? = null

    public actual override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> openSession()
            is Rendezvous.Existing -> {
                val advertisement = rendezvous.tag
                require(advertisement is MultipeerAdvertisement) {
                    "MultipeerPeerLinkFactory.weave requires MultipeerAdvertisement; got ${advertisement::class}"
                }
                joinSession(advertisement)
            }
        }

    private fun openSession(): BridgePeerLink {
        val lib = nativeLib ?: throwUnsupportedPlatform()
        val runtime = runtimeHandle ?: error("mc_runtime_create returned null on a macOS host — likely a stale dylib")
        check(activeSession == null) { "MultipeerPeerLinkFactory already has an active session" }
        val session = lib.mc_runtime_open(runtime) ?: error("mc_runtime_open failed for runtime $runtime")
        activeSession = session
        return BridgePeerLink(
            nativeLib = lib,
            sessionHandle = session,
            selfId = PeerId(displayName),
        )
    }

    private fun joinSession(advertisement: MultipeerAdvertisement): BridgePeerLink {
        val lib = nativeLib ?: throwUnsupportedPlatform()
        val runtime = runtimeHandle ?: error("mc_runtime_create returned null on a macOS host")
        check(activeSession == null) { "MultipeerPeerLinkFactory already has an active session" }
        val session =
            lib.mc_runtime_join(runtime, advertisement.handle)
                ?: error("mc_runtime_join failed for ${advertisement.handle}")
        activeSession = session
        return BridgePeerLink(
            nativeLib = lib,
            sessionHandle = session,
            selfId = PeerId(displayName),
        )
    }

    private fun throwUnsupportedPlatform(): Nothing =
        error(
            "MultipeerConnectivity is macOS-only on the JVM target; " +
                "fall back to mDNS for cross-platform LAN play on Linux/Windows.",
        )

    public actual fun close() {
        val lib = nativeLib ?: return
        val runtime = runtimeHandle ?: return
        activeSession?.let { runCatching { lib.mc_session_close(it) } }
        activeSession = null
        runCatching { lib.mc_runtime_close(runtime) }
    }
}
