package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import us.tractat.kuilt.core.fabric.Conn
import io.ktor.websocket.Frame as KtorFrame

/**
 * Adapts a Ktor [DefaultWebSocketSession] into a message [Conn] so a 2-peer
 * WebSocket fabric is built on the shared [us.tractat.kuilt.core.fabric.identified]
 * seam rather than a hand-rolled receive loop.
 *
 * Works for both the client path
 * ([io.ktor.client.plugins.websocket.DefaultClientWebSocketSession]) and the server
 * path ([io.ktor.server.websocket.DefaultWebSocketServerSession]) — both extend
 * [DefaultWebSocketSession].
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
 * **close:** closes the underlying session. Idempotent — Ktor's
 * [io.ktor.websocket.close] is a no-op once the session is already closing.
 */
internal class WebSocketConn(
    private val session: DefaultWebSocketSession,
) : Conn {
    override suspend fun send(frame: ByteArray) {
        session.send(KtorFrame.Binary(fin = true, data = frame))
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
