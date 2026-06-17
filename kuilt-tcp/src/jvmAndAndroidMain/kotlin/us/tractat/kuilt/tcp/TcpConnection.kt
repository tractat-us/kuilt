package us.tractat.kuilt.tcp

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.stream.framed

/**
 * Adapt a connected Ktor [Socket] into a message [Connection].
 *
 * The socket's read/write channels bridge to kotlinx-io `Source`/`Sink` via the
 * blocking JVM adapters (`toInputStream`/`toOutputStream`), then feed `:kuilt-stream`'s
 * [framed] for 4-byte length-prefix framing. The cold, single-collection `Connection` is
 * handed straight to `handshaking`, which now consumes `incoming` with a single
 * collection — no hot-reader pump is needed. [incoming]'s blocking pull-reads are
 * pinned to [ioDispatcher] with [flowOn], so the seam's confinement dispatcher only
 * serialises state and never blocks on the wire.
 */
internal fun tcpConnection(socket: Socket, ioDispatcher: CoroutineDispatcher): Connection {
    val source = socket.openReadChannel().toInputStream().asSource().buffered()
    val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    val framed = framed(source, sink)
    return object : Connection {
        override suspend fun send(frame: ByteArray) = framed.send(frame)
        override val incoming: Flow<ByteArray> = framed.incoming.flowOn(ioDispatcher)
        override suspend fun close() {
            framed.close()
            socket.close()
        }
    }
}
