package us.tractat.kuilt.websocket

/**
 * Per-dial request decoration for [KtorClientLoom], supplied fresh on every attempt by a
 * [us.tractat.kuilt.core.Weft]. Named for what it carries generically, not for the one use case
 * (auth) that motivated it — [queryParams]/[headers] are equally at home carrying a trace id or
 * a client-version header as a ticket.
 *
 * @property queryParams Appended to the dial URL alongside the existing `?peer=` query param,
 *   percent-encoded.
 * @property headers Set on the WebSocket-upgrade HTTP request.
 */
public data class WebSocketDialContext(
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)
