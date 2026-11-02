package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Open a WebSocket to [urlString] and hand the session to the caller, or leave nothing behind.
 *
 * Walk away from a dial and the socket goes with it. Cancel this call while the far end is still
 * answering, and the request is aborted. Cancel it in the instant the session arrives, and that
 * session is closed with a normal close, since nobody will ever hold it. Only a return makes the
 * session yours, and from then on closing it is your job.
 *
 * Use this in place of Ktor's `webSocketSession`, which does not make that promise. That one runs the
 * handshake on the [HttpClient]'s scope and only waits for it, so a cancel ends the wait and nothing
 * else. The finished socket then stays open with no owner until the session ends from outside or the
 * client closes. The far end sees a healthy connection the whole time.
 *
 * It differs in two more ways, both about failure:
 *
 *  - On an [HttpClient] that has already closed this throws [IOException]. Ktor's call never returns
 *    there: its handshake coroutine is cancelled before it starts, so nothing completes what the
 *    caller is waiting on.
 *  - A cancellation that is not the caller's own arrives as [IOException]. The caller's job is alive,
 *    so a `CancellationException` would cancel it rather than fail it, with no handler and no stack
 *    trace (see [us.tractat.kuilt.core.Loom.weave]).
 *
 * **What it does not bound.** There is no clock on the dial, and an [HttpClient] closed during one
 * does not end it. Put a ceiling on the client with `install(HttpTimeout)`, or wrap this call in a
 * timeout, which is an ordinary cancel and leaves no socket either.
 *
 * The session's close is the caller's only obligation once this returns. Wrap it in
 * [WebSocketConnection] to build a seam on it.
 *
 * @param urlString The `ws://` or `wss://` URL to dial.
 * @param block Further request configuration, such as headers.
 * @throws IOException if [HttpClient] is closed, or the dial ends in a cancellation nobody asked for.
 */
public suspend fun HttpClient.ownedWebSocketSession(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit = {},
): DefaultClientWebSocketSession {
    plugin(WebSockets)
    val statement = prepareRequest {
        url {
            protocol = URLProtocol.WS
            port = protocol.defaultPort
        }
        url.takeFrom(urlString)
        block()
    }
    val arrived = CompletableDeferred<DefaultClientWebSocketSession>()

    // The session lives inside `body`'s block and ends when that returns, so something has to stay in
    // it for as long as the session is open, and that cannot be the caller. This is the shape of Ktor's
    // own `webSocketSession`, with the one thing it throws away kept: the Job, which is the only handle
    // that can abort the request. Parented to the client, so its failure is caught rather than let out
    // to cancel the client.
    val dial = launch {
        try {
            statement.body<DefaultClientWebSocketSession, Unit> { session ->
                val ended = CompletableDeferred<Unit>()
                session.outgoing.invokeOnClose { cause ->
                    if (cause == null) ended.complete(Unit) else ended.completeExceptionally(cause)
                }
                arrived.complete(session)
                try {
                    ended.await()
                } catch (abandoned: CancellationException) {
                    closeUnclaimed(session, ended)
                    throw abandoned
                }
            }
        } catch (failure: Throwable) {
            arrived.completeExceptionally(failure)
        }
    }
    // A coroutine launched on a closed client is cancelled before it starts, so the `try` above never
    // runs and nothing else would complete this. A no-op on every other path.
    dial.invokeOnCompletion { arrived.completeExceptionally(IOException("the HttpClient is closed")) }

    try {
        return arrived.await()
    } catch (abandoned: CancellationException) {
        // Whichever window the cancel landed in, this reaches it. Mid-handshake, it aborts the request.
        // With the session already delivered, the caller has taken this exception instead of the session
        // (a cancelled coroutine does not resume with a value), and `dial` closes it.
        dial.cancel()
        currentCoroutineContext().ensureActive()
        throw IOException("the WebSocket dial ended without a session", abandoned)
    }
}

/**
 * Close a session the caller will never hold, with a normal close the far end can read.
 *
 * Shielded because the cancel is what brought us here. Bounded because the shield removes the only
 * other way out, and a far end that has stopped reading can hold a Close frame up for as long as it
 * likes. Giving up costs only the courtesy: leaving `body` cancels the call, and that ends the socket.
 *
 * Nothing here is caught. [close] swallows its own failures, and anything else lands in the dial's
 * catch-all, which has nobody left to tell and still leaves `body`.
 */
private suspend fun closeUnclaimed(session: DefaultClientWebSocketSession, ended: CompletableDeferred<Unit>) {
    withContext(NonCancellable) {
        withTimeoutOrNull(UNCLAIMED_CLOSE_BOUND) {
            session.close()
            ended.join()
        }
    }
}

/** How long an unclaimed session gets to say goodbye before its socket is cut. */
private val UNCLAIMED_CLOSE_BOUND = 2.seconds
