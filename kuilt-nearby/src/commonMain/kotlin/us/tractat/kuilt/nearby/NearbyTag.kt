package us.tractat.kuilt.nearby

import us.tractat.kuilt.core.Tag

/**
 * A [Tag] advertising a Nearby Connections endpoint.
 *
 * [peerKey] doubles as the local endpointId assigned by the Nearby runtime.
 * Note: local endpointIds are not stable across sessions, so the stable peer
 * identity is exchanged during the connect handshake — see [ConnectStateMachine].
 */
public data class NearbyTag(
    override val displayName: String,
    override val peerKey: String,
) : Tag
