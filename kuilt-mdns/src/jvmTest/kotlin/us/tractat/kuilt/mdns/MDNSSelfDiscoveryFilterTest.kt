package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Self-discovery guard (#1489). JmDNS / NWBrowser deliver a device's **own**
 * advertisement to its own listener, so a symmetric advertise+browse peer can
 * dial itself. The two mDNS host entry points that know both the local
 * [PeerId] and the discovered one — [MDNSPeerLinkFactory] (dial side) and
 * [MDNSMultiAcceptHost] (accept side) — must never surface or connect the local
 * peer to itself. This is the #1466 recipe one transport up (see the merged
 * NwSeam self-connection fix).
 *
 * Driven over the **real localhost byte path** (real Netty server + real OkHttp
 * client) with JmDNS absorbed by [CapturingJmDNS] (no multicast, so **not**
 * `-P`-gated). Teardown mirrors [MDNSConformanceTest]: accepted seams closed
 * ([CloseReason.Normal]) first, then the [HttpClient]s, then the servers.
 */
class MDNSSelfDiscoveryFilterTest {

    private val servers = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
    private val clients = mutableListOf<HttpClient>()
    private val openSeams = mutableListOf<Seam>()

    @AfterTest
    fun tearDown() {
        runBlocking { openSeams.forEach { runCatchingCancellable { it.close(CloseReason.Normal) } } }
        openSeams.clear()
        clients.forEach { it.close() }
        clients.clear()
        servers.forEach { it.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
        servers.clear()
    }

    @Test
    fun `PeerLinkFactory refuses to dial its own advertisement`() {
        val wsPath = "/ws/self-dial"
        // embeddedServer is called OUTSIDE any runBlocking receiver on purpose. Inside a
        // `runBlocking { … }` the `CoroutineScope.embeddedServer` extension resolves and parents the
        // Netty application's SupervisorJob to the runBlocking Job — which never completes until the
        // server is stopped (tearDown), and tearDown can't run until the test's runBlocking returns.
        // That is a structural deadlock: runBlocking waits forever on the server child (killed on CI
        // as a 15-minute task timeout). Called here, with no CoroutineScope receiver in scope, it binds
        // to the top-level (GlobalScope-parented) overload, so the server is a root job we own and
        // stop in tearDown — nothing leaks into the assertion's runBlocking below. (The port read
        // below has its own runBlocking, which completes: it awaits a resolved connector, not the
        // server's job.)
        val server = embeddedServer(Netty, port = 0) { /* route mounted post-start, see below */ }
            .also { servers += it }
        server.start(wait = false)
        // Bind 0 and read the port back off the *live* connector; probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU —
        // the probe closes before Netty binds, so another process can take the port in that window
        // (#1590, #1749). The port is an input to the factory (it goes in the advertisement), so the
        // factory is built after start(), the module lambda having run strictly before
        // resolvedConnectors() could answer.
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val factory = MDNSPeerLinkFactory(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            wsPath = wsPath,
            httpClientFactory = { HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it } },
        )

        // The device's own advertisement, as its browser would deliver it back to itself:
        // serverPeerId == this factory's selfPeerId, pointing at the factory's own live server.
        val ownAdvertisement = MDNSAdvertisement(
            host = "localhost",
            port = port,
            serverPeerId = factory.selfPeerId,
            sessionName = "self",
            wsPath = wsPath,
        )

        // The self-guard is a synchronous `require`, so this returns immediately. The tight timeout
        // is the self-diagnosing backstop: if the guard ever regresses and weave attempts a real
        // self-dial, this fails as a 5-second assertion instead of hanging for the task timeout.
        // Pre-fix (guard absent) this happily connected to its own server and returned a self-Seam.
        runBlocking {
            withTimeout(5.seconds) {
                assertFailsWith<IllegalArgumentException> {
                    factory.weave(Rendezvous.Existing(ownAdvertisement)).also { openSeams += it }
                }
            }
        }
    }

    @Test
    fun `MultiAcceptHost never surfaces a joiner whose peerId is the host itself`() {
        val wsPath = "/ws/self-accept"
        // Outside runBlocking on purpose — see the note on the sibling test: inside a runBlocking
        // receiver the `CoroutineScope.embeddedServer` extension parents the Netty application job to
        // the enclosing runBlocking, which then deadlocks on its own server child. Here it binds to
        // the top-level (GlobalScope-parented) overload; the server is a root job we stop in tearDown.
        // Port handling matches the sibling test: bind 0, read the port back off the live connector,
        // mount the host on the started Application (#1590, #1749).
        val server = embeddedServer(Netty, port = 0) { /* route mounted post-start, see below */ }
            .also { servers += it }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val host = MDNSMultiAcceptHost(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            pattern = us.tractat.kuilt.core.Pattern("self-accept-host"),
            wsPath = wsPath,
        )

        fun advertisement() = WebSocketAdvertisement(
            url = "ws://localhost:$port$wsPath",
            serverPeerId = host.selfPeerId,
            sessionName = "joiner",
        )

        // Every real-socket await lives inside one bounded window, sized for cold, contended CI, so a
        // mis-fire fails as an assertion rather than hanging to the 15-minute task timeout.
        runBlocking {
            withTimeout(30.seconds) {
                // The self-dial: a client presenting the HOST's own peerId (what a symmetric
                // advertise+browse device does when it dials its own advertisement). Connect it FIRST
                // so it is the first seam buffered on the accept side.
                val selfClient = HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it }
                openSeams += KtorClientLoom(selfClient, selfPeerId = host.selfPeerId).join(advertisement())

                // A genuine remote joiner with a distinct peerId.
                val legitId = PeerId("legit-joiner")
                val legitClient = HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it }
                openSeams += KtorClientLoom(legitClient, selfPeerId = legitId).join(advertisement())

                // Even though the self-connection was buffered first, the host must skip it and surface
                // only the genuine joiner. Pre-fix nextSeam() returned the self seam (remote == self,
                // peers collapsed to {selfPeerId}).
                val accepted = host.nextSeam().also { openSeams += it }

                assertEquals(
                    setOf(legitId),
                    accepted.peers.value - accepted.selfId,
                    "the first surfaced seam must be the genuine joiner, not the self-connection",
                )
            }
        }
    }
}
