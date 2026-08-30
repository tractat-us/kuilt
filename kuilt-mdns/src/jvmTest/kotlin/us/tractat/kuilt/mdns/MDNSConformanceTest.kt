package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertFailsWith
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

    /**
     * Binds port 0 and reads the port back off the *live* connector, then mounts the host factory
     * on the already-started [io.ktor.server.application.Application].
     *
     * Probing a free port with a throwaway `ServerSocket(0).use { it.localPort }` and re-binding the
     * number is a TOCTOU: the probe socket is closed before Netty binds, so on a loaded box another
     * process can take the port in that window and `@BeforeTest` dies with `BindException: Address
     * already in use` on a PR that cannot have caused it (#1590, #1749). Binding 0 has no window.
     *
     * The port is an **input** to the factory (it goes in the advertised record), so the factory
     * cannot be built inside the `embeddedServer` module lambda — that lambda runs during `start()`,
     * strictly before `resolvedConnectors()` can answer. [io.ktor.server.engine.EmbeddedServer.application]
     * is the same `Application` the lambda receives, and mounting a route on it after `start()`
     * works: [newLoomPair] has always built its joiner factory that way.
     */
    @BeforeTest
    fun setUp() {
        server = embeddedServer(Netty, port = 0) { /* routes mounted post-start, see KDoc */ }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        hostFactory = MDNSPeerLinkFactory(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            wsPath = hostWsPath,
            httpClientFactory = ::freshJoinerClient,
        )
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
        runBlocking { openSeams.forEach { runCatchingCancellable { it.close(CloseReason.Normal) } } }
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
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
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
            sessionName = "host",
            wsPath = hostWsPath,
        )

    /**
     * Prove the mDNS self-dial guard (#1489 / the #1466 class one transport up). JmDNS / NWBrowser
     * deliver a device its OWN advertisement, so a symmetric advertise+browse peer can be handed
     * its own record and dial itself.
     *
     * Unlike a multi-peer mesh fabric (e.g. `NwSeam`), mDNS's self-dial defense is **not** a
     * seam-level drop: the transport is a per-connection **2-peer WebSocket relay**, so a
     * self-connection can never reach a live host seam to be dropped there. The defense is a
     * fail-fast `require(serverPeerId != selfPeerId)` at [MDNSPeerLinkFactory.weave] (the choke
     * point where both ids are in scope; also exercised directly by [MDNSSelfDiscoveryFilterTest]).
     *
     * We prove it by driving [hostFactory] to dial its OWN advertisement and asserting the refusal.
     * Because the guard throws before any connection forms, the live host seam is untouched — so the
     * suite's no-self-echo / peers-unchanged / stays-Woven assertions in [selfDialIsRejected] hold as
     * required.
     */
    override suspend fun injectSelfDial(host: Seam): Boolean {
        val selfAdvertisement = MDNSAdvertisement(
            host = "localhost",
            port = port,
            serverPeerId = hostFactory.selfPeerId,
            sessionName = "host",
            wsPath = hostWsPath,
        )
        assertFailsWith<IllegalArgumentException> {
            hostFactory.weave(Rendezvous.Existing(selfAdvertisement))
        }
        return true
    }

    /** Proven: the factory refuses to dial its own advertisement, so no gap. */
    override fun selfDialGap(): String? = null

    // identical byte path to websocket — plaintext ws:// to a host-peer hub;
    // joiner↔joiner frames traverse the host; no path observer either (#1712).
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        meshDelivery = false,
        reportsLiveCapability = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "meshDelivery" to CapabilityGaps.MESH_DELIVERY,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
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
