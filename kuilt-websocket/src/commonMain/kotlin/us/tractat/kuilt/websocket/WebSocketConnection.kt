package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import us.tractat.kuilt.core.fabric.Connection
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
 */
public class WebSocketConnection(
    private val session: DefaultWebSocketSession,
) : Connection {
    override suspend fun send(frame: ByteArray) {
        try {
            session.send(KtorFrame.Binary(fin = true, data = frame))
        } catch (closed: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("the WebSocket session is closed", closed)
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
        session.close()
    }
}
