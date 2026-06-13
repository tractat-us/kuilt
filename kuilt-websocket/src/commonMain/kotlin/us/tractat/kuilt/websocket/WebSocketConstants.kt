package us.tractat.kuilt.websocket

/**
 * Query parameter the client appends to the WebSocket URL so the server can
 * read the client's [us.tractat.kuilt.core.PeerId] out-of-band.
 *
 * Client writes: `?peer=<uuid>` — see [KtorClientLoom].
 * Server reads: `call.request.queryParameters[PEER_QUERY_PARAM]` — see [KtorServerLoom].
 */
internal const val PEER_QUERY_PARAM: String = "peer"
