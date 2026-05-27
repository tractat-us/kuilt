package us.tractat.kuilt.core

/**
 * Game-agnostic discovery handle. mDNS service record / MPC peer / WS URL.
 *
 * Open (not sealed) so that transport modules in other Gradle modules can provide
 * their own implementations (e.g. `WebSocketAdvertisement` in `:kuilt-websocket`,
 * `MDNSAdvertisement` in `:kuilt-mdns`). Sealed would confine all
 * tag types to `:kuilt-core`, defeating the extensibility goal.
 *
 * [peerKey] is the stable, unique identifier for this peer within its discovery
 * transport. It is the key a consumer uses to track arrivals and
 * departures — the same key returned by [PeerDiscoverySource.departures] when
 * the peer leaves.
 */
public interface Tag {
    /** Human-readable service name as broadcast by the advertising peer. */
    public val displayName: String

    /**
     * Stable, unique identifier for this peer within its discovery transport.
     *
     * For mDNS peers this is the server's [PeerId] value. For Multipeer peers
     * this is the `MCPeerID` handle. For test fixtures it can be any unique
     * string (e.g. the display name if unique across the test).
     */
    public val peerKey: String
}
