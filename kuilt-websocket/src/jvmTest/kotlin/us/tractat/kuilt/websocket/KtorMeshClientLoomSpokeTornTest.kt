package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.fabric.hubMesh
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A [KtorMeshClientLoom] spoke is a genuine peer-mesh: its session ends when its one and only
 * peer — the hub — goes away. This regression pins that the spoke honours the
 * `incoming`-completes-on-Torn contract when the hub drops the link mid-session.
 *
 * Before #1436 the loom built its spoke seam with `meshSeam` (hub semantics: never self-torns on
 * drain), so a hub disconnect left the spoke stuck `Woven` forever with `incoming` never
 * completing — #1386's violation in a shipping production path. After the migration to `peerMesh`
 * the spoke latches [SeamState.Torn] and completes [incoming] the instant its last link drops.
 */
class KtorMeshClientLoomSpokeTornTest {

    @Test
    fun spokeLatchesTornAndCompletesIncomingWhenHubDropsLink() =
        testApplication {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val source = KtorConnectionSource(application, "/hub")

            val hubId = PeerId("hub")
            val clientId = PeerId("client")
            // A start-empty-and-grow host: legitimately sits empty between joiners → hubMesh.
            val hub = hubMesh(hubId, emptyList(), dispatcher, Random(1L))

            // Accept-pump for exactly one spoke, standing in for hostedOverlay's loop.
            val pumpScope = CoroutineScope(currentCoroutineContext() + Job())
            pumpScope.launch { hub.addLink(source.accept()) }

            val httpClient = createClient { install(ClientWebSockets) }
            val loom = KtorMeshClientLoom(
                httpClient = httpClient,
                dispatcher = dispatcher,
                selfPeerId = clientId,
                random = Random(42L),
            )

            try {
                val spoke = loom.weave(
                    Rendezvous.Existing(
                        WebSocketAdvertisement(url = "/hub", serverPeerId = hubId, sessionName = "client"),
                    ),
                )

                // Handshake completes: the spoke sees the hub, and the hub sees the spoke.
                withTimeout(5_000) {
                    spoke.peers.first { clientId in it && hubId in it }
                    hub.peers.first { hubId in it && clientId in it }
                }

                // The hub drops the link out from under the live spoke session (a remote
                // disconnect — the spoke never calls close() itself).
                hub.close()

                // The spoke's one and only peer is gone → a peer-mesh must latch Torn …
                assertIs<SeamState.Torn>(
                    withTimeout(5_000) { spoke.state.first { it is SeamState.Torn } },
                    "spoke must latch Torn when the hub drops its single link",
                )
                // … and complete incoming (a late collector on the drained spool terminates).
                withTimeout(5_000) { spoke.incoming.toList() }
            } finally {
                pumpScope.cancel()
            }
        }
}
