package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [WebSocketPeerLinkFactory] using Ktor's [testApplication].
 *
 * Architecture: two factories — one per side of the WebSocket connection.
 *
 * - [KtorServerLoom]: mounts a WebSocket route on a Ktor [Application].
 *   Each incoming connection produces a 2-peer [Seam] accessible via
 *   [KtorServerLoom.nextLink].
 *
 * - [KtorClientLoom]: client side. [join] with a [WebSocketAdvertisement]
 *   connects and returns a 2-peer [Seam].
 *
 * Together they cover the 2-peer case from ADR-005 §D2: `peers.value.size == 2`,
 * `broadcast` == `sendTo(remoteId, ...)`, sequence numbers are receiver-local.
 *
 * **JVM-only:** this module targets JVM only — WASM/iOS are deferred per the
 * transport-websocket CLAUDE.md.
 */
class WebSocketPeerLinkFactoryTest {
    private val serverPath = "/ws/test"

    @BeforeTest
    fun assumeOptIn() {
        Assume.assumeTrue(
            "Skipped: set -Pintegration.tests=true to run wall-clock integration tests",
            System.getProperty("integration.tests") == "true",
        )
    }

    // ── Construction & membership ────────────────────────────────────────────

    @Test
    fun `server link peers contains both selfId and client selfId after handshake`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            assertEquals(2, serverLink.peers.value.size)
            assertTrue(serverLink.selfId in serverLink.peers.value)
            assertTrue(clientLink.selfId in serverLink.peers.value)

