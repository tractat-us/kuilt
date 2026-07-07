package us.tractat.kuilt.core

import kotlinx.coroutines.flow.StateFlow

/**
 * An observable `PeerId → Principal` roster: the landing spot for host-verified identities
 * on paths that have no admit handshake or member objects (the hosted-hub topology).
 *
 * Implemented by [us.tractat.kuilt.core.fabric.Mesh], which maintains the map atomically
 * with link publication and removal — it can never desync from the live link set. Seam
 * decorators (a gossip overlay) delegate to their base; a session facade exposes a
 * convenience view over the seam it rides.
 */
public interface PrincipalRoster {
    /**
     * Host-verified principals of currently-linked peers, keyed by the [PeerId] each was
     * verified against at admission. Peers with no attestation are absent. An entry is
     * removed when its peer's link drops; the map empties when the seam tears down.
     */
    public val attestedPrincipals: StateFlow<Map<PeerId, Principal>>
}
