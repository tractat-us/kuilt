package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 */
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    public actual override fun discoveries(): Flow<Tag> =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    // No leave signal: this platform has no MultipeerConnectivity at all, so nothing can ever be
    // discovered here and nothing can ever depart. Empty rather than throwing, so a consumer that
    // folds a list of sources through discoveryRoster fails on the one call that is genuinely
    // unsupported ([discoveries]) instead of on two.
    override fun departures(): Flow<String> = emptyFlow()
}
