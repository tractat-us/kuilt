@file:Suppress("ForbiddenImport") // real-network loopback conformance harness — a TCP socket needs a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import java.net.ServerSocket as JvmServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Verifies that the TCP fabric ([TcpLoom]) satisfies every invariant in
 * [SeamConformanceSuite] over a **real localhost socket** — a real Ktor TCP
 * server socket bound on an ephemeral port, a real Ktor TCP client socket, real
 * bytes framed by `:kuilt-stream`'s `framed()`.
 *
 * This is a real-IO test, not a virtual-time test (sockets cannot be driven by a
 * test scheduler), mirroring [us.tractat.kuilt.websocket]'s conformance harness.
 *
 * [newLoomPair] returns distinct host/joiner [TcpLoom]s: the host accepts one
 * connection on the pre-bound [serverSocket]; the joiner connects to [joinTag]'s
 * address. The suite drives `host()`/`join()` concurrently, so the host loom's
 * accept-then-handshake satisfies the suspend-until-joiner contract naturally.
 */
class TcpConformanceTest : SeamConformanceSuite() {

    @Suppress("ForbiddenMethodCall") // real-network loopback conformance harness — sockets need a real IO dispatcher
    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        port = JvmServerSocket(0).use { it.localPort }
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", port)
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val hostLoom = TcpLoom.host(serverSocket, PeerId("tcp-host"), selector)
        val joinerLoom = TcpLoom.join(PeerId("tcp-joiner"), selector)
        return hostLoom to joinerLoom
    }

    override fun joinTag(): Tag = TcpAddress(host = "127.0.0.1", port = port)
}
