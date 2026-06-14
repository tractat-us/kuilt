package us.tractat.kuilt.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Conn
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.stream.framed
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The headline of the pluggable fabric kit, told without Ktor: a **proprietary,
 * in-house stream RPC** — here a plain [java.net.Socket] — becomes a kuilt
 * [Seam] with the *same* adapter [TcpConn] uses.
 *
 * The transport-specific code is just this: take whatever byte duplex your RPC
 * exposes (here `socket.inputStream` / `socket.outputStream`), wrap it as a
 * kotlinx-io `Source`/`Sink`, pass it to [framed] for length-prefix framing, then
 * to [handshaking] to negotiate identity in-band. That is the entire bridge — the
 * RPC's own connect/accept is all that is transport-specific.
 *
 * This test is the artifact the "extend with your own fabric" tutorial cites: it
 * proves the kit generalises beyond Ktor to any `InputStream`/`OutputStream`.
 */
@Suppress("ForbiddenMethodCall") // real-socket example — blocking reads need a real IO dispatcher
class ProprietaryRpcExampleTest {

    /**
     * The whole adapter: a byte-stream socket → a kuilt [Conn]. `framed()` adds
     * length-prefix framing; `pumped()` makes the blocking reads a hot, re-collectable
     * flow on an IO dispatcher (the single-reader glue `handshaking` requires).
     */
    private fun rpcConn(socket: Socket): Conn =
        framed(
            source = socket.getInputStream().asSource().buffered(),
            sink = socket.getOutputStream().asSink().buffered(),
        ).pumped(Dispatchers.IO)

    /** Wrap a connected socket as a 2-peer kuilt [Seam], identity negotiated in-band. */
    private suspend fun weaveSeam(socket: Socket, selfId: PeerId): Seam =
        handshaking(rpcConn(socket), selfId, Dispatchers.IO)

    @Test
    fun aProprietarySocketRpcBecomesAKuiltSeam() = runBlocking {
        withContext(Dispatchers.IO) {
            ServerSocket(0).use { server ->
                withTimeout(10.seconds) {
                    coroutineScope {
                        // Weave both ends concurrently: handshaking exchanges Hello preambles
                        // in-band, so each side's weave suspends until the other has begun.
                        val hostDeferred = async { weaveSeam(server.accept(), PeerId("rpc-host")) }
                        val client = weaveSeam(
                            Socket("127.0.0.1", server.localPort),
                            PeerId("rpc-client"),
                        )
                        val host = hostDeferred.await()

                        val clientReceives = async { client.incoming.first() }
                        host.broadcast("hello from the in-house RPC".encodeToByteArray())

                        assertEquals(
                            "hello from the in-house RPC",
                            clientReceives.await().payload.decodeToString(),
                        )
                    }
                }
            }
        }
    }
}
