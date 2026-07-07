package us.tractat.kuilt.websocket

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The #839 acceptance criterion for the WebSocket front door: an auth artifact on the accepting
 * call (here a header) → [KtorConnectionSource.principalExtractor] → the principal rides the
 * emitted connection and lands on the hub mesh's per-peer roster at admission, keyed by the
 * [PeerId] the spoke claimed in its `MeshHello`.
 */
class KtorConnectionSourceAttestationTest {

    @Test
    fun extractedPrincipalLandsOnTheHubRoster() =
        testApplication {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val source = KtorConnectionSource(application, "/hub") { call ->
                call.request.headers["X-Test-Principal"]?.let { Principal(it) }
            }

            val hubId = PeerId("hub")
            val clientId = PeerId("client")
            val hub = meshSeam(hubId, emptyList(), dispatcher, Random(1L))

            // Accept-pump for exactly one spoke, standing in for hostedOverlay's loop.
            val pumpScope = CoroutineScope(currentCoroutineContext() + Job())
            pumpScope.launch { hub.addLink(source.accept()) }

            val httpClient = createClient {
                install(ClientWebSockets)
                defaultRequest { header("X-Test-Principal", "user-alice") }
            }
            val loom = KtorMeshClientLoom(
                httpClient = httpClient,
                dispatcher = dispatcher,
                selfPeerId = clientId,
                random = Random(42L),
            )

            try {
                loom.weave(
                    Rendezvous.Existing(
                        WebSocketAdvertisement(url = "/hub", serverPeerId = hubId, sessionName = "client"),
                    ),
                )
                val roster = withTimeout(5_000) { hub.attestedPrincipals.first { clientId in it } }
                assertEquals(Principal("user-alice"), roster[clientId])
            } finally {
                pumpScope.cancel()
            }
        }
}
