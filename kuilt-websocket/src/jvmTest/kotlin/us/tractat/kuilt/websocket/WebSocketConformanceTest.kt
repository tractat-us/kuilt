package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
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
        // Bind 0 and read the port back from the *live* connector. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before Netty binds, so another process on a loaded box can take the port
        // in that window (`BindException: Address already in use` — #1590). Binding 0 has no window.
        server = embeddedServer(Netty, port = 0) {
            // BOTH looms take the observer, not just the client one. The suite's
            // `wovenSeamCapabilityIsHonest` reads the **host** seam — which is this server loom's —
            // so wiring only the client leaves the awaited transition unreachable and the test
            // hangs to the wall-clock backstop instead of failing (measured: 60 s, no assertion).
            serverLoom = KtorServerLoom(this, serverPath, connectivity = connectivity)
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
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

    /**
     * A reachable device, published on the fake observer both looms are built with.
     *
     * **LOAD-BEARING — do not delete (#1725).** `reportsLiveCapability = true` selects the AWAITING
     * branch of [SeamConformanceSuite.wovenSeamCapabilityIsHonest], which blocks on
     * `capability.first { it.availability !is Unknown }` with no timeout, and since #1712 an
     * observer is the only thing that can satisfy it — the static loom report supplies roles only.
     * [FakeConnectivityObserver] starts at `null`, so an unseeded seam publishes `Unknown` forever:
     * the await would never complete and the test would die on `runTest`'s wall-clock backstop
     * rather than fail. `WebSocketSeamCapabilityTest.unobservedByDefaultReportsTheUnknownFloor`
     * pins that unseeded-floor behaviour directly, so the claim this comment rests on is asserted
     * somewhere rather than only asserted about.
     */
    private val connectivity = FakeConnectivityObserver(NetworkReachability.Reachable)

    override fun newLoomPair(): Pair<Loom, Loom> =
        serverLoom to KtorClientLoom(httpClient, connectivity = connectivity)

    override fun joinTag(): Tag = WebSocketAdvertisement(
        url = "ws://localhost:$port$serverPath",
        serverPeerId = serverLoom.selfPeerId,
        sessionName = "conformance-client",
    )

    /**
     * Inject a mid-session transport death by abruptly stopping the Netty server under the live
     * session — the underlying WebSocket connection drops for both the server seam (host) and the
     * client seam (joiner), without either calling `close()`. Per-test server (fresh in [setUp]),
     * so this only affects this test; [tearDown]'s stop is idempotent.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        return true
    }

    /** Proven: this harness drops the transport by stopping the server, so no gap. */
    override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * Plaintext `ws://`; relay/hub topology — frames traverse the server.
     *
     * `reportsLiveCapability = true` (#1725): [WebSocketSeam] drives its
     * [Seam.capability] from the [ConnectivityObserver] its loom was built with, whose real
     * bindings are `androidConnectivityObserver` (a `ConnectivityManager.NetworkCallback`) and
     * `browserConnectivityObserver` (`navigator.onLine`). Per the rule stated on
     * `NwBridgeLoopbackConformanceTest`, this flag tracks the **binding under test** rather than the
     * abstract fabric — and the binding under test here is wired to [connectivity], so it is off the
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor.
     *
     * Two things that flag does *not* claim, both worth stating because the harness cannot:
     *  - **The desktop JVM has no observer**, by design — there is no portable OS reachability
     *    source there and synthesising one from socket state would report the *relay's* health as
     *    the *device's*. A plain `KtorClientLoom(httpClient)` on the JVM still reports the floor;
     *    `WebSocketSeamCapabilityTest.unobservedByDefaultReportsTheUnknownFloor` holds it there.
     *  - **That a platform emits readings is not proven by any of this.** A fake-injected signal
     *    demonstrates the seam's reaction, never the transport's emission; the Android and browser
     *    halves are device-only. Apple has no binding yet at all — #2413.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        meshDelivery = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "meshDelivery" to CapabilityGaps.MESH_DELIVERY,
    )

    /** #2591: this fixture fills the joiner's roster itself, so the joiner arm cannot fail here. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a seeded roster: joinTag() reads serverLoom.selfPeerId off the shared server loom field, and the " +
            "joiner's WebSocketSeam is LinkSeam-backed, so it opens at setOf(selfId, remoteId). The socket " +
            "does have to connect for weave() to return, so the arm is not unfalsifiable - it is implied by " +
            "a seam existing at all.",
        )
}
