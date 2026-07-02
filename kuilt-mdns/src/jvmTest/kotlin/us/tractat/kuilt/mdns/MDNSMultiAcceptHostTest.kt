package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Verifies [MDNSMultiAcceptHost]'s advertise-once / accept-N contract over the
 * **real localhost byte path** — a real Netty WebSocket server, real OkHttp
 * WebSocket clients — with JmDNS absorbed by [CapturingJmDNS] (no multicast, so
 * this is **not** `-P`-gated).
 *
 * The load-bearing assertion is that accepting N joiners does **not** re-register
 * the mDNS service — `registerCount` stays `1` across every accept. That is the
 * behaviour distinguishing this helper from [MDNSPeerLinkFactory], whose fused
 * register+accept re-publishes the service on every [weave][us.tractat.kuilt.core.Loom.weave].
 *
 * Teardown mirrors [MDNSConformanceTest]: accepted seams are closed with
 * [CloseReason.Normal] first, then the [HttpClient]s, then the server — avoiding
 * the documented EOF-in-next-test flake.
 */
class MDNSMultiAcceptHostTest {

    private val wsPath = "/ws/multi-accept"

    private var port: Int = 0
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var host: MDNSMultiAcceptHost
    private lateinit var jmdns: CapturingJmDNS

    private val clients = mutableListOf<HttpClient>()
    private val openSeams = mutableListOf<Seam>()

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        jmdns = CapturingJmDNS()
        server = embeddedServer(Netty, port = port) {
            host = MDNSMultiAcceptHost(
                serviceType = MDNSServiceType("_kuilt-test._tcp"),
                application = this,
                jmdns = jmdns,
                port = port,
                pattern = Pattern("multi-accept-host"),
                wsPath = wsPath,
            )
        }
        server.start(wait = false)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { openSeams.forEach { runCatching { it.close(CloseReason.Normal) } } }
        openSeams.clear()
        clients.forEach { it.close() }
        clients.clear()
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    @Test
    fun `accept-N does not re-register the mDNS service`() = runBlocking {
        host.advertise()
        assertEquals(1, jmdns.registerCount, "advertise() registers the service exactly once")

        val joinerIds = listOf(PeerId("joiner-a"), PeerId("joiner-b"))

        // Accept side: N accepts under the one advertisement, concurrently with the joins.
        val accepted = async {
            List(joinerIds.size) { host.nextSeam().also { openSeams += it } }
        }

        // Join side: two real WebSocket clients, each presenting a distinct peer id.
        joinerIds.forEach { id ->
            val client = HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it }
            val clientSeam = KtorClientLoom(client, selfPeerId = id).join(
                WebSocketAdvertisement(
                    url = "ws://localhost:$port$wsPath",
                    serverPeerId = host.selfPeerId,
                    displayName = "joiner",
                ),
            )
            openSeams += clientSeam
        }

        val seams = accepted.await()
        val remoteIds = seams
            .map { seam -> (seam.peers.first { it.size > 1 } - seam.selfId).single() }
            .toSet()

        assertAll(
            { assertEquals(2, seams.toSet().size, "two distinct accepted seams") },
            { assertEquals(joinerIds.toSet(), remoteIds, "accepted seams carry the two joiner peer ids") },
            // The whole point of the helper: no re-registration per accept.
            { assertEquals(1, jmdns.registerCount, "advertise-once: registerService must NOT be called per accept") },
        )

        host.close()
        assertAll(
            { assertEquals(1, jmdns.unregisterCount, "close() unregisters the service exactly once") },
            { assertEquals(0, jmdns.closeCount, "close() must NOT close the caller-owned JmDNS instance") },
        )
    }
}
