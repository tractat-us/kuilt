package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.Principal
import us.tractat.kuilt.session.PrincipalAttested
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
                        sessionName = "client",
                    )
                val clientLink = clientLoom.join(advertisement)
                val room = withTimeout(5_000) { firstRoom.await() }
                assertEquals(serverPeerId, room.selfId)
                clientLink.close(CloseReason.Normal)
                hostJob.cancel()
            }
        }

    @Test
    fun `verified principal from the call rides the accepted connection`() =
        testApplication {
            val loom =
                KtorServerLoom(
                    application = application,
                    path = serverPath,
                    selfPeerId = serverPeerId,
                    principalExtractor = { Principal("device-9") },
                )
            val clientLoom = KtorClientLoom(createClient { install(ClientWebSockets) })

            coroutineScope {
                val seamDeferred = async { loom.nextLink() }
                val advertisement =
                    WebSocketAdvertisement(
                        url = "ws://localhost$serverPath",
                        serverPeerId = serverPeerId,
                        sessionName = "client",
                    )
                val clientLink = clientLoom.join(advertisement)
                val seam = withTimeout(5_000) { seamDeferred.await() }
                assertIs<PrincipalAttested>(seam, "accepted seam must carry the verified principal")
                assertEquals(Principal("device-9"), seam.principal)
                clientLink.close(CloseReason.Normal)
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
                val ex = runCatchingCancellable { host.start { } }.exceptionOrNull()
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

    /**
     * #449 regression guard: a non-cancellation error from the accept loop must
     * propagate out of [KtorRoomHost.start], not be swallowed or cause a silent
     * return.
     *
     * Uses the internal loom-injection constructor to inject a [FailingLoom] stub
     * whose [Loom.host] always throws [IllegalStateException]. The first iteration
     * of the accept loop calls [us.tractat.kuilt.session.SeamRoomFactory.host],
     * which in turn calls [Loom.host]. With the pre-fix `break` behaviour, [start]
     * would return normally (silently swallowing the error). With the fix (`throw e`),
     * it rethrows — this test is RED against a hypothetical revert to `break` and
     * GREEN with the current `throw e` production code.
     */
    @Test
    fun `accept loop non-cancellation error propagates out of start`() =
        runBlocking {
            val error = IllegalStateException("loom accept failed")
            val host = KtorRoomHost(
                path = serverPath,
                pattern = serverPattern,
                loom = FailingLoom(error),
            )
            val result = runCatchingCancellable {
                coroutineScope {
                    host.start { awaitCancellation() }
                }
            }
            val thrown = result.exceptionOrNull()
            assertIs<IllegalStateException>(
                thrown,
                "start() must rethrow the accept-loop failure, not return normally",
            )
            assertEquals(error.message, thrown.message)
        }

    /**
     * Cancelling the accept scope must still leave every room already handed to `onRoom` — the leave
     * is *not* optional cleanup.
     *
     * Scope cancellation is this host's whole documented shutdown ([KtorRoomHost.close] is a no-op and
     * says so), and it also arrives on the accept-failure path, where a rethrow out of the loop fails
     * the enclosing `coroutineScope` and cancels every sibling room handler. Either way the per-room
     * `launch` is cancelled while `onRoom` is suspended, so its `finally` runs on an
     * already-cancelled coroutine. [Room.leave] is a suspending call into the transport, so unshielded
     * it throws at that suspension point and the leave never completes: `seam.close` is never reached
     * and the room silently vanishes off the fabric rather than departing, leaving peers to time it
     * out as a transport drop (#2286).
     *
     * **`onRoom` must stay suspended, and the seam's `close` must suspend — or this test is vacuous.**
     * The two other exits from that `try` — `onRoom` returning normally, and `onRoom` throwing a
     * non-cancellation failure into the swallowing `catch` — both reach the `finally` *uncancelled*,
     * where even an unshielded leave works fine. Hence `awaitCancellation()` inside `onRoom`. And the
     * throw comes from the transport's own suspension inside `close`, not from [Room.leave] itself
     * (its `Mutex.withLock` fast path never checks cancellation), so a `FakeSeam` alone — whose
     * `close` never suspends — is green with or without the shield. [SuspendingCloseSeam] supplies the
     * suspension every real transport has: a WebSocket close handshake here, a Multipeer disconnect,
     * an `NwConnection` cancel.
     */
    @Test
    fun `cancelling the accept scope still leaves a room already handed to onRoom`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val seam = SuspendingCloseSeam(FakeSeam(selfId = serverPeerId))
            val host = KtorRoomHost(path = serverPath, pattern = serverPattern, loom = SoleSeamLoom(seam))
            val accepted = CompletableDeferred<Unit>()

            val hostJob = backgroundScope.launch {
                host.start {
                    accepted.complete(Unit)
                    awaitCancellation()
                }
            }
            accepted.await()

            hostJob.cancelAndJoin()

            assertEquals(
                listOf(CloseReason.Normal),
                seam.closes,
                "leave(Normal) must run to completion on the way out of a cancelled accept loop",
            )
        }
}

/** Test stub: always throws [error] from [Loom.weave], simulating an accept-loop failure. */
private class FailingLoom(private val error: Throwable) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = throw error
}

/**
 * A [Loom] that hands out one prepared [Seam] and then never accepts again.
 *
 * The suspend-forever second weave is what a real accept does between connections, and it is load
 * bearing here: [KtorRoomHost.start]'s accept loop is a `while (true)`, so a loom that kept returning
 * seams would spin it, freezing virtual time instead of running the test.
 */
private class SoleSeamLoom(private val seam: Seam) : Loom {
    private var handedOut = false

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        if (handedOut) awaitCancellation()
        handedOut = true
        return seam
    }
}

/**
 * A [Seam] whose `close` suspends *before* it takes effect, and records the reasons it completed for.
 *
 * The suspension stands in for the round trip a real transport's close performs, and it is the only
 * thing that makes an unshielded teardown observable — the recording happens after it, so a `close`
 * that throws at that suspension point records nothing.
 */
private class SuspendingCloseSeam(private val delegate: FakeSeam) : Seam by delegate {
    private val _closes = mutableListOf<CloseReason>()

    /** Reasons for which `close` ran to completion, in call order. */
    val closes: List<CloseReason> get() = _closes.toList()

    override suspend fun close(reason: CloseReason) {
        yield()
        _closes += reason
        delegate.close(reason)
    }
}
