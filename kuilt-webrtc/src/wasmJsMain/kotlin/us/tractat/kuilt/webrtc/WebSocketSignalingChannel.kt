@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.webrtc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.Weft
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.JsFun
import kotlin.js.JsAny

private val log = KotlinLogging.logger("us.tractat.kuilt.webrtc.WebSocketSignalingChannel")

/**
 * [SignalingChannel] backed by a browser `WebSocket`.
 *
 * Each [open] call dials `${baseUrl}/signaling/${room}` (with `wss://` if
 * the page is `https`, else `ws://`). The first impl of the server-side
 * route is `WS /signaling/{room}` in `:server`.
 *
 * **Per-dial credentials:** [weft] is invoked fresh inside every [open] call, so a caller can
 * mint a single-use credential (e.g. a short-lived ticket) as a query param rather than baking
 * one into a static [baseUrl]. Query params only — the browser `WebSocket` constructor cannot
 * set custom request headers. See [#1330](https://github.com/tractat-us/kuilt/issues/1330).
 */
public class WebSocketSignalingChannel(
    private val baseUrl: String,
    private val weft: Weft<Map<String, String>> = { emptyMap() },
) : SignalingChannel {
    override suspend fun open(room: String): SignalingSession {
        val url = buildSignalingUrl(baseUrl, room, weft())
        log.debug { "signaling open → $url" }
        val ws = createWebSocket(url)
        val openDeferred = CompletableDeferred<Unit>()
        val incoming = Channel<SignalingMessage>(Channel.UNLIMITED)

        wsSetOnOpen(ws) {
            log.debug { "signaling ws.onopen room=$room" }
            openDeferred.complete(Unit)
        }
        wsSetOnError(ws) {
            log.debug { "signaling ws.onerror room=$room openCompleted=${openDeferred.isCompleted}" }
            if (!openDeferred.isCompleted) {
                openDeferred.completeExceptionally(
                    IllegalStateException("Signaling WebSocket open failed"),
                )
            }
            incoming.close()
        }
        wsSetOnClose(ws) {
            log.debug { "signaling ws.onclose room=$room — closing inboundChannel" }
            incoming.close()
        }
        wsSetOnMessage(ws) { text ->
            runCatchingCancellable { SignalingMessageCodec.decode(text) }
                .onSuccess { msg ->
                    log.debug { "signaling ws.onmessage room=$room type=${msg::class.simpleName}" }
                    incoming.trySend(msg)
                }
        }

        openDeferred.await()
        log.debug { "signaling open complete room=$room" }
        return BrowserWebSocketSession(ws, incoming)
    }

    /**
     * Opens a session and awaits the server-assigned [SignalingMessage.Role] frame.
     * Returns the role alongside the session. The role frame is consumed
     * from the underlying channel before returning; the session's [SignalingSession.incoming]
     * flow starts at the first offer/answer/ICE frame.
     *
     * Unlike [open], this method uses [Channel.receive] directly so the rest of
     * [SignalingSession.incoming] remains collectible. (Calling [kotlinx.coroutines.flow.first]
     * on a [receiveAsFlow] / [kotlinx.coroutines.flow.consumeAsFlow] would cancel the
     * underlying channel.)
     *
     * Use this when both tabs are symmetric peers and the relay breaks the host/joiner
     * tie. See [WebRTCPeerLinkFactory.openWithServerRole].
     */
    public suspend fun openWithRole(room: String): Pair<Boolean, SignalingSession> {
        val session = open(room) as BrowserWebSocketSession
        log.debug { "signaling openWithRole room=$room — awaiting Role frame via receive()" }
        val role =
            (session.inboundChannel.receive() as? SignalingMessage.Role)
                ?: error("Expected Role frame as first message from signaling relay")
        log.debug { "signaling openWithRole room=$room — Role received isHost=${role.host}" }
        return role.host to session
    }
}

/** Pure URL-building for [WebSocketSignalingChannel.open] — no browser API needed, directly testable. */
internal fun buildSignalingUrl(
    baseUrl: String,
    room: String,
    queryParams: Map<String, String>,
): String {
    val base = "$baseUrl/signaling/$room"
    if (queryParams.isEmpty()) return base
    val encoded =
        queryParams.entries.joinToString("&") { (key, value) ->
            "${jsEncodeURIComponent(key)}=${jsEncodeURIComponent(value)}"
        }
    return "$base?$encoded"
}

private class BrowserWebSocketSession(
    private val ws: JsAny,
    val inboundChannel: Channel<SignalingMessage>,
) : SignalingSession {
    override val incoming: Flow<SignalingMessage> = inboundChannel.receiveAsFlow()

    override suspend fun send(message: SignalingMessage) {
        wsSend(ws, SignalingMessageCodec.encode(message))
    }

    override suspend fun close() {
        log.debug { "signaling session.close() — wsClose + inboundChannel.close" }
        wsClose(ws)
        inboundChannel.close()
    }
}

// ── Browser WebSocket bindings ─────────────────────────────────────────────────
// org.w3c.dom.WebSocket is a JS-target type; in Kotlin/Wasm we declare externals
// ourselves and use @JsFun wrappers to avoid extension-receiver restrictions.

@JsFun("(url) => new WebSocket(url)")
private external fun createWebSocket(url: String): JsAny

@JsFun("(ws, handler) => { ws.onopen = () => handler(); }")
private external fun wsSetOnOpen(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onerror = () => handler(); }")
private external fun wsSetOnError(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onclose = () => handler(); }")
private external fun wsSetOnClose(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onmessage = (event) => handler(event.data); }")
private external fun wsSetOnMessage(
    ws: JsAny,
    handler: (String) -> Unit,
)

@JsFun("(ws, text) => ws.send(text)")
private external fun wsSend(
    ws: JsAny,
    text: String,
)

@JsFun("(ws) => ws.close()")
private external fun wsClose(ws: JsAny)

@JsFun("(s) => encodeURIComponent(s)")
private external fun jsEncodeURIComponent(s: String): String
