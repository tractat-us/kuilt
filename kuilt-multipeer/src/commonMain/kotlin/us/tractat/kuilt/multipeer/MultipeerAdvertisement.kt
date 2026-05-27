package us.tractat.kuilt.multipeer

import us.tractat.kuilt.core.Tag

/**
 * `Tag` for a peer discovered via Apple's MultipeerConnectivity
 * framework.
 *
 * Apple represents peers with `MCPeerID` objects whose lifetime is tied to the
 * advertising peer's process. We can't surface those directly through
 * `Tag` (it would leak ObjC into commonMain), so the underlying
 * `MCPeerID` is held in a per-session map keyed by [handle]; callers pass the
 * advertisement back to `MultipeerPeerLinkFactory.join` which performs the
 * lookup.
 *
 * @property handle Stable opaque identifier for this peer within the current
 *   browse session. Treat it as an opaque token — its only valid use is round-
 *   tripping back to the `MultipeerPeerLinkFactory` that produced it.
 * @property displayName Human-readable name as broadcast by the advertising
 *   peer (matches its `MCPeerID.displayName`).
 * @property serviceType MultipeerConnectivity service type string the peer
 *   advertised under (e.g. [MultipeerService.SERVICE_TYPE]).
 */
public data class MultipeerAdvertisement(
    val handle: String,
    override val displayName: String,
    val serviceType: String,
) : Tag {
    /** The MC peer handle — stable within the current browse session. */
    override val peerKey: String get() = handle
}
