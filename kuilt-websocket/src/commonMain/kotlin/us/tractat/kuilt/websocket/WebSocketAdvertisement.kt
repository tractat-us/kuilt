package us.tractat.kuilt.websocket

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag

/**
 * [Tag] for WebSocket transports.
 *
 * Carries the `ws://` or `wss://` URL needed by [KtorClientPeerLinkFactory.join]
 * plus the server's [PeerId] so both ends arrive at the same membership view
 * without any in-band handshake.
 *
 * @property url WebSocket endpoint URL, e.g. `ws://192.168.1.1:8080/live/session`.
 * @property serverPeerId The server's [PeerId] for this endpoint. Must match the
 *   `selfId` the corresponding [KtorServerPeerLinkFactory] was constructed with.
 * @property displayName Human-readable name for the local peer.
 */
public data class WebSocketAdvertisement(
    val url: String,
    val serverPeerId: PeerId,
    override val displayName: String,
) : Tag {
    override val peerKey: String get() = serverPeerId.value
}
