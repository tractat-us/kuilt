package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Integration test that empirically answers the O4 design question:
 *
 * > **Does `ResumeToken`-based resume survive a real entry-server CHANGE, or does it
 * > degrade to a fresh-join when the new endpoint is a different server that never
 * > issued the token?**
 *
 * ## Answer: **degrades to fresh-join**
 *
 * `ResumeToken` is keyed on [us.tractat.kuilt.session.partition.RoomId], not on any
 * server identity, so [ServerClusterReconnect] retains the token across endpoint rotation —
 * the client side is correct. However, the server side cannot honour the token:
 *
 * - Each `KtorRoomHost` creates a fresh [SeamRoomFactory] and therefore a fresh
 *   [us.tractat.kuilt.session.SeamRoom] per accepted connection.
 * - Each `SeamRoom` owns a `DefaultJoinerReconnectController` whose reconnect window
 *   registry (`windows` map) is **in-memory and per-room-instance**.
 * - Server-B has no window entry for the client's `PeerId` — it was never the entry
 *   server. `tryResume` returns `WindowClosed` immediately.
 *
 * [Room.resume] therefore returns [ResumeResult.WindowClosed] when the client presents
 * the token from server-A to server-B. This is the characterisation this test proves.
 *
 * ## Implication for S3 ClusterClient
 *
 * The `ClusterClient.connect`-on-failover code path must treat `ResumeResult.WindowClosed`
 * as a signal to fall back to a fresh join (re-admit handshake), not as an error. Resume
 * is a **best-effort optimisation** that succeeds only when the reconnect stays on the
 * same entry server within its reconnect window. Cross-endpoint failover always requires
 * a full re-join.
 *
 * See issue [#532](https://github.com/tractat-us/kuilt/issues/532).
 *
 * ## Test structure (mirrors S1b — [RelayRoomTest])
 *
 * Two [KtorRoomHost]s are mounted at different paths on the same `testApplication`.
 * A single [KtorClientLoom] connects first to server-A, completes the admit handshake,
 * and captures its `resumeToken`. The server-A accept loop is then cancelled (simulating
 * entry-server death). The client round-robins to server-B via [ServerClusterReconnect],
 * opens a new connection, and attempts [us.tractat.kuilt.session.Room.resume] with the
 * token from server-A.
 */
class ResumeTokenFailoverTest {

    private val serverPeerIdA = PeerId("relay-server-a")
    private val serverPeerIdB = PeerId("relay-server-b")
    private val pathA = "/ws/relay-a"
    private val pathB = "/ws/relay-b"
    private val roomPattern = Pattern("failover-test-room")

    @Test
    fun `resume token from server-A degrades to WindowClosed on server-B — fresh join required`() =
        testApplication {
            val hostA = KtorRoomHost(
                application = application,
                path = pathA,
                serverPeerId = serverPeerIdA,
                pattern = roomPattern,
            )
            val hostB = KtorRoomHost(
                application = application,
                path = pathB,
                serverPeerId = serverPeerIdB,
                pattern = roomPattern,
            )

            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            coroutineScope {
                // ── Step 1: client connects to server-A and captures its ResumeToken ─

                // Scope for the server-A accept loop. Cancelling it simulates entry-server death.
                val serverAScope = CoroutineScope(coroutineContext + Job())
                val serverAJob = serverAScope.launch {
                    hostA.start { room ->
                        // Server-A accepts one connection and then stays alive (held by the scope)
                        // until we cancel the scope below.
                        withTimeout(15.seconds) {
                            room.roster.first { it.isNotEmpty() }
                        }
                        // Hold the room open until the scope is cancelled.
                        serverAScope.coroutineContext[Job]!!.join()
                    }
                }

                // Client scope for the initial server-A connection.
                val clientScopeA = CoroutineScope(coroutineContext + Job())
                val roomA = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScopeA)
                    .join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$pathA",
                            serverPeerId = serverPeerIdA,
                            displayName = "failover-client",
                        )
                    )

                // Wait for the admit handshake to complete (roster is non-empty on client side).
                withTimeout(10.seconds) { roomA.roster.first { it.isNotEmpty() } }

                // Capture the resume token — set after the host's Welcome frame is received.
                val token = withTimeout(5.seconds) {
                    var t = roomA.resumeToken
                    while (t == null) {
                        kotlinx.coroutines.delay(10)
                        t = roomA.resumeToken
                    }
                    t
                }
                assertNotNull(token, "client must hold a ResumeToken after connecting to server-A")

                // Register the token in the reconnect helper (server-A is endpoint 0, server-B is endpoint 1).
                val endpointA = WebSocketAdvertisement(
                    url = "ws://localhost$pathA",
                    serverPeerId = serverPeerIdA,
                    displayName = "failover-client",
                )
                val endpointB = WebSocketAdvertisement(
                    url = "ws://localhost$pathB",
                    serverPeerId = serverPeerIdB,
                    displayName = "failover-client",
                )
                val reconnect = ServerClusterReconnect(
                    endpoints = listOf(endpointA, endpointB),
                    selector = RoundRobinEndpointSelector(startIndex = 0),
                )
                reconnect.setToken(token)

                // ── Step 2: kill server-A ─────────────────────────────────────────

                // Tear down the client's server-A room and cancel server-A's accept loop.
                clientScopeA.cancel()
                serverAJob.cancel()
                serverAScope.cancel()

                // ── Step 3: round-robin to server-B ──────────────────────────────

                reconnect.onTransportTear()

                // ── Step 4: start server-B accept loop ───────────────────────────

                val serverBJob = launch {
                    hostB.start { room ->
                        // Server-B accepts the connection and holds it long enough for the
                        // resume attempt below to complete.
                        withTimeout(15.seconds) {
                            room.roster.first { it.isNotEmpty() }
                        }
                        // Room can close after the admit handshake — we only need it up
                        // for the resume attempt, which happens on the client side.
                        withTimeout(10.seconds) {
                            // Stay open briefly so the client's resume() send reaches us.
                            kotlinx.coroutines.delay(3_000)
                        }
                    }
                }

                // ── Step 5: client connects to server-B and attempts resume ───────

                val clientScopeB = CoroutineScope(coroutineContext + Job())
                val roomB = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScopeB)
                    .join(reconnect.currentEndpoint())

                // Wait for the admit handshake on server-B to complete (fresh join).
                withTimeout(10.seconds) { roomB.roster.first { it.isNotEmpty() } }

                // Present the token from server-A. Server-B has no reconnect window for this
                // client's PeerId — it was never the entry server.
                val pendingToken = reconnect.pendingToken()
                assertNotNull(pendingToken, "reconnect must still hold the token after endpoint rotation")
                val resumeResult = withTimeout(10.seconds) {
                    roomB.resume(pendingToken)
                }

                // ── Step 6: assert the design finding ────────────────────────────

                // The resume DEGRADES to WindowClosed: the token is structurally valid
                // (correct RoomId) but server-B's JoinerReconnectController has no window
                // state for this peer's PeerId. Fresh join (the admit handshake above) is
                // the correct fallback path.
                assertIs<ResumeResult.WindowClosed>(
                    resumeResult,
                    "ResumeToken from server-A must return WindowClosed on server-B: " +
                        "each server's JoinerReconnectController is in-memory and per-host-room. " +
                        "ClusterClient.connect-on-failover must fall back to a fresh join.",
                )

                // ── Teardown ──────────────────────────────────────────────────────
                clientScopeB.cancel()
                serverBJob.cancel()
            }
        }
}
