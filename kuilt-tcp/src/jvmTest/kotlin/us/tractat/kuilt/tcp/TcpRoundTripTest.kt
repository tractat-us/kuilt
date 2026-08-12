@file:Suppress("ForbiddenImport") // real-network loopback test — a TCP socket needs a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.stream.DEFAULT_MAX_FRAME_SIZE
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end round trip over a real loopback TCP socket: a host and a joiner weave
 * a [us.tractat.kuilt.core.Seam] via [TcpLoom], then exchange frames in both
 * directions. Complements [TcpConformanceTest] with explicit bidirectional asserts.
 */
class TcpRoundTripTest {

    @Suppress("ForbiddenMethodCall") // real-network loopback test — a TCP socket needs a real IO dispatcher
    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        // Bind 0 and read the port back off the socket we actually hold — probing a free port and
        // re-binding the number is a TOCTOU: the probe closes before the real bind, so another
        // process can take the port in that window (#1590). Binding 0 has no window.
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        port = (serverSocket.localAddress as InetSocketAddress).port
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    // runBlocking, not runTest: this drives real sockets, so it must run on real time.
    // A withTimeout guards against a hang without coupling to a virtual clock.
    @Test
    fun framesTravelInBothDirections() = runBlocking {
        val hostLoom = TcpLoom.host(serverSocket, PeerId("host"), selector)
        val joinerLoom = TcpLoom.join(PeerId("joiner"), selector)

        withTimeout(10.seconds) {
            coroutineScope {
                val hostDeferred = async { hostLoom.host(us.tractat.kuilt.core.Pattern("host")) }
                val joiner = joinerLoom.join(TcpAddress("127.0.0.1", port))
                val host = hostDeferred.await()

                val joinerReceives = async { joiner.incoming.first() }
                val hostReceives = async { host.incoming.first() }

                host.broadcast("from-host".encodeToByteArray())
                joiner.broadcast("from-joiner".encodeToByteArray())

                assertEquals("from-host", joinerReceives.await().decodeToString())
                assertEquals("from-joiner", hostReceives.await().decodeToString())

                host.close(us.tractat.kuilt.core.CloseReason.Normal)
                joiner.close(us.tractat.kuilt.core.CloseReason.Normal)
            }
        }
    }

    /**
     * A real fabric populates [us.tractat.kuilt.core.Seam.maxPayloadBytes] (#2047).
     *
     * The whole budget mechanism is inert unless some fabric names a number, and this is the end of
     * the chain that carries it: `framed()`'s `maxFrameSize` → `Connection.maxFrameBytes` → the
     * seam `handshaking` returns. A build in which every layer kept the `null` default would still
     * pass every unit test of the arithmetic above it, and fail here.
     */
    @Test
    fun aWovenSeamPublishesTheFramingCeiling() = runBlocking {
        val hostLoom = TcpLoom.host(serverSocket, PeerId("host"), selector)
        val joinerLoom = TcpLoom.join(PeerId("joiner"), selector)

        withTimeout(10.seconds) {
            coroutineScope {
                val hostDeferred = async { hostLoom.host(us.tractat.kuilt.core.Pattern("host")) }
                val joiner = joinerLoom.join(TcpAddress("127.0.0.1", port))
                val host = hostDeferred.await()

                assertEquals(DEFAULT_MAX_FRAME_SIZE, host.maxPayloadBytes, "host seam")
                assertEquals(DEFAULT_MAX_FRAME_SIZE, joiner.maxPayloadBytes, "joiner seam")

                host.close(us.tractat.kuilt.core.CloseReason.Normal)
                joiner.close(us.tractat.kuilt.core.CloseReason.Normal)
            }
        }
    }
}
