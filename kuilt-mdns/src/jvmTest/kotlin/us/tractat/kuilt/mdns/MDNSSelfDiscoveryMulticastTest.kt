package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import us.tractat.kuilt.core.Rendezvous
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Real-Bonjour **one-device self-discovery** test — the #1466 reproducer the fiasco never tried.
 *
 * A single device that BOTH advertises and browses the same service type is delivered its OWN
 * advertisement by real Bonjour/JmDNS over genuine multicast, and would then dial itself. The
 * merged self-guards ([MDNSServiceDiscoverer]'s obligation KDoc, [MDNSPeerLinkFactory]'s fail-fast
 * `require`) must hold on the *real* transport, not only under the [CapturingJmDNS] double that
 * [MDNSSelfDiscoveryFilterTest] uses. This test proves both halves over real multicast:
 *  1. the device really does discover its own advertisement (the self-return precondition of #1466), and
 *  2. weaving that self-advertisement is refused ([MDNSPeerLinkFactory] never self-connects).
 *
 * **Opt-in / `-P`-gated** — mirrors [MDNSMulticastIntegrationTest]: skipped unless
 * `-Pmdns.multicast.tests=true` (forwarded to the JVM system property by `build.gradle.kts`), because
 * real multicast is unreliable in CI (containers/VMs without a LAN interface). Run on a Mac / a machine
 * with a real NIC:
 *
 * ```
 * ./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true
 * ```
 *
 * Authored-but-gated: it is not exercised by the default build; the flag turns it on for hardware/LAN
 * validation, pre-phone.
 */
class MDNSSelfDiscoveryMulticastTest {

    private var jmdns: JmDNS? = null
    private val servers = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
    private val clients = mutableListOf<HttpClient>()
    private val advertisers = mutableListOf<MDNSServiceAdvertiser>()

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "Skipped: set -Pmdns.multicast.tests=true to run real-multicast self-discovery tests",
            System.getProperty("mdns.multicast.tests") == "true",
        )
        val iface = multicastInterface() ?: run {
            Assume.assumeTrue("Skipped: no non-loopback, up, multicast-capable network interface found", false)
            return
        }
        val address = iface.inetAddresses.toList().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: run {
                Assume.assumeTrue("Skipped: interface ${iface.name} has no usable non-loopback address", false)
                return
            }
        // ONE JmDNS instance: it both advertises and browses, so it is delivered its own service.
        jmdns = JmDNS.create(address, "kuilt-self-discovery")
    }

    @After
    fun tearDown() {
        advertisers.forEach { it.unregister() }
        advertisers.clear()
        clients.forEach { it.close() }
        clients.clear()
        servers.forEach { it.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
        servers.clear()
        jmdns?.close()
        jmdns = null
    }

    @Test
    fun `a single advertise+browse device discovers its own service over real multicast and refuses to dial it`() {
        val jmdns = requireNotNull(jmdns)
        val serviceType = MDNSServiceType("_kuilt-self._tcp")
        val wsPath = "/ws/self-discovery-multicast"

        // Build the factory (its KtorServerLoom mints the local selfPeerId). embeddedServer is called
        // OUTSIDE any runBlocking receiver — see the note in MDNSSelfDiscoveryFilterTest: inside a
        // runBlocking receiver the CoroutineScope.embeddedServer extension parents the Netty job to the
        // enclosing runBlocking, which then deadlocks on its own server child. Here it binds to the
        // top-level overload; the server is a root job we stop in tearDown.
        //
        // Bind 0 and read the port back off the *live* connector. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU: the
        // probe closes before Netty binds, so another process can take the port in that window
        // (#1590, #1749). The port is an input to the factory (it is the advertised port this test
        // then discovers over real multicast), so the factory is built after start() — the module
        // lambda runs strictly before resolvedConnectors() can answer.
        val server = embeddedServer(Netty, port = 0) { /* route mounted post-start, see above */ }
            .also { servers += it }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val factory = MDNSPeerLinkFactory(
            serviceType = serviceType,
            application = server.application,
            jmdns = jmdns,
            port = port,
            wsPath = wsPath,
            httpClientFactory = { HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it } },
        )

        val selfId = factory.selfPeerId

        // Advertise THIS device's own session under its own selfPeerId over real multicast.
        val advertiser = MDNSServiceAdvertiser(
            serviceType = serviceType,
            jmdns = jmdns,
            displayName = "self-discovery-multicast",
            port = port,
            selfId = selfId,
            wsPath = wsPath,
        ).also { advertisers += it }
        advertiser.register()

        runBlocking {
            // (1) Real Bonjour returns the device its OWN advertisement — the #1466 self-return
            // precondition, on genuine multicast. Up to 10s for announcement propagation.
            val ownAdvertisement = withTimeout(10_000) {
                MDNSServiceDiscoverer(serviceType, jmdns)
                    .discoveries()
                    .first { it.serverPeerId == selfId }
            }
            assertEquals(selfId, ownAdvertisement.serverPeerId, "the device must discover its own advertisement")

            // (2) The self-guard refuses to weave (dial) the device's own discovered advertisement — the
            // #1466 self-connection, one transport up. A tight 5s window: if the guard ever regresses and
            // attempts a real self-dial, this fails as an assertion rather than hanging.
            withTimeout(5_000) {
                assertFailsWith<IllegalArgumentException>("weaving a self-advertisement must be refused") {
                    factory.weave(Rendezvous.Existing(ownAdvertisement))
                }
            }
        }
    }

    private fun multicastInterface(): NetworkInterface? =
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() && hasUsableAddress(it) }

    private fun hasUsableAddress(iface: NetworkInterface): Boolean =
        iface.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
}
