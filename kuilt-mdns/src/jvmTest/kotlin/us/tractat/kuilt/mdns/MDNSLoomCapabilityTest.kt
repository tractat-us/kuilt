package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import us.tractat.kuilt.core.TransportRole
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * [MDNSPeerLinkFactory] self-reports the Bonjour/mDNS discovery fabric's roles:
 * it discovers peers on the LAN and, once found, reaches them over the shared
 * Wi-Fi network.
 */
class MDNSLoomCapabilityTest {
    @Test
    fun declaresDiscoveryAndWifiLanRoles() {
        val port = ServerSocket(0).use { it.localPort }
        lateinit var factory: MDNSPeerLinkFactory
        // The factory's init wires a KtorServerLoom onto a real Application, so it must be
        // built inside an embeddedServer scope — the module lambda runs on start. capability()
        // itself is a pure pre-connect self-report; the server is stopped immediately.
        val server = embeddedServer(Netty, port = port) {
            factory = MDNSPeerLinkFactory(
                serviceType = MDNSServiceType("_kuilt-test._tcp"),
                application = this,
                jmdns = CapturingJmDNS(),
                port = port,
                wsPath = "/ws/mdns-cap",
                httpClientFactory = { HttpClient(OkHttp) { install(ClientWebSockets) } },
            )
        }
        server.start(wait = false)
        try {
            assertEquals(
                setOf(TransportRole.Discovery, TransportRole.WifiLan),
                factory.capability().roles,
            )
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
}
