@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tier-(a) tests for the [ServerClusterReconnect] endpoint-rotation policy
 * as it will be used by [ClusterClient].
 *
 * Tests are in `:kuilt-cluster` (not `:kuilt-session`) because they verify the
 * *policy* of how ClusterClient drives reconnect: round-robin order on failover. A cross-server
 * reconnect is always a fresh join (#532): the next server holds no seat for the client's key, and
 * since #3215 there is no token to carry across.
 *
 * All tests run under [StandardTestDispatcher] with a tight 5s timeout.
 */
class EndpointRotationTest {

    private val endpointA = InMemoryTag("server-a")
    private val endpointB = InMemoryTag("server-b")
    private val endpointC = InMemoryTag("server-c")

    @Test
    fun `initial endpoint is index 0 with round-robin starting at 0`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val endpoints = listOf(endpointA, endpointB, endpointC)
            val reconnect = ServerClusterReconnect(
                endpoints = endpoints,
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )
            assertEquals(endpointA, reconnect.currentEndpoint())
        }

    @Test
    fun `onTransportTear advances to next endpoint in round-robin order`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
    fun `seeded selector produces deterministic rotation order`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
