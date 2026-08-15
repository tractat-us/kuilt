package us.tractat.kuilt.websocket

import us.tractat.kuilt.core.TransportRole

/**
 * Query parameter the client appends to the WebSocket URL so the server can
 * read the client's [us.tractat.kuilt.core.PeerId] out-of-band.
 *
 * Client writes: `?peer=<uuid>` — see [KtorClientLoom].
 * Server reads: `call.request.queryParameters[PEER_QUERY_PARAM]` — see [KtorServerLoom].
 */
internal const val PEER_QUERY_PARAM: String = "peer"

/**
 * The WebSocket fabric's [TransportRole] set: frames reach a peer by relaying through a server
 * ([TransportRole.ServerRelay]) and that relay carries application data ([TransportRole.Data]).
 *
 * One constant shared by the pre-connect `Loom.capability()` report and the live
 * `Seam.capability` view, on both the client and server looms, so the two answers about the same
 * fabric cannot drift apart. **Static**: unlike a radio-bearing fabric, these roles do not narrow
 * when the device goes offline — a relay fabric with a dead radio is still a relay fabric, just
 * unavailable, and #1712's split puts that in the availability half.
 */
internal val RELAY_ROLES: Set<TransportRole> = setOf(TransportRole.ServerRelay, TransportRole.Data)
