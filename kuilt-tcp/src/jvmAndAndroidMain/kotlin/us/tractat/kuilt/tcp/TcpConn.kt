package us.tractat.kuilt.tcp

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.kuilt.core.fabric.Conn
import us.tractat.kuilt.stream.framed

/**
 * Adapt a connected Ktor [Socket] into a message [Conn].
 *
 * The socket's read/write channels bridge to kotlinx-io `Source`/`Sink` via the
 * blocking JVM adapters (`toInputStream`/`toOutputStream`), feed `:kuilt-stream`'s
 * [framed] for 4-byte length-prefix framing, then [pumped] to make `incoming` a hot,
 * re-collectable flow on [ioDispatcher] — the single-reader contract `handshaking`
 * requires. The whole transport-specific bridge is these few lines.
 */
internal fun tcpConn(socket: Socket, ioDispatcher: CoroutineDispatcher): Conn {
    val source = socket.openReadChannel().toInputStream().asSource().buffered()
    val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    val pumped = framed(source, sink).pumped(ioDispatcher)
    return object : Conn by pumped {
        override suspend fun close() {
            pumped.close()
            socket.close()
        }
    }
}
