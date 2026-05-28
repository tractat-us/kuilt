package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * Discovers peers that are advertising over Apple's MultipeerConnectivity
 * framework on the same Wi-Fi network or Bluetooth PAN.
 *
 * The browser is paired with a [MultipeerPeerLinkFactory]: the factory owns
 * the `MCNearbyServiceBrowser` and the live `MCPeerID` map, and the browser
 * exposes a transport-agnostic `Flow<Tag>` for the consumer.
 *
 * Implementations live in `appleMain`; `jvmMain` wraps the dylib + JNA bridge.
 */
public expect class MultipeerServiceBrowser(
    factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    override val kind: DiscoveryKind

    override fun discoveries(): Flow<Tag>
}
