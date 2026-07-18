package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import us.tractat.kuilt.core.TransportRole
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * The Ktor WebSocket looms are the server-relay fabric: they reach peers by
 * relaying through a server and carry data over that relay. Both the plain
 * client and the mesh-spoke client declare the same [TransportRole] pair.
 */
class KtorLoomCapabilityTest {

    private val httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }

    @AfterTest
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun clientDeclaresServerRelayAndData() {
        assertEquals(
            setOf(TransportRole.ServerRelay, TransportRole.Data),
            KtorClientLoom(httpClient).capability().roles,
        )
    }

    @Test
    fun meshClientDeclaresServerRelayAndData() {
        assertEquals(
            setOf(TransportRole.ServerRelay, TransportRole.Data),
            KtorMeshClientLoom(httpClient).capability().roles,
        )
    }
}
