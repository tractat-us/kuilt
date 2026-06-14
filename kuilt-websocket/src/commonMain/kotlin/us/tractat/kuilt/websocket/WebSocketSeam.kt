package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] backed by a raw Ktor [DefaultWebSocketSession].
 *
 * Works for both the client path ([io.ktor.client.plugins.websocket.DefaultClientWebSocketSession])
 * and the server path ([io.ktor.server.websocket.DefaultWebSocketServerSession]) — both
 * extend [DefaultWebSocketSession].
 *
 * The seam itself is the shared [identified] 2-peer fabric; this factory only adapts the
 * Ktor session into a [WebSocketConn] and supplies the known identities. The receive loop,
 * outbound serialization, sequence numbering and single-shot teardown all live in `identified`.
 *
 * **Wire format:** byte-transparent. Each binary WebSocket frame's payload is delivered
 * verbatim as [us.tractat.kuilt.core.Swatch.payload]; no framing prefix, no in-band
 * handshake (see [WebSocketConn]).
 *
 * **PeerId discovery:** both [selfId] and [remoteId] are supplied at construction time by
 * the calling factory. Identity is exchanged out of band — the client passes its [PeerId]
 * in the URL query (`?peer=<id>`), and the server's [PeerId] is part of the
 * [WebSocketAdvertisement].
 *
 * @param dispatcher Scheduling scope for the seam's read/write loops. `identified` is
 *   thread-safe via atomics, so this is purely a scheduler — the Looms default it to
 *   `Dispatchers.Default.limitedParallelism(1)`.
 */
internal fun WebSocketSeam(
    selfId: PeerId,
    remoteId: PeerId,
    session: DefaultWebSocketSession,
    dispatcher: CoroutineContext,
): Seam = identified(WebSocketConn(session), selfId, remoteId, dispatcher)
