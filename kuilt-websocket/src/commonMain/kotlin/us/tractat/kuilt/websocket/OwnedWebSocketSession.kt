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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Open a WebSocket to [urlString] and hand the session to the caller, or give it up.
 *
 * Walk away from a dial and the socket goes with it, on every engine but one. Cancel this call while
 * the far end is still answering, and on CIO and Darwin the request is aborted. Cancel it in the
 * instant the session arrives, and that session gets a normal close before the cancellation reaches
 * you, since nobody will ever hold it. Only a return makes the session yours, and from then on closing
 * it is your job. A cancel does not wait for an abort to land, so the socket can outlive this call by
 * a moment.
 *
 * Use this in place of Ktor's `webSocketSession`, which does not make that promise. That one runs the
 * handshake on the [HttpClient]'s scope and only waits for it, so a cancel ends the wait and nothing
 * else. The finished socket then stays open with no owner until the session ends from outside or the
 * client closes. The far end sees a healthy connection the whole time.
 *
 * **Where a cancel before the upgrade does not end the socket.** Pick CIO or Darwin if a far end might
 * never answer.
 *
 *  - OkHttp does not abort. The socket waits on OkHttp's own read timeout, about 10 s by default, and
 *    with `readTimeout(0)` it was still open at 30 s. A far end that answers after the cancel gets a
 *    finished upgrade and then a `1011` Close frame. Nothing here can reach that socket.
 *  - Darwin loses one race. An answer written right behind the cancel, on the first dial of a process,
 *    beat the cancel in 12 of 12 fresh processes, and the socket was still open at 4 to 8 s in eleven
 *    and at 90 s in the twelfth. With 1 ms or more between the two it ended in 21 of 21. Rarely, a dial
 *    cancelled as the session arrives stays open too, with no Close frame (see #3102).
 *
 * It differs in two more ways, both about failure:
 *
 *  - On an [HttpClient] that has closed, with no session still open on it, this throws [IOException].
 *    Ktor's call never returns there: its handshake coroutine is cancelled before it starts, so
 *    nothing completes what the caller is waiting on. A session still open keeps the client's job
 *    active, and nothing public says the client closed, so the dial simply goes ahead. On CIO and
 *    OkHttp it reached the far end and returned a session, and it was still waiting at 10 s on a far
 *    end that stayed silent. Darwin is unmeasured.
 *  - A cancellation that is not the caller's own arrives as [IOException]. The caller's job is alive,
 *    so a `CancellationException` would cancel it rather than fail it, with no handler and no stack
 *    trace (see [us.tractat.kuilt.core.Loom.weave]).
 *
 * **What it does not bound.** There is no clock on the dial, and an [HttpClient] closed during one
 * does not end it. To bound it, wrap this call in a timeout, which is an ordinary cancel with the
 * outcomes above. `install(HttpTimeout)` is not a way to bound it: Ktor turns the request timeout off
 * for a WebSocket request, so on CIO a 500 ms `requestTimeoutMillis` was still waiting at 13 s on a far
 * end holding its `101`, and a 500 ms `socketTimeoutMillis` killed an idle, healthy session within 3 s.
 *
 * A closed session keeps its socket until the far end answers the Close frame, because ending it sooner
 * can lose the frame, and a far end that never answers gets two seconds. So a cancelled caller waits at
 * most two seconds too. OkHttp keeps the socket waiting for that answer on a clock of its own, which was
 * still running 15 seconds later.
 *
 * Wrap the returned session in [WebSocketConnection] to build a seam on it.
 *
 * @param urlString The `ws://` or `wss://` URL to dial.
 * @param block Further request configuration, such as headers.
 * @throws IOException if [HttpClient] has closed with no session still open on it, or the dial ends in
 *   a cancellation nobody asked for.
 */
public suspend fun HttpClient.ownedWebSocketSession(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit = {},
): DefaultClientWebSocketSession = ownedWebSocketSession(urlString, CLOSE_BOUND, block)

/** [ownedWebSocketSession] with its bound in the caller's hands, so a test can make waiting it out visible. */
internal suspend fun HttpClient.ownedWebSocketSession(
    urlString: String,
    closeBound: Duration,
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
                ended.join()
                session.awaitTheFarEndsAnswer(closeBound)
                ended.await()
            }
        } catch (failure: Throwable) {
            arrived.completeExceptionally(failure)
        }
    }
    // A coroutine launched on a closed client with no session left on it is cancelled before it starts,
    // so the `try` above never runs and nothing else would complete this. With a session left, the
    // client's job is still active and the dial runs like any other. A no-op on every other path.
    dial.invokeOnCompletion { arrived.completeExceptionally(IOException("the HttpClient is closed")) }

    try {
        return arrived.await()
    } catch (abandoned: CancellationException) {
        // Shielded because the cancel is what brought us here, and the close would be the first casualty.
        withContext(NonCancellable) { abandonDial(arrived, dial, closeBound) }
        currentCoroutineContext().ensureActive()
        throw IOException("the WebSocket dial ended without a session", abandoned)
    }
}

/**
 * Keep the call open until the far end has heard this session's Close frame, or [bound] runs out.
 *
 * Returning from the dial's block ends the call, and on CIO and Darwin a session's outgoing side closes
 * the moment its Close frame is queued, before the socket's writer has run. A call ended on that signal
 * cancels the writer with the frame still in its queue, and the far end hears a dropped connection.
 * Nothing reports the frame reaching the socket: on CIO it crosses another coroutine after the writer.
 * The first proof is the far end's answer, or the socket ending, and either one ends the session's job.
 *
 * On OkHttp and in the browser the session's job is the call's own, which cannot end before this
 * returns. Waiting on it would only ever time out, and it is not needed: there the outgoing side stays
 * open until the far end has answered.
 */
private suspend fun DefaultClientWebSocketSession.awaitTheFarEndsAnswer(bound: Duration) {
    val sessionJob = coroutineContext.job
    if (sessionJob !== call.coroutineContext.job) withTimeoutOrNull(bound) { sessionJob.join() }
}

/**
 * End a dial whose caller has gone, on whichever side of the session's arrival that happened.
 *
 * With no session yet, cancelling [dial] is all there is to do. CIO and Darwin abort the request, and
 * OkHttp lets it run on, which a join here would not change, so this does not wait to see which. Once
 * a session has arrived, cancelling first is the wrong tool: it takes the session down with the call,
 * and the far end hears a dropped connection. So that session gets an ordinary close, and [dial] ends
 * by itself when the session does. A caller reaches this with a session delivered because a cancelled
 * coroutine does not resume with a value, even one that was already on its way.
 *
 * [arrived]'s `cancel` settles which side this is, since from then on it cannot take a session. If the
 * dial finishes in that same instant, its `complete` loses and the cancel below ends it.
 *
 * Bounded because the shield around this removes the only other way out, and a far end that never
 * answers the Close frame can hold the session up for as long as it likes. Giving up costs only the
 * courtesy. Nothing here is caught, because `close` swallows its own failures and `join` reports none.
 */
private suspend fun abandonDial(
    arrived: CompletableDeferred<DefaultClientWebSocketSession>,
    dial: Job,
    bound: Duration,
) {
    arrived.cancel()
    if (!arrived.isCancelled) {
        withTimeoutOrNull(bound) {
            arrived.await().close()
            dial.join()
        }
    }
    dial.cancel()
}

/** How long a closing session gets to end politely before its call is cut. */
internal val CLOSE_BOUND = 2.seconds