            clientLink.close()
        }

    @Test
    fun `client link peers contains both selfId and server selfId after handshake`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            assertEquals(2, clientLink.peers.value.size)
            assertTrue(clientLink.selfId in clientLink.peers.value)
            assertTrue(serverLink.selfId in clientLink.peers.value)

            clientLink.close()
        }

    @Test
    fun `server and client link selfIds are distinct`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            assertNotEquals(serverLink.selfId, clientLink.selfId)

            clientLink.close()
        }

    @Test
    fun `KtorServerLoom satisfies Loom without casting`() =
        testApplication {
            val factory: Loom = KtorServerLoom(application, serverPath)
            assertIs<Loom>(factory)
        }

    @Test
    fun `KtorClientLoom satisfies Loom without casting`() =
        testApplication {
            val factory: Loom = KtorClientLoom(createClient { install(WebSockets) })
            assertIs<Loom>(factory)
        }

    // ── Broadcast ────────────────────────────────────────────────────────────

    @Test
    fun `broadcast from client reaches server with correct sender and payload`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val payload = byteArrayOf(1, 2, 3)
            val frame =
                coroutineScope {
                    val received = async { serverLink.incoming.first() }
                    clientLink.broadcast(payload)
                    withTimeout(2_000) { received.await() }
                }

            assertTrue(frame.payload.contentEquals(payload))
            assertEquals(clientLink.selfId, frame.sender)

            clientLink.close()
        }

    @Test
    fun `broadcast from server reaches client with correct sender and payload`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val payload = byteArrayOf(4, 5, 6)
            val frame =
                coroutineScope {
                    val received = async { clientLink.incoming.first() }
                    serverLink.broadcast(payload)
                    withTimeout(2_000) { received.await() }
                }

            assertTrue(frame.payload.contentEquals(payload))
            assertEquals(serverLink.selfId, frame.sender)

            clientLink.close()
        }

    // ── SendTo ───────────────────────────────────────────────────────────────

    @Test
    fun `sendTo remote is equivalent to broadcast in 2-peer mode`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val payload = byteArrayOf(7, 8, 9)
            val frame =
                coroutineScope {
                    val received = async { serverLink.incoming.first() }
                    clientLink.sendTo(serverLink.selfId, payload)
                    withTimeout(2_000) { received.await() }
                }

            assertTrue(frame.payload.contentEquals(payload))
            assertEquals(clientLink.selfId, frame.sender)

            clientLink.close()
        }

    @Test
    fun `sendTo self throws IllegalArgumentException`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (_, clientLink) = connectPair(serverFactory, clientFactory)

            assertFailsWith<IllegalArgumentException> {
                clientLink.sendTo(clientLink.selfId, byteArrayOf(1))
            }

            clientLink.close()
        }

    // ── Sequence stamping ────────────────────────────────────────────────────

    @Test
    fun `received frames carry receiver-local monotonically increasing sequence numbers`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

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

            clientLink.close()
        }

    @Test
    fun `sequence numbers are receiver-local — client and server counters are independent`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val (receivedOnServer, receivedOnClient) =
                coroutineScope {
                    val serverFrames = async { serverLink.incoming.take(2).toList() }
                    val clientFrames = async { clientLink.incoming.take(1).toList() }
                    clientLink.broadcast(byteArrayOf(1))
                    clientLink.broadcast(byteArrayOf(2))
                    serverLink.broadcast(byteArrayOf(99))
                    withTimeout(2_000) { serverFrames.await() } to withTimeout(2_000) { clientFrames.await() }
                }

            // Server's receiver-local counter
            assertEquals(1L, receivedOnServer[0].sequence)
            assertEquals(2L, receivedOnServer[1].sequence)
            // Client's receiver-local counter starts independently at 1
            assertEquals(1L, receivedOnClient[0].sequence)

            clientLink.close()
        }

    @Test
    fun `sequence increments across mixed broadcast and sendTo calls at receiver`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val received =
                coroutineScope {
                    val frames = async { serverLink.incoming.take(2).toList() }
                    clientLink.broadcast(byteArrayOf(10))
                    clientLink.sendTo(serverLink.selfId, byteArrayOf(20))
                    withTimeout(2_000) { frames.await() }
                }

            assertEquals(1L, received[0].sequence)
            assertEquals(2L, received[1].sequence)

            clientLink.close()
        }

    // ── Wire transparency ────────────────────────────────────────────────────

    @Test
    fun `wire is byte-transparent — bytes sent equal bytes received with no framing prefix`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            // A CBOR map starts with 0xA0|n. Earlier framing prefixes (0x01, 0x02)
            // would corrupt this. This test pins the wire as byte-transparent so
            // existing CBOR-Envelope clients keep working unchanged.
            val cborLikePayload = byteArrayOf(0xA1.toByte(), 0x63, 'f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0x01)

            val frame =
                coroutineScope {
                    val received = async { serverLink.incoming.first() }
                    clientLink.broadcast(cborLikePayload)
                    withTimeout(2_000) { received.await() }
                }

            assertTrue(
                frame.payload.contentEquals(cborLikePayload),
                "Expected payload ${cborLikePayload.toList()}, got ${frame.payload.toList()}",
            )
            assertEquals(cborLikePayload.size, frame.payload.size, "No bytes added or removed by transport")

            clientLink.close()
        }

    // ── FIFO delivery ────────────────────────────────────────────────────────

    @Test
    fun `two broadcasts from client arrive at server in send order`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            val received =
                coroutineScope {
                    val frames = async { serverLink.incoming.take(2).toList() }
                    clientLink.broadcast(byteArrayOf(10))
                    clientLink.broadcast(byteArrayOf(20))
                    withTimeout(2_000) { frames.await() }
                }

            assertTrue(received[0].payload.contentEquals(byteArrayOf(10)))
            assertTrue(received[1].payload.contentEquals(byteArrayOf(20)))

            clientLink.close()
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `close is idempotent — calling twice does not throw`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (_, clientLink) = connectPair(serverFactory, clientFactory)

            clientLink.close()
            clientLink.close() // must not throw
        }

    @Test
    fun `closing client link removes client from server peers set`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            // Precondition: 2 peers
            assertEquals(2, serverLink.peers.value.size)

            clientLink.close(CloseReason.Normal)

            // Server side should converge to {selfId only} as the WS connection closes.
            val finalPeers =
                withTimeout(3_000) {
                    serverLink.peers.first { it.size == 1 }
                }
            assertEquals(1, finalPeers.size)
            assertTrue(serverLink.selfId in finalPeers)
            assertFalse(clientLink.selfId in finalPeers)
        }

    @Test
    fun `broadcast after close throws IllegalStateException`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (_, clientLink) = connectPair(serverFactory, clientFactory)

            clientLink.close()

            assertFailsWith<IllegalStateException> {
                clientLink.broadcast(byteArrayOf(1))
            }
        }

    @Test
    fun `sendTo after close throws IllegalStateException`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            clientLink.close()

            assertFailsWith<IllegalStateException> {
                clientLink.sendTo(serverLink.selfId, byteArrayOf(1))
            }
        }

    @Test
    fun `closing server link removes it from client peers set`() =
        testApplication {
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) = connectPair(serverFactory, clientFactory)

            // Precondition: 2 peers
            assertEquals(2, clientLink.peers.value.size)

            serverLink.close(CloseReason.Normal)

            // Client side should converge to {selfId only} as the WS connection closes.
            val finalPeers =
                withTimeout(3_000) {
                    clientLink.peers.first { it.size == 1 }
                }
            assertEquals(1, finalPeers.size)
            assertTrue(clientLink.selfId in finalPeers)
            assertFalse(serverLink.selfId in finalPeers)
        }

    @Test
    fun `join on server factory throws UnsupportedOperationException`() =
        testApplication {
            val factory = KtorServerLoom(application, serverPath)
            assertFailsWith<UnsupportedOperationException> {
                factory.join(
                    WebSocketAdvertisement(
                        url = "ws://localhost$serverPath",
                        serverPeerId = factory.selfPeerId,
                        displayName = "x",
                    ),
                )
            }
        }

    @Test
    fun `open on client factory throws UnsupportedOperationException`() =
        testApplication {
            val factory = KtorClientLoom(createClient { install(WebSockets) })
            assertFailsWith<UnsupportedOperationException> {
                factory.host(Pattern("client"))
            }
        }

    @Test
    fun `open on server factory with Pattern produces a link that accepts a subsequent join`() =
        testApplication {
            // Tests that open() is a valid entry point on the server factory (Loom interface).
            val serverFactory = KtorServerLoom(application, serverPath)
            val clientFactory = KtorClientLoom(createClient { install(WebSockets) })

            val (serverLink, clientLink) =
                coroutineScope {
                    val serverLinkDeferred = async { serverFactory.host(Pattern("server")) }
                    val clientLink =
                        clientFactory.join(
                            WebSocketAdvertisement(
                                url = "ws://localhost$serverPath",
                                serverPeerId = serverFactory.selfPeerId,
                                displayName = "client",
                            ),
                        )
                    val serverLink = serverLinkDeferred.await()
                    serverLink to clientLink
                }

            assertEquals(2, serverLink.peers.value.size)
            assertEquals(2, clientLink.peers.value.size)

            clientLink.close()
        }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Concurrently starts the server-side accept and the client-side connect.
     * Returns once both links are fully formed (handshake complete).
     */
    private suspend fun connectPair(
        serverFactory: KtorServerLoom,
        clientFactory: KtorClientLoom,
        timeoutMs: Long = 5_000,
    ): Pair<Seam, Seam> =
        withTimeout(timeoutMs) {
            coroutineScope {
                val serverLinkDeferred = async { serverFactory.nextLink() }
                val clientLink =
                    clientFactory.join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverFactory.selfPeerId,
                            displayName = "client",
                        ),
                    )
                val serverLink = serverLinkDeferred.await()
                serverLink to clientLink
            }
        }
}
