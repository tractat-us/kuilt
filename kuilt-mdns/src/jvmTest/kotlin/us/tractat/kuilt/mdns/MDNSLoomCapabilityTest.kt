package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.TransportRole
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
        // Bind 0 and read the port back off the *live* connector, then wire the factory onto the
        // started server's Application. Probing a free port with a throwaway
        // `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU: the probe
        // closes before Netty binds, so on a loaded box another process can take the port in that
        // window (`BindException: Address already in use` — #1590, #1749). Binding 0 has no window.
        //
        // The factory's init wires a KtorServerLoom onto a real Application, and the port is an
        // input to it, so it is built *after* start() — the module lambda runs during start(),
        // strictly before resolvedConnectors() can answer. capability() itself is a pure pre-connect
        // self-report; the server is stopped immediately.
        val server = embeddedServer(Netty, port = 0) { /* wired post-start, see above */ }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val factory = MDNSPeerLinkFactory(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            wsPath = "/ws/mdns-cap",
            httpClientFactory = { HttpClient(OkHttp) { install(ClientWebSockets) } },
        )
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
