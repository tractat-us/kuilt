package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.Flow
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
}
