package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Seam
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip integration tests for [WebSocketSeam] via [KtorServerLoom] and
 * [KtorClientLoom]. Drives a Ktor [testApplication] server in-process.
 */
class WebSocketSeamRoundTripTest {
    private val serverPath = "/ws/seam-test"

    // ── Broadcast — client → server ──────────────────────────────────────────

    @Test
    fun `broadcast from client reaches server with correct payload`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)

            val payload = byteArrayOf(1, 2, 3)
            val swatch =
                coroutineScope {
                    val received = async { serverLink.incoming.first() }
                    clientLink.broadcast(payload)
                    withTimeout(2_000) { received.await() }
                }

            assertContentEquals(payload, swatch.payload)
            assertEquals(clientLink.selfId, swatch.sender)

            clientLink.close(CloseReason.Normal)
        }

    // ── Broadcast — server → client ──────────────────────────────────────────

    @Test
    fun `broadcast from server reaches client with correct payload`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)

            val payload = byteArrayOf(4, 5, 6)
            val swatch =
                coroutineScope {
                    val received = async { clientLink.incoming.first() }
                    serverLink.broadcast(payload)
                    withTimeout(2_000) { received.await() }
                }

            assertContentEquals(payload, swatch.payload)
            assertEquals(serverLink.selfId, swatch.sender)

            clientLink.close(CloseReason.Normal)
        }

    // ── Sequence numbers ─────────────────────────────────────────────────────

    @Test
    fun `received frames carry monotonically increasing sequence numbers`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)

            val received =
                coroutineScope {
                    val frames = async { serverLink.incoming.take(3).toList() }
                    clientLink.broadcast(byteArrayOf(1))
                    clientLink.broadcast(byteArrayOf(2))
                    clientLink.broadcast(byteArrayOf(3))
                    withTimeout(2_000) { frames.await() }
                }

            assertEquals(1L, received[0].sequence)
            assertEquals(2L, received[1].sequence)
            assertEquals(3L, received[2].sequence)

            clientLink.close(CloseReason.Normal)
        }

    @Test
    fun `sequence counters are receiver-local and independent`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)

            val (serverSwatches, clientSwatches) =
                coroutineScope {
                    val serverFrames = async { serverLink.incoming.take(2).toList() }
                    val clientFrames = async { clientLink.incoming.take(1).toList() }
                    clientLink.broadcast(byteArrayOf(1))
                    clientLink.broadcast(byteArrayOf(2))
                    serverLink.broadcast(byteArrayOf(99))
                    withTimeout(2_000) { serverFrames.await() } to withTimeout(2_000) { clientFrames.await() }
                }

            assertEquals(1L, serverSwatches[0].sequence)
            assertEquals(2L, serverSwatches[1].sequence)
            assertEquals(1L, clientSwatches[0].sequence)

            clientLink.close(CloseReason.Normal)
        }

    // ── Peers StateFlow ──────────────────────────────────────────────────────

    @Test
    fun `peers contains both sides after connect`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)

            assertEquals(2, serverLink.peers.value.size)
            assertEquals(2, clientLink.peers.value.size)
            assertTrue(serverLink.selfId in serverLink.peers.value)
            assertTrue(clientLink.selfId in serverLink.peers.value)

            clientLink.close(CloseReason.Normal)
        }

    @Test
    fun `close removes remote from peers`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom)
            assertEquals(2, serverLink.peers.value.size)

            clientLink.close(CloseReason.Normal)

            val finalPeers = withTimeout(3_000) { serverLink.peers.first { it.size == 1 } }
            assertEquals(1, finalPeers.size)
            assertTrue(serverLink.selfId in finalPeers)
            assertFalse(clientLink.selfId in finalPeers)
        }

    // ── Helper ───────────────────────────────────────────────────────────────

    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        clientLoom: KtorClientLoom,
        timeoutMs: Long = 5_000,
    ): Pair<Seam, Seam> =
        withTimeout(timeoutMs) {
            coroutineScope {
                val serverLinkDeferred = async { serverLoom.nextLink() }
                val advertisement =
                    WebSocketAdvertisement(
                        url = "ws://localhost$serverPath",
                        serverPeerId = serverLoom.selfPeerId,
                        displayName = "client",
                    )
                val clientLink = clientLoom.join(advertisement)
                val serverLink = serverLinkDeferred.await()
                serverLink to clientLink
            }
        }
}
