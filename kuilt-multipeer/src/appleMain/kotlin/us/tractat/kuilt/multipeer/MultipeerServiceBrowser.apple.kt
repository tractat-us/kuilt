package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * iOS browser that discovers peers advertising under [factory]'s service
 * type and emits one [MultipeerAdvertisement] per `foundPeer` callback.
 *
 * The browser does **not** own its own `MCNearbyServiceBrowser` — it
 * activates the one held by [factory] through
 * `MultipeerPeerLinkFactory.startBrowsing`. That same browser instance is
 * later reused by `MultipeerPeerLinkFactory.join` to call `invitePeer`;
 * Apple's MC framework requires the invitation to be sent on the browser
 * that discovered the peer (the peer lives in that browser's internal
 * "peers dictionary"). A throwaway browser created elsewhere would silently
 * drop the invite.
 *
 * The browser uses each peer's `displayName` as the
 * [MultipeerAdvertisement.handle]. That collides if two devices on the same
 * Wi-Fi share a display name — unusual but not impossible. The lobby treats
 * that as a UX problem to be disambiguated later (e.g. by appending a short
 * suffix); the transport itself does no de-duplication.
 *
 * Departures are surfaced via [departures] which exposes
 * `MultipeerPeerLinkFactory.lostPeerHandles` — a `SharedFlow` fed by the
 * `BrowserDelegate.browser(lostPeer:)` callback.
 *
 * Single-collector: [factory] permits one active browse session at a time,
 * so the returned flow must not be collected concurrently from multiple
 * places.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    /**
     * Emits peer keys for peers that have left the network. Backed by
     * `MultipeerPeerLinkFactory.lostPeerHandles` which is fed by the
     * `MCNearbyServiceBrowserDelegate.browser(_:lostPeer:)` callback.
     *
     * The key is `MCPeerID.displayName`, which is also
     * [MultipeerAdvertisement.peerKey] — so a departure names the peer
     * [discoveries] published, as `PeerDiscoverySource.departures` requires.
     *
     * **Only emits while [discoveries] is being collected**, because the
     * factory's browser delegate — the sole producer of a `lostPeer` — is
     * installed by `startBrowsing`, and `discoveries()` is the only caller.
     * Any departure that fires outside that window is dropped.
     *
     * That coupling is a **known contract violation, tracked by kuilt #2410**, not a caveat:
     * `DiscoverySourceConformanceSuite.departuresEmitsWithNoConcurrentDiscoveriesCollector`
     * fails against this class, and
     * `MultipeerAppleDiscoverySourceConformanceTest` pins the failure so a fix
     * flips it loudly. It matters because `discoveryRoster` merges the two
     * feeds, and `merge` subscribes to inner flows in separately-launched
     * coroutines: a consumer collecting *both* can still attach here before
     * the arrival feed has opened a session, and lose every departure until it
     * does. Fixing it means giving the browse session a lifetime of its own —
     * ref-counted across both feeds — rather than tying it to one collector;
     * `startBrowsing`'s `check(browser == null)` is what currently forbids
     * `departures()` from simply opening its own.
     */
    public actual override fun departures(): Flow<String> = factory.lostPeerHandles

    public actual override fun discoveries(): Flow<Tag> =
        callbackFlow {
            factory.startBrowsing { ad -> trySend(ad) }
            awaitClose { factory.stopBrowsing() }
        }
}
