package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam

/**
 * Proves that the public [WebSocketConnection] constructor lets a downstream build an in-process
 * client-spoke [us.tractat.kuilt.core.fabric.Mesh] for `testApplication` tests without re-deriving
 * the adapter — the headline use case of [WebSocketConnection]'s public visibility.
 *
 * The test mirrors exactly the downstream pattern from the KDoc:
 * open a client WebSocket session and pass it to [meshSeam] via [WebSocketConnection]. Asserts
 * that the mesh preamble completes: hub sees the client in its peer set and vice-versa.
 */
class PublicWebSocketConnectionSpokeTest {

    @Test
    fun clientSpokeHandshakesHubViaMeshSeamUsingPublicWebSocketConnection() =
        testApplication {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
            val source = KtorConnectionSource(application, "/hub")
            val client = createClient { install(ClientWebSockets) }
            val hubId = PeerId("hub")
            val clientId = PeerId("client-0")

            coroutineScope {
                val hubMeshDeferred = async {
                    meshSeam(hubId, emptyList(), dispatcher).also { hub ->
                        hub.addLink(source.accept())
                    }
                }
                client.webSocket("/hub") {
                    val clientSeam = meshSeam(clientId, listOf(WebSocketConnection(this)), dispatcher)
                    val hub = hubMeshDeferred.await()

                    withTimeout(5_000) {
                        hub.peers.first { hubId in it && clientId in it }
                        clientSeam.peers.first { clientId in it && hubId in it }
                    }

                    assertTrue(
                        clientId in hub.peers.value,
                        "hub should see client-0 after handshake",
                    )
                    assertTrue(
                        hubId in clientSeam.peers.value,
                        "client spoke should see hub after handshake",
                    )
                }
            }
        }
}
