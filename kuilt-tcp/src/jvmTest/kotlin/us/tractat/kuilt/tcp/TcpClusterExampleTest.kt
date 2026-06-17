@file:Suppress("ForbiddenImport") // real-network loopback cluster example — TCP sockets need a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.meshSeam
import java.net.ServerSocket as JvmServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Stands up a small TCP **cluster** over real loopback sockets: a hub peer A builds a
 * [meshSeam] to peer B, then a late joiner C dials in and is admitted with [Mesh.addLink].
 * Each spoke (B, C) is itself a single-link [meshSeam] to A, so all three speak the same
 * `MeshHello` preamble on the wire. This proves the cluster path end-to-end — the mesh
 * handshake, dynamic join, broadcast, and per-sender attribution — over real TCP, not just
 * the 2-peer `handshaking` path that [TcpConformanceTest] and [TcpRoundTripTest] exercise.
 *
 * Real-IO test (`runBlocking`, real dispatchers): sockets cannot be driven by a virtual
 * clock. A [withTimeout] guards against a hang without coupling to a test scheduler.
 */
class TcpClusterExampleTest {

    @Suppress("ForbiddenMethodCall") // real-network loopback cluster example — sockets need a real IO dispatcher
    private val selector = SelectorManager(Dispatchers.IO)

    @AfterTest
    fun tearDown() = selector.close()

    @Test
    fun threePeerTcpClusterFormsViaMeshSeamAndAddLink() = runBlocking {
        withTimeout(20.seconds) {
            coroutineScope {
                val a = PeerId("peer-a")
                val b = PeerId("peer-b")
                val c = PeerId("peer-c")

                // One real loopback socket pair per spoke. accept() yields A's end; connect() B's/C's.
                val (aToB, bToA) = tcpConnectionPair()
                val (aToC, cToA) = tcpConnectionPair()

                // Hub A weaves a mesh to B; B weaves its own single-link mesh to A. Run concurrently
                // so the MeshHello preambles cross in parallel (serial would deadlock).
                val hubDeferred = async { meshSeam(a, listOf(aToB), Dispatchers.IO) }
                val bMeshDeferred = async { meshSeam(b, listOf(bToA), Dispatchers.IO) }
                val hub = hubDeferred.await()
                val bMesh = bMeshDeferred.await()

                assertEquals(setOf(a, b), hub.peers.value, "hub sees A + B before the late join")

                // Late joiner C dials in: A admits it via addLink while C weaves its own mesh to A.
                val joinDeferred = async { hub.addLink(aToC) }
                val cMeshDeferred = async { meshSeam(c, listOf(cToA), Dispatchers.IO) }
                joinDeferred.await()
                val cMesh = cMeshDeferred.await()

                assertEquals(setOf(a, b, c), hub.peers.value, "late joiner C joined the cluster")

                // The hub broadcasts; both the original member and the late joiner receive it,
                // attributed to A.
                val onB = async { bMesh.incoming.first() }
                val onC = async { cMesh.incoming.first() }
                val payload = "hello cluster".encodeToByteArray()
                hub.broadcast(payload)

                val bFrame = onB.await()
                val cFrame = onC.await()
                assertContentEquals(payload, bFrame.payload, "B receives the cluster broadcast")
                assertContentEquals(payload, cFrame.payload, "C receives the cluster broadcast")
                assertEquals(a, bFrame.sender, "B attributes the frame to A")
                assertEquals(a, cFrame.sender, "C attributes the frame to A")

                hub.close()
                bMesh.close()
                cMesh.close()
            }
        }
    }

    /**
     * Open one real loopback TCP connection and return both ends as [Connection]s (each `framed()` by
     * `:kuilt-stream` via [tcpConnection]). The accepting end is `.first`, the dialing end `.second`.
     */
    private suspend fun tcpConnectionPair(): Pair<Connection, Connection> = coroutineScope {
        val port = JvmServerSocket(0).use { it.localPort }
        val server = aSocket(selector).tcp().bind("127.0.0.1", port)
        val acceptedDeferred = async { server.accept() }
        val client: Socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        val accepted = acceptedDeferred.await()
        server.close()
        tcpConnection(accepted, Dispatchers.IO) to tcpConnection(client, Dispatchers.IO)
    }
}
