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
import kotlinx.coroutines.Job
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
 * Walk away from a dial and the socket goes with it, before the cancellation reaches you. Cancel this
 * call while the far end is still answering, and the request is aborted. Cancel it in the instant the
 * session arrives, and that session gets a normal close, since nobody will ever hold it. Only a return
 * makes the session yours, and from then on closing it is your job.
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
 * Wrap the returned session in [WebSocketConnection] to build a seam on it.
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
                ended.await()
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
        // Shielded because the cancel is what brought us here, and the socket is gone before it goes on.
        withContext(NonCancellable) { abandonDial(arrived, dial) }
        currentCoroutineContext().ensureActive()
        throw IOException("the WebSocket dial ended without a session", abandoned)
    }
}

/**
 * End a dial whose caller has gone, on whichever side of the session's arrival that happened.
 *
 * With no session yet, cancelling [dial] aborts the request. Once one has arrived, cancelling is the
 * wrong tool: it takes the session down with the call, and the far end hears a dropped connection. So
 * that session gets an ordinary close instead, and [dial] ends by itself when the session does. A
 * caller reaches this with a session delivered because a cancelled coroutine does not resume with a
 * value, even one that was already on its way.
 *
 * [arrived]'s `cancel` settles which side this is, since from then on it cannot take a session. If the
 * dial finishes in that same instant, its `complete` loses and it is simply cancelled below.
 *
 * Bounded because the shield around this removes the only other way out, and a far end that has
 * stopped reading can hold a Close frame up for as long as it likes. Giving up costs only the courtesy:
 * the last line cuts the call. Nothing here is caught, because `close` swallows its own failures and a
 * cancelled dial reports nothing to `join`.
 */
private suspend fun abandonDial(arrived: CompletableDeferred<DefaultClientWebSocketSession>, dial: Job) {
    arrived.cancel()
    withTimeoutOrNull(ABANDON_BOUND) {
        if (arrived.isCancelled) dial.cancel() else arrived.await().close()
        dial.join()
    }
    dial.cancel()
}

/** How long an abandoned dial gets to end politely before its call is cut. */
private val ABANDON_BOUND = 2.seconds
