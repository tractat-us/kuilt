package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Verifies that the WebSocket fabric ([KtorServerLoom] / [KtorClientLoom]) satisfies
 * every invariant in [SeamConformanceSuite] over a **real localhost connection** —
 * a real Netty server, a real OkHttp client, real sockets and frames (ADR-001).
 *
 * [newLoomPair] returns `(KtorServerLoom, KtorClientLoom)` — the actual server and
 * client looms. The suite drives `host()`/`join()` concurrently for all tests, so
 * `KtorServerLoom.host()`'s suspend-until-client semantics are satisfied naturally.
 *
 * [@BeforeTest][BeforeTest] starts a real Netty server on a random free port.
 * [joinTag] returns a [WebSocketAdvertisement] pointing at it, which the suite
 * supplies to `KtorClientLoom.join()`.
 *
 * [@AfterTest][AfterTest] closes any open seams with [CloseReason.Normal] before
 * stopping the server, so the receive loop sees a graceful close rather than an
 * abrupt EOF — preventing uncaught coroutine exceptions from leaking across tests.
 */
class WebSocketConformanceTest : SeamConformanceSuite() {

    private val serverPath = "/ws/conformance"

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var serverLoom: KtorServerLoom
    private lateinit var httpClient: HttpClient
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            serverLoom = KtorServerLoom(this, serverPath)
        }
        server.start(wait = false)
        httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            // Nothing to pre-close here — the suite's coroutineScope unwinds seams
            // when each test completes (test 5 explicitly closes; others let scope cancel).
        }
        if (this::httpClient.isInitialized) httpClient.close()
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    // ── SeamConformanceSuite binding ─────────────────────────────────────────
    //
    // The suite drives host()/join() concurrently for every test (including tests 1
    // and 5 which previously deadlocked on a raw KtorServerLoom). Returning real looms
    // here means the connection is established by the suite, not pre-wired by us.

    override fun newLoomPair(): Pair<Loom, Loom> =
        serverLoom to KtorClientLoom(httpClient)

    override fun joinTag(): Tag = WebSocketAdvertisement(
        url = "ws://localhost:$port$serverPath",
        serverPeerId = serverLoom.selfPeerId,
        displayName = "conformance-client",
    )
}
