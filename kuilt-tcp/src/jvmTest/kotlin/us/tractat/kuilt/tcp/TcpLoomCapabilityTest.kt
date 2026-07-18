@file:Suppress("ForbiddenImport") // real-network loopback socket needs a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportRole
import java.net.ServerSocket as JvmServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TcpLoom] is a pure data-carrying fabric: it declares only [TransportRole.Data].
 * Construction binds a real loopback [ServerSocket] (no IO happens until [weave]).
 */
class TcpLoomCapabilityTest {

    @Suppress("ForbiddenMethodCall") // real-network loopback socket needs a real IO dispatcher
    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket

    @BeforeTest
    fun setUp() = runBlocking {
        val port = JvmServerSocket(0).use { it.localPort }
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", port)
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
