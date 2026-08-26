package us.tractat.kuilt.scale

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network TCP mesh — sockets need a real IO dispatcher
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
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.stream.framed

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
            hubMesh(
                selfId = peerIds[i],
                connections = connsByPeer[i],
                dispatcher = Dispatchers.IO,
            )
        }
    }.awaitAll()

    InMemoryMesh(rawSeams.map { MeteredSeam(it) })
}

/** Open one loopback TCP socket pair, returning both ends as [Connection]s. */
private suspend fun tcpConnectionPair(selector: SelectorManager): Pair<Connection, Connection> =
    coroutineScope {
        // Bind 0 and read the port back off the bound socket — probing a free port and re-binding
        // the number is a TOCTOU: the probe closes before the real bind, so another process can
        // take the port in that window (#1590). Binding 0 has no window. This builder opens
        // N*(N-1)/2 pairs, so it is the densest port consumer in the tree.
        val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val acceptDeferred = async { server.accept() }
        val client: Socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        val accepted = acceptDeferred.await()
        server.close()
        toConnection(accepted) to toConnection(client)
    }

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
