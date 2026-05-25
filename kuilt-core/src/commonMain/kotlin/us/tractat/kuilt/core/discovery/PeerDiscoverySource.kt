package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.PeerAdvertisement

/**
 * A transport-agnostic feed of peer advertisements.
 *
 * Each transport module provides its own implementation: `MDNSServiceDiscoverer`
 * for Bonjour, `MultipeerServiceBrowser` for Apple MultipeerConnectivity, and so
 * on. The lobby controller composes a list of these and merges their flows so
 * the UI sees one unified roster.
 *
 * Implementations expose a *narrower* return type via Kotlin's covariant-return
 * support (e.g. `Flow<MDNSAdvertisement>`); direct callers keep their typed
 * APIs while the lobby treats every source as `Flow<PeerAdvertisement>`.
 */
public interface PeerDiscoverySource {
    /** Identifies the underlying transport (mDNS, MultipeerConnectivity, …). */
    public val kind: DiscoveryKind

    /**
     * Cold flow that emits a [PeerAdvertisement] for every peer the source
     * discovers. Stays open until the collector's scope is cancelled.
     */
    public fun discoveries(): Flow<PeerAdvertisement>

    /**
     * Cold flow that emits a [PeerAdvertisement.peerKey] for every peer that
     * leaves the network. Implementations that do not support departure events
     * (e.g. test fakes that emit a fixed roster) may return [emptyFlow].
     *
     * Stays open until the collector's scope is cancelled.
     */
    public fun departures(): Flow<String> = emptyFlow()
}
