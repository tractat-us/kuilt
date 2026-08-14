@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-(a) tests for the [ServerClusterReconnect] endpoint-rotation policy
 * as it will be used by [ClusterClient].
 *
 * Tests are in `:kuilt-cluster` (not `:kuilt-session`) because they verify the
 * *policy* of how ClusterClient drives reconnect — specifically: round-robin order
 * on failover, and the terminal-refusal→fresh-join fallback (proven by #532).
 *
 * All tests run under [StandardTestDispatcher] with a tight 5s timeout.
 */
class EndpointRotationTest {

    private val endpointA = InMemoryTag("server-a")
    private val endpointB = InMemoryTag("server-b")
    private val endpointC = InMemoryTag("server-c")

    @Test
    fun `initial endpoint is index 0 with round-robin starting at 0`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val endpoints = listOf(endpointA, endpointB, endpointC)
            val reconnect = ServerClusterReconnect(
                endpoints = endpoints,
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )
            assertEquals(endpointA, reconnect.currentEndpoint())
        }

    @Test
    fun `onTransportTear advances to next endpoint in round-robin order`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val endpoints = listOf(endpointA, endpointB, endpointC)
            val reconnect = ServerClusterReconnect(
                endpoints = endpoints,
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )

            // Initial: A
            assertEquals(endpointA, reconnect.currentEndpoint())

            // Tear 1: A → B
            reconnect.onTransportTear()
            assertEquals(endpointB, reconnect.currentEndpoint())

            // Tear 2: B → C
            reconnect.onTransportTear()
            assertEquals(endpointC, reconnect.currentEndpoint())

            // Tear 3: wraps around C → A
            reconnect.onTransportTear()
            assertEquals(endpointA, reconnect.currentEndpoint())
        }

    @Test
    fun `ResumeToken survives endpoint rotation and is re-presented to next server`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val endpoints = listOf(endpointA, endpointB)
            val reconnect = ServerClusterReconnect(
                endpoints = endpoints,
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )

            // Client connects to server-A and registers its token.
            val token = ResumeToken(
                peerId = PeerId("client-peer"),
                roomId = RoomId("room-1"),
                issuedAt = 1000L,
            )
            reconnect.setToken(token)
            assertNotNull(reconnect.pendingToken(), "token registered on server-A")

            // Entry-server tears — advance to server-B.
            reconnect.onTransportTear()
            assertEquals(endpointB, reconnect.currentEndpoint())

            // The token survives endpoint rotation (keyed on RoomId, not server identity).
            assertEquals(token, reconnect.pendingToken(), "token must outlive endpoint rotation")
        }

    @Test
    fun `a terminal cross-server refusal is a fresh-join signal not an error`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // This test encodes the ClusterClient's required policy from the O4/#532 finding:
            // presenting a token from server-A to server-B is always terminally refused, because
            // server-B mints its own RoomId and keeps its reconnect windows in memory. The real
            // wire measurement lives in :examples' ResumeTokenFailoverTest; this models the policy
            // the client applies to it.
            //
            // ClusterClient must handle it as "fall back to fresh join" — not as an error.

            val result: ResumeResult.JoinerOutcome =
                ResumeResult.Refused("resume-token-invalid: session-mismatch", RejectCode.ResumeTokenInvalid)

            // Terminal is the operative property: a retryable refusal would mean "try the same
            // endpoint again", which across servers can never work.
            assertFalse(assertIs<ResumeResult.Refused>(result).code.retryable)

            // After treating the refusal as fresh-join, the token should be cleared
            // (it was from the old server and will never succeed on the new one).
            // This models the ClusterClient's post-failover cleanup contract.
            val reconnect = ServerClusterReconnect(
                endpoints = listOf(endpointA, endpointB),
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )
            reconnect.setToken(
                ResumeToken(
                    peerId = PeerId("client"),
                    roomId = RoomId("room-2"),
                    issuedAt = 2000L,
                ),
            )

            // Simulate the ClusterClient's refusal handling: clear the stale token.
            reconnect.clearToken()

            assertNull(reconnect.pendingToken(), "token must be cleared after refusal / fresh-join")
        }

    @Test
    fun `seeded selector produces deterministic rotation order`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val endpoints = listOf(endpointA, endpointB, endpointC)

            // Two reconnect helpers with the same seeded selector must produce identical order.
            fun makeReconnect() = ServerClusterReconnect(
                endpoints = endpoints,
                selector = RoundRobinEndpointSelector(startIndex = 1),
            )

            val r1 = makeReconnect()
            val r2 = makeReconnect()

            repeat(6) { step ->
                assertEquals(r1.currentEndpoint(), r2.currentEndpoint(), "rotation order must match at step $step")
                r1.onTransportTear()
                r2.onTransportTear()
            }
        }
}
