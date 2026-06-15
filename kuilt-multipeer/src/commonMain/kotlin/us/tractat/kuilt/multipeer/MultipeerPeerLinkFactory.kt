package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam

/**
 * `Loom` backed by Apple's MultipeerConnectivity framework.
 *
 * Common API only — implementations live in the platform-specific source sets.
 * `appleMain` provides the real implementation against
 * `platform.MultipeerConnectivity.*`; `jvmMain` wraps a
 * `libkuilt.dylib` over JNA so macOS desktop builds can talk to
 * iPhones.
 *
 * The factory is **stateful** — it owns the underlying `MCPeerID`/`MCSession`
 * instances and the in-flight discovery map that gives
 * [MultipeerAdvertisement.handle] its meaning. Construct one per device, share
 * it via DI, and rely on `Loom.open` / `Loom.join` to
 * spin up a session.
 *
 * @param displayName Local display name surfaced to remote peers as the
 *   `MCPeerID.displayName`. Keep it short and recognisable (device name is the
 *   conventional choice).
 * @param serviceType MultipeerConnectivity service-type string. Default:
 *   [MultipeerService.SERVICE_TYPE].
 */
public expect class MultipeerPeerLinkFactory(
    displayName: String,
    serviceType: String = MultipeerService.SERVICE_TYPE,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam

    /**
     * Snapshot of advertisements visible to this factory's current browse
     * session. Updated reactively by the browse delegate on both `foundPeer`
     * and `lostPeer` events, so the lobby's peer list reflects current
     * reality (a host that stops advertising drops out of the set, even on
     * the same device). Emitted as a [StateFlow] so the UI can `collectAsState`.
     *
     * Empty when no `MultipeerServiceBrowser.discoveries` flow is currently
     * being collected.
     */
    public val visiblePeers: StateFlow<Set<MultipeerAdvertisement>>

    /**
     * Stops advertising / browsing / disconnects any active session.
     * Safe to call multiple times. The factory remains usable: a fresh
     * `open` / `join` call re-creates the session.
     */
    public fun close()
}
