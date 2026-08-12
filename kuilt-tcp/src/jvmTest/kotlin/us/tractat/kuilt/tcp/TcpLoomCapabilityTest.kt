@file:Suppress("ForbiddenImport") // real-network loopback socket needs a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback socket needs a real IO dispatcher
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportRole
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TcpLoom] is a pure data-carrying fabric: it declares only [TransportRole.Data].
 * Construction binds a real loopback [ServerSocket] (no IO happens until [weave]).
 */
class TcpLoomCapabilityTest {

    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket

    @BeforeTest
    fun setUp() = runBlocking {
        // Bind 0 rather than probing a free port and re-binding the number — the probe closes
        // before the real bind, so another process can take the port in that window (#1590).
        // This test never needs the number, so nothing reads it back.
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    @Test
    fun declaresDataRole() {
        val loom = TcpLoom.host(serverSocket, PeerId("host"), selector)
        assertEquals(setOf(TransportRole.Data), loom.capability().roles)
    }
}
