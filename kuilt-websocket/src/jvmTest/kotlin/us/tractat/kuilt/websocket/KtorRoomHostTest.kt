package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.Room
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [KtorRoomHost]. Drives a Ktor [testApplication] server in-process,
 * mirrors the harness shape of [WebSocketSeamRoundTripTest].
 */
class KtorRoomHostTest {
    private val serverPath = "/ws/room-test"
    private val serverPeerId = PeerId("test-room-server")
    private val serverPattern = Pattern("test-room")

    @Test
    fun `accepted WS connection produces a Room with serverPeerId`() =
        testApplication {
            val host =
                KtorRoomHost(
                    application = application,
                    path = serverPath,
                    serverPeerId = serverPeerId,
                    pattern = serverPattern,
                )
            val clientLoom = KtorClientLoom(createClient { install(ClientWebSockets) })
            val firstRoom = CompletableDeferred<Room>()

            coroutineScope {
                val hostJob = launch { host.start { room -> firstRoom.complete(room) } }
                val advertisement =
                    WebSocketAdvertisement(
                        url = "ws://localhost$serverPath",
                        serverPeerId = serverPeerId,
                        displayName = "client",
                    )
                val clientLink = clientLoom.join(advertisement)
                val room = withTimeout(5_000) { firstRoom.await() }
                assertEquals(serverPeerId, room.selfId)
                clientLink.close(CloseReason.Normal)
                hostJob.cancel()
            }
        }

    @Test
    fun `start a second time on same host throws IllegalStateException`() =
        testApplication {
            val host = KtorRoomHost(application, serverPath, serverPeerId, serverPattern)
            coroutineScope {
                val job = launch { host.start { awaitCancellation() } }
                // Yield twice so the launched coroutine reaches `withLock`.
                yield()
                yield()
                val ex = runCatching { host.start { } }.exceptionOrNull()
                assertNotNull(ex, "second start() must throw")
                assertTrue(
                    ex is IllegalStateException,
                    "expected IllegalStateException, got ${ex::class.simpleName}: ${ex.message}",
                )
                job.cancel()
            }
        }

    @Test
    fun `accept loop tears down cleanly when scope is cancelled`() =
        testApplication {
            val host = KtorRoomHost(application, serverPath, serverPeerId, serverPattern)
            coroutineScope {
                val job = launch { host.start { awaitCancellation() } }
                yield()
                job.cancel()
                // Job must complete promptly; if it hangs, the loop is stuck.
                withTimeout(2_000) { job.join() }
            }
        }
}
