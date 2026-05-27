package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
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
 * [newLoomPair] returns [ReplayLoom] wrappers around a [Seam] pair that is
 * pre-connected in [@BeforeTest][BeforeTest] via a real Netty server and a real
 * OkHttp client. This lets the suite's synchronous [newLoomPair] binding compose
 * with [KtorServerLoom.nextLink]'s suspend-until-client-connects semantics without
 * any fake or mock transport. The connection is genuine; the wrappers just cache it.
 *
 * [@AfterTest][AfterTest] closes the seams with [CloseReason.Normal] before stopping
 * the server, so the client's receive loop sees a graceful close rather than an
 * abrupt EOF — preventing uncaught coroutine exceptions from leaking across tests.
 */
class WebSocketConformanceTest : SeamConformanceSuite() {

    private val serverPath = "/ws/conformance"

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var serverLoom: KtorServerLoom
    private lateinit var httpClient: HttpClient
    private lateinit var serverSeam: Seam
    private lateinit var clientSeam: Seam
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            serverLoom = KtorServerLoom(this, serverPath)
        }
        server.start(wait = false)
        httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }
        preConnect()
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            if (this@WebSocketConformanceTest::serverSeam.isInitialized) {
                serverSeam.close(CloseReason.Normal)
            }
        }
        if (this::httpClient.isInitialized) httpClient.close()
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    // ── SeamConformanceSuite binding ─────────────────────────────────────────
    //
    // KtorServerLoom.host() (nextLink()) suspends until a client connects. Tests that
    // assert on the host side only (hostYieldsUsableSeamWithNonEmptySelfId,
    // closeIsIdempotent) call host() without a corresponding join() — they would
    // deadlock on a raw KtorServerLoom. The pre-connected ReplayLoom approach
    // eliminates the deadlock: weave() returns the cached Seam immediately.

    override fun newLoomPair(): Pair<Loom, Loom> =
        ReplayLoom(serverSeam) to ReplayLoom(clientSeam)

    override fun joinTag(): Tag = WebSocketAdvertisement(
        url = "ws://localhost:$port$serverPath",
        serverPeerId = serverLoom.selfPeerId,
        displayName = "conformance-client",
    )

    // ── Connection setup ─────────────────────────────────────────────────────

    private fun preConnect() {
        runBlocking {
            coroutineScope {
                val serverDeferred = async { serverLoom.nextLink() }
                clientSeam = KtorClientLoom(httpClient).join(joinTag())
                serverSeam = serverDeferred.await()
            }
        }
    }

    // ── ReplayLoom ───────────────────────────────────────────────────────────
    //
    // Replays a single pre-connected Seam. weave() returns the stored Seam regardless
    // of the Rendezvous variant — the real connection was established in preConnect().

    private class ReplayLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun availability(): FabricAvailability = FabricAvailability.Available
    }
}
