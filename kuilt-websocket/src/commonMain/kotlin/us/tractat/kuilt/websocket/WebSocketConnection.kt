package us.tractat.kuilt.websocket

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.flushWithin
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import io.ktor.websocket.Frame as KtorFrame

/**
 * Adapts any Ktor [DefaultWebSocketSession] into a kuilt [Connection] — byte-transparent
 * binary framing, single-collection [incoming], idempotent [close].
 *
 * Works for both the client path
 * ([io.ktor.client.plugins.websocket.DefaultClientWebSocketSession]) and the server
 * path ([io.ktor.server.websocket.DefaultWebSocketServerSession]) — both extend
 * [DefaultWebSocketSession].
 *
 * **Primary use — in-process client spoke for [testApplication] tests.** Open a
 * client WebSocket session and wrap it into a kuilt [Connection] to build a spoke
 * that handshakes against a [KtorConnectionSource]-mounted hub route:
 *
 * ```kotlin
 * testApplication {
 *     val source = KtorConnectionSource(application, "/hub")
 *     val client = createClient { install(WebSockets) }
 *     client.webSocket("/hub") {
 *         val clientSeam = meshSeam(PeerId("client"), listOf(WebSocketConnection(this)), dispatcher)
 *         // clientSeam.peers now contains the hub's PeerId
 *     }
 * }
 * ```
 *
 * **Wire format:** byte-transparent. Each binary WebSocket frame's payload is the
 * whole message, delivered verbatim; no framing prefix, no in-band handshake. A
 * non-binary application frame is a protocol error and completes [incoming]
 * exceptionally, which tears the seam down.
 *
 * **incoming:** the session's binary frames, mapped to [ByteArray]. Single-collection
 * (the seam collects it exactly once). Close/EOF/error end the channel, completing the
 * flow so the seam transitions to `Torn`.
 *
 * **send:** a session that has closed throws [IOException] with the session's own exception as its
 * cause. Ktor reports a closed session as a [CancellationException]: Netty and CIO sessions cancel
 * their outgoing queue when the socket closes, and an OkHttp session closes its queue with one. A
 * caller nobody cancelled must not hear that as a cancellation (see [Connection.send]), so this tells
 * the two apart by asking whether the caller's own job is cancelled.
 *
 * **close:** closes the underlying session. Idempotent — Ktor's
 * [io.ktor.websocket.close] is a no-op once the session is already closing.
 *
 * **flush:** the answer depends on the Ktor engine under the session.
 *
 * - A server session, and a client session on the CIO, Darwin or browser engine, answers
 *   [FlushOutcome.Drained]. That rests on queue order and nothing else: a [close] goes down the session's
 *   own queue behind the frames ahead of it. `session.flush` skips that queue, so a frame can still be
 *   in it when this answers. Closing the `HttpClient`, cancelling the session or exiting the process
 *   instead of closing this connection can still lose such a frame (read from Ktor's source, not run).
 * - **OkHttp answers [FlushOutcome.Unsupported].** Its session ignores what `WebSocket.send` returns, so
 *   a frame past OkHttp's 16 MiB send queue is dropped with no error, and its `flush` is empty. Measured:
 *   3,000 frames of 16 KiB lost frames 3 runs of 3, with every flush saying `Drained`.
 * - **Any other engine answers [FlushOutcome.Unsupported] too**, since nobody has read what a returning
 *   send means on it.
 *
 * All three answer [FlushOutcome.Failed] once this connection has closed, or the session says it has.
 */
public class WebSocketConnection(
    private val session: DefaultWebSocketSession,
) : Connection {
    // Set by `close` rather than read off the session: an OkHttp session's queue stays open until the
    // far end answers the close, so `isClosedForSend` alone would call a closed link healthy.
    @Volatile
    private var closedHere = false

    private val closed: Boolean get() = closedHere || session.outgoing.isClosedForSend

    override suspend fun send(frame: ByteArray) {
        try {
            session.send(KtorFrame.Binary(fin = true, data = frame))
        } catch (closed: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("the WebSocket session is closed", closed)
        }
    }

    override suspend fun flush(timeout: Duration): FlushOutcome = flushWithin(timeout) {
        val confirms = session.canConfirmADrain()
        if (confirms) {
            try {
                session.flush()
            } catch (cause: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("the WebSocket session is closed", cause)
            }
        }
        when {
            // `flush` also returns normally on a session that has closed, having written nothing.
            closed -> FlushOutcome.Failed(IOException("the WebSocket session is closed"))
            confirms -> FlushOutcome.Drained
            else -> FlushOutcome.Unsupported
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        for (frame in session.incoming) {
            when (frame) {
                is KtorFrame.Binary -> emit(frame.readBytes())
                else -> throw IllegalArgumentException("unexpected non-binary frame: ${frame::class.simpleName}")
            }
        }
    }

    override suspend fun close() {
        closedHere = true
        session.close()
    }
}

/**
 * The Ktor client engines whose WebSocket session can back [FlushOutcome.Drained], by class name.
 *
 * - `CIOEngine`: run, against Netty and CIO servers, by the burst in `SeamConformanceSuite`.
 * - `DarwinClientEngine`: read, not run. Its writer awaits each send's completion, and a close frame
 *   takes its turn in the same queue.
 * - `JsClientEngine`: read, not run. A close frame takes its turn in the same queue, and the browser's
 *   `WebSocket.close` sends what it has buffered first.
 *
 * **To let OkHttp answer `Drained` after all, add `OkHttpEngine` here.** That is the whole reversal, and
 * the test pinning the current answer is `flushOnALiveSessionAnswersWhatItsEngineCanConfirm`.
 *
 * Names rather than types because the module has no dependency on most of these engines, and an engine a
 * consumer does not have on its classpath cannot be type-checked against. A name fails the safe way: an
 * engine this list does not recognise, a Ktor rename, or a shrinker renaming the class all give
 * [FlushOutcome.Unsupported], never a claim nobody checked. The cost is that an obfuscated release build
 * can answer `Unsupported` where its debug build answered `Drained`.
 */
private val ENGINES_THAT_CONFIRM_A_DRAIN = setOf("CIOEngine", "DarwinClientEngine", "JsClientEngine")

/** Whether [ENGINES_THAT_CONFIRM_A_DRAIN] names this engine. */
internal fun HttpClientEngine.canConfirmADrain(): Boolean = this::class.simpleName in ENGINES_THAT_CONFIRM_A_DRAIN

// A session that came from no client is a server's: real Netty and CIO servers are what the burst runs on.
private fun DefaultWebSocketSession.canConfirmADrain(): Boolean =
    (this as? ClientWebSocketSession)?.call?.client?.engine?.canConfirmADrain() ?: true
