package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: a real Ktor session needs real time and a thread that outlives the test's own job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.conformance.CancelledCallerFixture
import us.tractat.kuilt.conformance.ConnectionSendConformanceSuite
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * [ConnectionSendConformanceSuite] over a real Netty server and a real Ktor client: one binding per
 * side of the session and per way it can die.
 *
 * Deterministic, not sampled. Each binding polls `outgoing.isClosedForSend`, which sends nothing,
 * so [deadLink] returns only once the session's own queue is dead. A session's queue dies either in
 * `runOutgoingProcessor`'s `finally`, which cancels it (Netty, CIO), or in the OkHttp listener's
 * `onClosed`, which closes it with a `CancellationException`. Both make `session.send` throw a
 * cancellation at a caller nobody cancelled.
 *
 * The session is opened in [rigScope], not by the caller: Ktor parents a client session's coroutines
 * to the job that opened it, and the caller here is `runTest`'s, which would then wait for them.
 */
abstract class WebSocketConnectionSendConformanceRig(
    private val engine: HttpClientEngineFactory<*>,
) : ConnectionSendConformanceSuite() {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serverSessions = Channel<DefaultWebSocketServerSession>(Channel.UNLIMITED)
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var httpClient: HttpClient

    // Started here rather than in `runBlocking`, whose receiver would bind `embeddedServer` to the
    // `CoroutineScope` overload and parent the server to the blocking job.
    @BeforeTest
    fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket(PATH) {
                    serverSessions.send(this)
                    closeReason.await()
                }
            }
        }
        server.start(wait = false)
        httpClient = HttpClient(engine) { install(ClientWebSockets) }
    }

    @AfterTest
    fun stopServer() {
        httpClient.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        rigScope.cancel()
    }

    /** Kill the session in whichever way this binding is about, and return the end under test. */
    protected abstract suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession

    /** Every binding here is a channel send, which has a cancellation check on its closed path. */
    override fun cancelledCaller(): CancelledCallerFixture = CancelledCallerFixture.Observes

    override suspend fun deadLink(): Connection =
        rigScope.async {
            val port = server.engine.resolvedConnectors().first().port
            val clientEnd = httpClient.webSocketSession("ws://127.0.0.1:$port$PATH")
            val serverEnd = withTimeout(SESSION_BOUND) { serverSessions.receive() }
            val dead = killOneEnd(serverEnd, clientEnd)
            withTimeout(SESSION_BOUND) {
                while (!dead.outgoing.isClosedForSend) delay(POLL)
            }
            WebSocketConnection(dead)
        }.await()

    /**
     * A dead session cannot confirm anything. Ktor's own `flush` is no help here: on a session that has
     * closed it returns normally, having written nothing, and on OkHttp it does nothing at all.
     */
    @Test
    fun flushOnADeadSessionReportsFailed(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            assertIs<FlushOutcome.Failed>(deadLink().flush(Duration.INFINITE))
        }

    private companion object {
        const val PATH = "/send-conformance"
        val SESSION_BOUND = 15.seconds
        val POLL = 5.milliseconds
    }
}

/** The hub's end after its client sent a close frame: the path a closed spoke takes on a real hub. */
class ServerEndAfterClientCloseFrameSendConformanceTest : WebSocketConnectionSendConformanceRig(CIO) {
    override suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession {
        clientEnd.close()
        return serverEnd
    }
}

/** The hub's end after its client's session was dropped without a close frame. */
class ServerEndAfterClientAbortSendConformanceTest : WebSocketConnectionSendConformanceRig(CIO) {
    override suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession {
        clientEnd.cancel()
        return serverEnd
    }
}

/** The hub's end after the hub itself closed it. */
class ServerEndAfterLocalCloseSendConformanceTest : WebSocketConnectionSendConformanceRig(CIO) {
    override suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession {
        serverEnd.close()
        return serverEnd
    }
}

/** A CIO client's end after the server closed it. */
class CioClientEndAfterServerCloseSendConformanceTest : WebSocketConnectionSendConformanceRig(CIO) {
    override suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession {
        serverEnd.close()
        return clientEnd
    }
}

/**
 * An OkHttp client's end after the server closed it. A different mechanism from CIO's, and the reason
 * this binding exists: the OkHttp engine has its own session class, whose `onClosed` closes the
 * outgoing channel with a `CancellationException` rather than cancelling it.
 */
class OkHttpClientEndAfterServerCloseSendConformanceTest : WebSocketConnectionSendConformanceRig(OkHttp) {
    override suspend fun killOneEnd(
        serverEnd: DefaultWebSocketServerSession,
        clientEnd: DefaultClientWebSocketSession,
    ): DefaultWebSocketSession {
        serverEnd.close()
        return clientEnd
    }
}
