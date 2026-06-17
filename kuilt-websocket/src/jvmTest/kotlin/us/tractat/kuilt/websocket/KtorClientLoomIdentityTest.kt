package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests that [KtorClientLoom.selfPeerId] is stable across reconnects when pinned,
 * and random-but-fixed-per-instance when using the default.
 */
class KtorClientLoomIdentityTest {

    private val serverPath = "/ws/identity-test"

    @Test
    fun `pinned selfPeerId is presented to the server on both joins`() =
        testApplication {
            val pinnedId = PeerId("client-x")
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = pinnedId,
            )
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                displayName = "client",
            )

            val (firstServerSeam, firstClientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            val firstRemoteIdSeen = firstServerSeam.peers.value
                .first { it != serverLoom.selfPeerId }
            firstClientSeam.close(CloseReason.Normal)
            withTimeout(3_000) { firstServerSeam.peers.value.also { } } // let seam settle

            val (secondServerSeam, secondClientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            val secondRemoteIdSeen = secondServerSeam.peers.value
                .first { it != serverLoom.selfPeerId }
            secondClientSeam.close(CloseReason.Normal)

            assertAll(
                { assertEquals(pinnedId, firstClientSeam.selfId, "first join: client selfId") },
                { assertEquals(pinnedId, firstRemoteIdSeen, "first join: server sees pinned id") },
                { assertEquals(pinnedId, secondClientSeam.selfId, "second join: client selfId") },
                { assertEquals(pinnedId, secondRemoteIdSeen, "second join: server sees same pinned id") },
            )
        }

    @Test
    fun `default selfPeerId is consistent within a loom instance`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                displayName = "client",
            )

            val (_, firstClientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            val idFromFirstJoin = firstClientSeam.selfId
            firstClientSeam.close(CloseReason.Normal)

            val (_, secondClientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            val idFromSecondJoin = secondClientSeam.selfId
            secondClientSeam.close(CloseReason.Normal)

            assertAll(
                // The loom has a fixed selfPeerId; both joins present the same id.
                { assertEquals(idFromFirstJoin, idFromSecondJoin, "same loom, same id across joins") },
                // And the loom's property matches what seams report.
                { assertEquals(clientLoom.selfPeerId, idFromFirstJoin, "loom.selfPeerId matches seam.selfId") },
            )
        }

    @Test
    fun `two distinct default looms mint different identities`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val loomA = KtorClientLoom(createClient { install(WebSockets) })
            val loomB = KtorClientLoom(createClient { install(WebSockets) })

            assertNotEquals(loomA.selfPeerId, loomB.selfPeerId, "distinct loom instances should have distinct default ids")
        }

    // ── Helper ───────────────────────────────────────────────────────────────

    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        advertisement: WebSocketAdvertisement,
        clientLoom: KtorClientLoom,
        timeoutMs: Long = 5_000,
    ) = withTimeout(timeoutMs) {
        coroutineScope {
            val serverLinkDeferred = async { serverLoom.nextLink() }
            val clientLink = clientLoom.join(advertisement)
            serverLinkDeferred.await() to clientLink
        }
    }
}
