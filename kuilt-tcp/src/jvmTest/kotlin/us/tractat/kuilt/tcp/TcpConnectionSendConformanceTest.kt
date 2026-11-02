package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback conformance harness — a TCP socket needs a real IO dispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.CancelledCallerFixture
import us.tractat.kuilt.conformance.ConnectionSendConformanceSuite
import us.tractat.kuilt.core.fabric.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * [ConnectionSendConformanceSuite] over a real loopback socket: one binding per way the link can die.
 *
 * Teardown closes the sockets rather than the [Connection]s, because `Connection.close` flushes
 * before it closes, and a flush into a socket nobody reads can block. The links here are dead, so it
 * should not, but a test's cleanup is the wrong place to find out.
 */
abstract class TcpConnectionSendConformanceRig : ConnectionSendConformanceSuite() {

    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    protected lateinit var dialedSocket: Socket
    protected lateinit var acceptedSocket: Socket
    protected lateinit var dialed: Connection
    protected lateinit var accepted: Connection

    @BeforeTest
    fun connect() = runBlocking {
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (serverSocket.localAddress as InetSocketAddress).port
        coroutineScope {
            val incoming = async { serverSocket.accept() }
            dialedSocket = aSocket(selector).tcp().connect("127.0.0.1", port)
            acceptedSocket = incoming.await()
        }
        dialed = tcpConnection(dialedSocket, Dispatchers.IO)
        accepted = tcpConnection(acceptedSocket, Dispatchers.IO)
    }

    @AfterTest
    fun disconnect() {
        dialedSocket.close()
        acceptedSocket.close()
        serverSocket.close()
        selector.close()
    }
}

private const val BLOCKING_SEND =
    "framed() writes to a blocking Sink, so a live link has no suspension point and the transport's " +
        "own failure reaches a cancelled caller as it stands"

/** This end closed the link. The local `Sink` refuses the frame before any socket is involved. */
class TcpLocalCloseSendConformanceTest : TcpConnectionSendConformanceRig() {
    override fun cancelledCaller(): CancelledCallerFixture = CancelledCallerFixture.NeverChecks(BLOCKING_SEND)

    override suspend fun deadLink(): Connection {
        dialed.close()
        return dialed
    }
}

/**
 * The far end closed the link. The kernel takes a first frame and the reset fails a later one, as a
 * `ClosedWriteChannelException` carrying "Broken pipe".
 */
class TcpRemoteCloseSendConformanceTest : TcpConnectionSendConformanceRig() {
    override fun cancelledCaller(): CancelledCallerFixture = CancelledCallerFixture.NeverChecks(BLOCKING_SEND)

    override suspend fun deadLink(): Connection {
        accepted.close()
        return dialed
    }
}

/**
 * The socket's own job was cancelled. Ktor's write channel then carries that cancellation, and its
 * `CloseToken` keeps a `CancellationException` as one when it rethrows it, so `framed()`'s write
 * throws a cancellation at a caller nobody cancelled.
 *
 * Nothing in `:kuilt-tcp` holds the socket after handing it to `tcpConnection`, so this binding
 * reaches in to do it. It is here for `framed()`, which accepts any `Sink` and owes its caller the
 * same answer whichever one it was given.
 */
class TcpCancelledSocketSendConformanceTest : TcpConnectionSendConformanceRig() {
    override fun cancelledCaller(): CancelledCallerFixture = CancelledCallerFixture.Observes

    override suspend fun deadLink(): Connection {
        dialedSocket.cancel()
        return dialed
    }
}
