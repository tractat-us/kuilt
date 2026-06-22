@file:Suppress("ForbiddenImport") // real-network TCP mesh — sockets need a real IO dispatcher

package us.tractat.kuilt.scale

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.stream.framed
import java.net.ServerSocket as JvmServerSocket

/**
 * Builds a fully-connected TCP mesh of [n] peers on loopback sockets.
 *
 * Opens N*(N-1)/2 real TCP socket pairs (one per edge in a complete graph),
 * assembles per-node connection lists, then calls [meshSeam] on all nodes
 * concurrently so the MeshHello preambles cross in parallel.
 *
 * This is Layer B of the kuilt-scale harness: real-IO, real wall-clock,
 * real socket/fd counts. Only runs when [ScaleTcpTests.enabled] is true.
 *
 * @param selector Ktor [SelectorManager] for socket operations (must be on a real IO dispatcher).
 */
@Suppress("ForbiddenMethodCall") // real-network — sockets need real IO dispatcher
internal suspend fun buildTcpMesh(
    n: Int,
    selector: SelectorManager,
): InMemoryMesh = coroutineScope {
    require(n >= 2) { "TCP mesh requires at least 2 peers, got $n" }
    val peerIds = (0 until n).map { PeerId("tcp-peer-$it") }

    val connsByPeer: Array<MutableList<Connection>> = Array(n) { mutableListOf() }

    val topology = Topology.Complete
    for ((i, j) in topology.edges(n)) {
        val (connI, connJ) = tcpConnectionPair(selector)
        connsByPeer[i].add(connI)
        connsByPeer[j].add(connJ)
    }

    val rawSeams: List<Seam> = (0 until n).map { i ->
        async {
            @Suppress("ForbiddenMethodCall") // real dispatcher for TCP IO
            meshSeam(
                selfId = peerIds[i],
                connections = connsByPeer[i],
                dispatcher = Dispatchers.IO,
            )
        }
    }.awaitAll()

    InMemoryMesh(rawSeams.map { MeteredSeam(it) })
}

/** Open one loopback TCP socket pair, returning both ends as [Connection]s. */
@Suppress("ForbiddenMethodCall") // real-network loopback pair — needs real IO
private suspend fun tcpConnectionPair(selector: SelectorManager): Pair<Connection, Connection> =
    coroutineScope {
        val port = JvmServerSocket(0).use { it.localPort }
        val server = aSocket(selector).tcp().bind("127.0.0.1", port)
        val acceptDeferred = async { server.accept() }
        val client: Socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        val accepted = acceptDeferred.await()
        server.close()
        toConnection(accepted) to toConnection(client)
    }

@Suppress("ForbiddenMethodCall") // real-network — blocking IO adapters need real IO dispatcher
private fun toConnection(socket: Socket): Connection {
    val source = socket.openReadChannel().toInputStream().asSource().buffered()
    val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    val framingConn = framed(source, sink)
    return object : Connection {
        override suspend fun send(frame: ByteArray) = framingConn.send(frame)
        override val incoming: Flow<ByteArray> = framingConn.incoming.flowOn(Dispatchers.IO)
        override suspend fun close() {
            framingConn.close()
            socket.close()
        }
    }
}
