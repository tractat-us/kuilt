package us.tractat.kuilt.mdns

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
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Verifies that [MDNSPeerLinkFactory] satisfies every invariant in [SeamConformanceSuite]
 * over the **real localhost byte path**, skipping JmDNS multicast discovery.
 *
 * The host factory opens a real Netty WebSocket server; the joiner factory connects
 * via a directly-constructed [MDNSAdvertisement] at `localhost:<port>` — no mDNS
 * discovery, no multicast. JmDNS calls are absorbed by [CapturingJmDNS], which is
 * already present in this test source set.
 *
 * Real-multicast tests stay `-P`-gated in [MDNSMulticastIntegrationTest]:
 * ```
 * ./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true
 * ```
 *
 * ## Concurrent host/join
 *
 * `KtorServerLoom.host()` suspends until a client connects. The hardened
 * [SeamConformanceSuite] already drives every test with a concurrent
 * `async { host() }` / `async { join() }` pair, so no overrides are needed here.
 *
 * ## Teardown ordering — seams closed before clients
 *
 * Closing the [HttpClient] abruptly (without a prior [Seam.close]) leaves the server's
 * receive loop seeing an EOF, which surfaces as an uncaught exception in the next test's
 * [kotlinx.coroutines.test.runTest]. To avoid this, [newLoomPair] wraps each [Loom]
 * in a [TrackingLoom] that records every [Seam] it produces. [tearDown] closes those
 * seams (graceful WS close frame) before closing the clients and stopping the server.
 *
 * This makes the teardown self-contained and independent of the [WebSocketSeam]
 * abrupt-close fix that lives in PR #17.
 *
 * See ADR-001 §"mdns multicast bypass (verified, recommended)" for the rationale.
 */
class MDNSConformanceTest : SeamConformanceSuite() {

    private val hostWsPath = "/ws/mdns-conf-host"
    private val joinerWsPath = "/ws/mdns-conf-joiner"

    private var port: Int = 0
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var hostFactory: MDNSPeerLinkFactory
    private val joinerClients = mutableListOf<HttpClient>()
    private val openSeams = mutableListOf<Seam>()

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            hostFactory = MDNSPeerLinkFactory(
                application = this,
                jmdns = CapturingJmDNS(),
                port = port,
                wsPath = hostWsPath,
                httpClientFactory = ::freshJoinerClient,
            )
        }
        server.start(wait = false)
    }

    /**
     * Teardown order:
     * 1. Close every tracked [Seam] with [CloseReason.Normal] — sends a graceful WS close
     *    frame so the server's receive loop exits cleanly rather than seeing EOF.
     * 2. Close joiner [HttpClient]s — underlying TCP sockets released.
     * 3. Stop the Netty server.
     */
    @AfterTest
    fun tearDown() {
        runBlocking { openSeams.forEach { runCatching { it.close(CloseReason.Normal) } } }
        openSeams.clear()
        joinerClients.forEach { it.close() }
        joinerClients.clear()
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    /**
     * Returns a host/joiner pair backed by the real Netty WebSocket transport.
     *
     * Both looms are wrapped in [TrackingLoom] so [tearDown] can close every [Seam]
     * produced by the test before the clients and server shut down.
     *
     * The host [MDNSPeerLinkFactory] was wired to [server]'s [Application] in [setUp].
     * The joiner is a fresh factory that mounts an unused route at [joinerWsPath]
     * (no client ever connects to it) and only uses its [Loom.join] path, delegating
     * directly to [KtorClientLoom] via its [HttpClient].
     */
    override fun newLoomPair(): Pair<Loom, Loom> {
        val joinerClient = freshJoinerClient()
        val joinerFactory = MDNSPeerLinkFactory(
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            wsPath = joinerWsPath,
            httpClientFactory = { joinerClient },
        )
        return TrackingLoom(hostFactory) to TrackingLoom(joinerFactory)
    }

    /**
     * The [MDNSAdvertisement] the joiner uses.
     *
     * Constructed directly with the host's [MDNSPeerLinkFactory.selfPeerId] and
     * [port] — no mDNS discovery involved. The joiner's [Loom.weave] call converts
     * this to a [us.tractat.kuilt.websocket.WebSocketAdvertisement] and connects
     * via [HttpClient] over the real Netty socket.
     */
    override fun joinTag(): Tag =
        MDNSAdvertisement(
            host = "localhost",
            port = port,
            serverPeerId = hostFactory.selfPeerId,
            displayName = "host",
            wsPath = hostWsPath,
        )

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun freshJoinerClient(): HttpClient =
        HttpClient(OkHttp) { install(ClientWebSockets) }
            .also { joinerClients += it }

    /**
     * Delegates every [weave] call to [delegate] and records the returned [Seam]
     * in [openSeams] so [tearDown] can close it gracefully.
     */
    private inner class TrackingLoom(private val delegate: Loom) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            delegate.weave(rendezvous)
                .also { openSeams += it }
    }
}
