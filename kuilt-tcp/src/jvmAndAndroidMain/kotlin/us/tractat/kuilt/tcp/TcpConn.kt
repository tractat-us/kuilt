package us.tractat.kuilt.tcp

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.fabric.Conn
import us.tractat.kuilt.stream.framed

/**
 * Adapt a connected Ktor [Socket] into a message [Conn].
 *
 * The socket's read/write channels are bridged to kotlinx-io `Source`/`Sink` via
 * the blocking JVM adapters (`toInputStream`/`toOutputStream`), then handed to
 * `:kuilt-stream`'s [framed] which owns 4-byte length-prefix framing.
 *
 * **Hot single-reader pump.** [framed]'s `incoming` is a *cold* flow whose blocking
 * `Source` reads must not be collected more than once. But [Conn.incoming]'s
 * consumers collect it twice in sequence — `handshaking` reads the identity preamble
 * with `firstFrame()`, then `identified` installs its own read loop. A single pump
 * coroutine therefore collects [framed] once on [ioDispatcher] and re-publishes
 * frames through an unbounded [Channel]; [incoming] is `receiveAsFlow()` over it, so
 * the two consecutive collectors share one underlying socket read (the same pattern
 * the WebSocket fabric uses). The pump runs on [ioDispatcher] so the blocking reads
 * never occupy the seam's confinement dispatcher.
 */
internal fun tcpConn(socket: Socket, ioDispatcher: CoroutineDispatcher): Conn =
    TcpConn(socket, ioDispatcher)

private class TcpConn(
    private val socket: Socket,
    ioDispatcher: CoroutineDispatcher,
) : Conn {
    private val source = socket.openReadChannel().toInputStream().asSource().buffered()
    private val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    private val delegate = framed(source, sink)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            // A wire close (local close() cancels the channel; a peer disconnect EOFs it)
            // surfaces as a ClosedByteChannelException/IOException from the blocking read.
            // Treat any such end-of-stream as a normal completion of incoming — but let a
            // CancellationException propagate so structured cancellation still works.
            runCatchingCancellable { delegate.incoming.collect { inbox.send(it) } }
            inbox.close()
        }
    }

    override suspend fun send(frame: ByteArray) = delegate.send(frame)

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    // Best-effort teardown: closing a kotlinx-io source/sink backed by an already
    // cancelled Ktor channel throws ClosedByteChannelException. close() is idempotent
    // and must not propagate that — swallow it (but never a CancellationException).
    override suspend fun close() {
        scope.cancel()
        runCatchingCancellable { delegate.close() }
        socket.close()
    }
}
