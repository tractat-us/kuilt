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
     * Only emits while [discoveries] is being collected (the factory's browser
     * delegate is only active during that window). Any departure that fires
     * before [discoveries] is collected is dropped.
     */
    override fun departures(): Flow<String> = factory.lostPeerHandles

    public actual override fun discoveries(): Flow<Tag> =
        callbackFlow {
            factory.startBrowsing { ad -> trySend(ad) }
            awaitClose { factory.stopBrowsing() }
        }
}
