package us.tractat.kuilt.websocket

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag

/**
 * [Tag] for WebSocket transports.
 *
 * Carries the `ws://` or `wss://` URL needed by [KtorClientLoom.join]
 * plus the server's [PeerId] so both ends arrive at the same membership view
 * without any in-band handshake.
 *
 * @property url WebSocket endpoint URL, e.g. `ws://192.168.1.1:8080/live/session`.
 * @property serverPeerId The server's [PeerId] for this endpoint. Must match the
 *   `selfPeerId` the corresponding [KtorServerLoom] was constructed with.
 * @property displayName Human-readable name for the local peer.
 * @property roomKey The stable room identity this joiner targets, matched against the
 *   host's room before admission. Defaults to `null` (**permissive**) — a WebSocket relay
 *   binds the room by URL path, so the transport already names the target and no host-side
 *   room check is needed. The property lets a consumer set it on a flat fabric. See [Tag.roomKey].
 */
public data class WebSocketAdvertisement(
    val url: String,
    val serverPeerId: PeerId,
    override val displayName: String,
    override val roomKey: String? = null,
) : Tag {
    override val peerKey: String get() = serverPeerId.value
}
