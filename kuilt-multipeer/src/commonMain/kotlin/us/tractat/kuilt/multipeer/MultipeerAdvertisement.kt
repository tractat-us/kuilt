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
 * @property sessionName Human-readable name as broadcast by the advertising
 *   peer (matches its `MCPeerID.displayName`).
 * @property serviceType MultipeerConnectivity service type string the peer
 *   advertised under (matches the `serviceType` passed to [MultipeerPeerLinkFactory]).
 * @property roomKey The stable room identity this joiner targets, matched against the
 *   host's room before admission. Defaults to `null` (**permissive**) — the MC service
 *   type already scopes discovery to a single room, so the transport binds the target
 *   and no host-side room check is needed. See [Tag.roomKey].
 */
public data class MultipeerAdvertisement(
    val handle: String,
    override val sessionName: String,
    val serviceType: String,
    override val roomKey: String? = null,
) : Tag {
    /** The MC peer handle — stable within the current browse session. */
    override val peerKey: String get() = handle
}
