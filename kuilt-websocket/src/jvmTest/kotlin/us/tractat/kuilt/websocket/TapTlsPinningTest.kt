package us.tractat.kuilt.websocket

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.websocket.tls.DevTlsIdentity
import us.tractat.kuilt.websocket.tls.generateDevTlsIdentity
import us.tractat.kuilt.websocket.tls.pinnedTlsHttpClient
import us.tractat.kuilt.websocket.tls.tapTlsConnector
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the optional `wss://` tap-reach path: a real Netty server serving a
 * [KtorServerLoom] over TLS with a self-signed [DevTlsIdentity], joined by a
 * [pinnedTlsHttpClient] that trusts only the host's certificate fingerprint.
 *
 * The two assertions together are the "done when" of the wire:
 *  - a joiner that pins the host's fingerprint completes a TLS handshake and exchanges frames
 *    ([wssRoundTripWithPinnedClient]) — i.e. the wire is TLS, not plaintext `ws://`;
 *  - a joiner that pins the wrong fingerprint is rejected at the handshake ([wrongFingerprintRejected]) —
 *    i.e. an on-path party without the pinned cert cannot connect, so it cannot read the wire.
 *
 * The server-engine-free half of the story — the fingerprint identity and the pin's accept/reject
 * logic — lives in [us.tractat.kuilt.websocket.tls.TapTlsHelpersTest], which runs on Android too.
 */
class TapTlsPinningTest {

    private val path = "/kuilt/tap-tls"

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @AfterTest
    fun tearDown() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    private fun startTlsServer(identity: DevTlsIdentity): Pair<KtorServerLoom, Int> {
        val port = ServerSocket(0).use { it.localPort }
        lateinit var loom: KtorServerLoom
        val srv = embeddedServer(
            Netty,
            configure = { tapTlsConnector(identity, port = port) },
        ) {
            loom = KtorServerLoom(this, path)
        }
        srv.start(wait = false)
        server = srv
        return loom to port
    }

    @Test
    fun wssRoundTripWithPinnedClient(): Unit = runBlocking {
        val identity = generateDevTlsIdentity()
        val (serverLoom, port) = startTlsServer(identity)
        val httpClient = pinnedTlsHttpClient(identity.fingerprintSha256)
        try {
            val clientLoom = KtorClientLoom(httpClient)
            val (serverLink, clientLink) = connectPair(serverLoom, clientLoom, port)

            val payload = byteArrayOf(7, 8, 9)
            val received = coroutineScope {
                val got = async { serverLink.incoming.first() }
                clientLink.broadcast(payload)
                withTimeout(5_000) { got.await() }
            }
            assertContentEquals(payload, received.toByteArray())
            assertEquals(clientLink.selfId, received.sender)

            clientLink.close(CloseReason.Normal)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun wrongFingerprintRejected(): Unit = runBlocking {
        val identity = generateDevTlsIdentity()
        val (serverLoom, port) = startTlsServer(identity)
        // A different identity's fingerprint — a stand-in for an on-path attacker's cert.
        val wrong = generateDevTlsIdentity().fingerprintSha256
        assertTrue(wrong != identity.fingerprintSha256)
        val httpClient = pinnedTlsHttpClient(wrong)
        try {
            val clientLoom = KtorClientLoom(httpClient)
            assertFailsWith<Exception> {
                withTimeout(5_000) { connectPair(serverLoom, clientLoom, port) }
            }
        } finally {
            httpClient.close()
        }
    }

    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        clientLoom: KtorClientLoom,
        port: Int,
    ): Pair<Seam, Seam> = coroutineScope {
        val serverLinkDeferred = async { serverLoom.nextLink() }
        val advertisement = WebSocketAdvertisement(
            url = "wss://localhost:$port$path",
            serverPeerId = serverLoom.selfPeerId,
            sessionName = "tls-client",
        )
        val clientLink = clientLoom.join(advertisement)
        val serverLink = serverLinkDeferred.await()
        serverLink to clientLink
    }
}
