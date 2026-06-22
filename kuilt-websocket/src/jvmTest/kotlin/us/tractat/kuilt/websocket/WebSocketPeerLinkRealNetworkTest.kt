package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import us.tractat.kuilt.core.Seam
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Real-network integration tests: a real Netty server bound to a random
 * localhost port, real OkHttp client. Catches issues that Ktor
 * `testApplication` cannot — real socket framing, real WebSocket upgrade
 * handshake, real network buffers.
 *
 * Kept deliberately small (the bulk of behavioural coverage lives in
 * [WebSocketPeerLinkFactoryTest], which uses `testApplication` and is much
 * faster). These tests exist to pin "the real wire works."
 */
class WebSocketPeerLinkRealNetworkTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port: Int = 0
    private val path = "/ws/integration"
    private lateinit var serverFactory: KtorServerLoom

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Skipped: set -Pserver.realnet.tests=true to run real-network integration tests",
            System.getProperty("server.realnet.tests") == "true",
        )
        port = ServerSocket(0).use { it.localPort }
        server =
            embeddedServer(Netty, port = port) {
                serverFactory = KtorServerLoom(this, path)
            }
        server.start(wait = false)
    }

    @AfterTest
    fun tearDown() {
        // Guard against setUp's Assume short-circuiting before initialisation.
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    @Test
    fun `client connects to a real Netty server and broadcasts round-trip`() =
        runBlocking {
            val client = HttpClient(OkHttp) { install(ClientWebSockets) }
            try {
                val (serverLink, clientLink) = connectPair(client)
                val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                val received =
                    coroutineScope {
                        val deferred = async { withTimeout(3_000) { serverLink.incoming.first() } }
                        clientLink.broadcast(payload)
                        deferred.await()
                    }
                assertTrue(received.toByteArray().contentEquals(payload))
                assertEquals(clientLink.selfId, received.sender)
                clientLink.close()
            } finally {
                client.close()
            }
        }

    @Test
    fun `multiple frames preserve FIFO order over real network`() =
        runBlocking {
            val client = HttpClient(OkHttp) { install(ClientWebSockets) }
            try {
                val (serverLink, clientLink) = connectPair(client)
                val received =
                    coroutineScope {
                        val frames =
                            async {
                                withTimeout(3_000) { serverLink.incoming.take(3).toList() }
                            }
                        clientLink.broadcast(byteArrayOf(1))
                        clientLink.broadcast(byteArrayOf(2))
                        clientLink.broadcast(byteArrayOf(3))
                        frames.await()
                    }
                assertTrue(received[0].toByteArray().contentEquals(byteArrayOf(1)))
                assertTrue(received[1].toByteArray().contentEquals(byteArrayOf(2)))
                assertTrue(received[2].toByteArray().contentEquals(byteArrayOf(3)))
                clientLink.close()
            } finally {
                client.close()
            }
        }

    private suspend fun connectPair(client: HttpClient): Pair<Seam, Seam> =
        coroutineScope {
            val serverDeferred = async { serverFactory.nextLink() }
            val clientFactory = KtorClientLoom(client)
            val clientLink =
                clientFactory.join(
                    WebSocketAdvertisement(
                        url = "ws://localhost:$port$path",
                        serverPeerId = serverFactory.selfPeerId,
                        displayName = "real-net-client",
                    ),
                )
            val serverLink = serverDeferred.await()
            serverLink to clientLink
        }
}
