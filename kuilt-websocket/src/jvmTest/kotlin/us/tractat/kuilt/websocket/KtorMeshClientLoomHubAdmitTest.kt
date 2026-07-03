package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertTrue
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import kotlin.random.Random

/**
 * Proves [KtorMeshClientLoom] admits against a **real** [MuxServerLoom] hub — the regression this
 * closes. A `MuxServerLoom` handshakes every accepted connection as a mesh spoke (the in-band
 * `MeshHello` preamble); a [KtorClientLoom] pointed at the same hub would silently **hang**
 * (its [WebSocketSeam] sends no in-band handshake, so admit never completes). This test drives the
 * new mesh client end-to-end and asserts the spoke seam sees the hub — the contrast that motivates
 * the loom. It deliberately does NOT drive a `KtorClientLoom` against the hub (that path hangs).
 */
class KtorMeshClientLoomHubAdmitTest {

    @Test
    fun meshClientLoomAdmitsAgainstMuxServerLoomHub() =
        testApplication {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val source = KtorConnectionSource(application, "/hub")
            val hubId = PeerId("server")
            val clientId = PeerId("client")

            // MuxServerLoom's accept pump runs forever; give it a scope we cancel at teardown.
            val hubScope = CoroutineScope(currentCoroutineContext() + Job())
            MuxServerLoom(
                source = source,
                scope = hubScope,
                selfId = hubId,
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(1234L),
            )

            val httpClient = createClient { install(ClientWebSockets) }
            val loom = KtorMeshClientLoom(
                httpClient = httpClient,
                dispatcher = dispatcher,
                selfPeerId = clientId,
                random = Random(42L),
            )

            try {
                val seam = loom.weave(
                    Rendezvous.Existing(
                        WebSocketAdvertisement(url = "/hub", serverPeerId = hubId, sessionName = "client"),
                    ),
                )

                // The load-bearing assertion: admit completed against the MuxServerLoom hub.
                withTimeout(5_000) { seam.peers.first { hubId in it } }

                assertTrue(
                    hubId in seam.peers.value,
                    "mesh spoke should see the MuxServerLoom hub ($hubId) after the MeshHello handshake",
                )
                assertTrue(
                    clientId in seam.peers.value,
                    "mesh spoke should always include its own id ($clientId)",
                )
            } finally {
                hubScope.cancel()
            }
        }
}
