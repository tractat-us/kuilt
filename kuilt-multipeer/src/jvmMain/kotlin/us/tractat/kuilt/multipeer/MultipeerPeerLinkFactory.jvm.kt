package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.ActiveSeamSlot
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.multipeer.internal.BridgePeerLink

/**
 * JVM-side `MultipeerPeerLinkFactory`, backed by [MultipeerNativeLib] and
 * the macOS K/N `libkuilt.dylib`.
 *
 * Single-session per factory instance (matches the Apple-side semantics).
 * Weaving twice while a session is live throws; the slot frees when the
 * session ends — an explicit `Seam.close()`, a terminal peer drop, or a join
 * that never connects — so a reconnect never needs a factory restart.
 *
 * Non-macOS hosts (Linux/Windows): the factory loads no native library and
 * every call throws with a clear error pointing to mDNS as the alternative
 * cross-platform LAN transport. [availability] reports [FabricAvailability.Unavailable]
 * on such hosts, so callers can probe for support before attempting to `weave`.
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

    /**
     * Torn-aware single-active guard. A weave while a live session occupies the
     * slot throws; a self-dropped (Torn) session frees the slot on the next weave
     * with no side-channel — [BridgePeerLink] latches Torn on both the drop and
     * close paths, and the slot reads that terminal state directly.
     */
    private val slot = ActiveSeamSlot("MultipeerPeerLinkFactory already has an active session")

    public override fun availability(): FabricAvailability =
        if (nativeLib != null) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable(
                "MultipeerConnectivity is macOS-only on the JVM target; " +
                    "use mDNS for cross-platform LAN on Linux/Windows.",
            )
        }

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

    private fun openSession(): BridgePeerLink = startSession { runtime -> mc_runtime_open(runtime) }

    private fun joinSession(advertisement: MultipeerAdvertisement): BridgePeerLink =
        startSession { runtime -> mc_runtime_join(runtime, advertisement.handle) }

    private fun startSession(open: MultipeerNativeLib.(runtime: Pointer) -> Pointer?): BridgePeerLink {
        val lib = nativeLib ?: throwUnsupportedPlatform()
        val runtime = runtimeHandle ?: error("mc_runtime_create returned null on a macOS host — likely a stale dylib")
        // The slot's guard + install are one atomic operation; a Torn (self-dropped)
        // occupant frees the slot on this claim. If the native open fails the slot is
        // left untouched. No onTerminated wiring — the seam's latched Torn is the signal.
        return slot.occupy {
            val session = lib.open(runtime) ?: error("mc_runtime session open failed for runtime $runtime")
            BridgePeerLink(
                nativeLib = lib,
                sessionHandle = session,
                selfId = resolveSelfId(lib, runtime),
            )
        }
    }

    /**
     * The wire [PeerId] for this device. The native runtime decorates the
     * advertised `MCPeerID.displayName` with a per-device nonce (collision
     * resistance, #1494), so `selfId` MUST come from the native wire name to
     * match what remote peers observe — not the raw constructor [displayName].
     * A fake native lib writes nothing (`<= 0`); in that case fall back to the
     * raw name so the fake-backed conformance path stays consistent.
     */
    private fun resolveSelfId(
        lib: MultipeerNativeLib,
        runtime: Pointer,
    ): PeerId {
        val buf = ByteArray(SELF_NAME_BUFFER_BYTES)
        val written = lib.mc_runtime_display_name(runtime, buf, buf.size)
        val name = if (written > 0) String(buf, 0, written, Charsets.UTF_8) else displayName
        return PeerId(name)
    }

    private companion object {
        /** MCPeerID.displayName caps at 63 UTF-8 bytes; +1 for the NUL terminator. */
        private const val SELF_NAME_BUFFER_BYTES: Int = 64
    }

    private fun throwUnsupportedPlatform(): Nothing =
        error(
            "MultipeerConnectivity is macOS-only on the JVM target; " +
                "fall back to mDNS for cross-platform LAN play on Linux/Windows.",
        )

    /**
     * Tears down the active session (if any) and the runtime handle. Idempotent.
     *
     * **Consumer contract:** the self-drop path frees the slot (on the next weave)
     * but issues no `mc_session_close` — and unlike apple, the JVM has no ARC to
     * reclaim the dropped native session. A consumer that observes [SeamState.Torn]
     * and drops the seam without calling `Seam.close()` leaks the native handle
     * until this factory `close()` runs. Always `close()` a self-dropped seam.
     */
    public actual fun close() {
        val lib = nativeLib ?: return
        val runtime = runtimeHandle ?: return
        // grabAndRelease nulls the slot before teardown, then delegate the native
        // close to the link's CAS-latched closeNow — exactly one mc_session_close
        // per handle even if the consumer's Seam.close() races this.
        val link = slot.grabAndRelease() as BridgePeerLink?
        link?.let { runCatchingCancellable { it.closeNow(CloseReason.Normal) } }
        runCatchingCancellable { lib.mc_runtime_close(runtime) }
    }
}
