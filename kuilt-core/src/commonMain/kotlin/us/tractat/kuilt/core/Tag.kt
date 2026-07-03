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

    /**
     * The stable room identity this joiner intends to enter — matched against the
     * host's [Pattern.roomKey] before admission.
     *
     * Defaults to `null` (**permissive**): most discovery transports name a single
     * room per connection (a WS URL, an mDNS service record, a 2-peer link *is* the
     * room), so the transport already binds the target and no host-side room check
     * is needed. `null` therefore means "the transport chose the room for me" and
     * the host admits without comparing. A non-null value is only required on a
     * *flat* fabric where one mesh carries several rooms (e.g. a shared
     * [InMemoryLoom]): there the host must reject a joiner whose target names a
     * different room than its own [Pattern.roomKey]. Note this is room identity,
     * **not** [displayName]/[peerKey] (member/peer identity) — do not conflate them.
     */
    public val roomKey: String? get() = null
}
