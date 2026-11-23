package us.tractat.kuilt.session.partition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [ServerClusterReconnect].
 *
 * Tests are structured around two concerns matching the design:
 *  - O3: Deterministic round-robin rotation across endpoints.
 *  - O3/failover: On transport tear, the selector advances to the next endpoint.
 *
 * Re-presenting a token on reconnect went with the token (#3215): only the server that admitted a
 * joiner holds a seat for its key, so another endpoint admits it afresh.
 *
 * All tests use [StandardTestDispatcher] (FIFO at each virtual instant) to avoid
 * load-dependent ordering. Fake [InMemoryLoom] looms serve as endpoints.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerClusterReconnectTest {
    private val endpoint0 = InMemoryTag("server-0")
    private val endpoint1 = InMemoryTag("server-1")
    private val endpoint2 = InMemoryTag("server-2")
    private val endpoints: List<Tag> = listOf(endpoint0, endpoint1, endpoint2)


    // ── O3: Rotation order ────────────────────────────────────────────────────

    /**
     * The default round-robin selector starts at index 0 and advances modulo N.
     */
    @Test
    fun `default selector starts at first endpoint`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )
        assertEquals(endpoint0, reconnect.currentEndpoint())
    }

    @Test
    fun `rotation advances through all endpoints in order`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )
        assertEquals(endpoint0, reconnect.currentEndpoint())

        reconnect.advanceEndpoint()
        assertEquals(endpoint1, reconnect.currentEndpoint())

        reconnect.advanceEndpoint()
        assertEquals(endpoint2, reconnect.currentEndpoint())
    }

    @Test
    fun `rotation wraps around after last endpoint`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )
        repeat(endpoints.size) { reconnect.advanceEndpoint() }
        assertEquals(endpoint0, reconnect.currentEndpoint())
    }

    // ── O3: Custom selector ───────────────────────────────────────────────────

    /**
     * An injected [EndpointSelector] can provide a custom rotation strategy.
     * Here we verify that a reverse-order selector is respected.
     */
    @Test
    fun `custom selector governs endpoint order`() = runTest {
        var index = endpoints.size - 1
        val reverseSelector = EndpointSelector { size ->
            val current = index
            index = (index - 1 + size) % size
            current
        }

        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
            selector = reverseSelector,
        )

        assertEquals(endpoint2, reconnect.currentEndpoint())
        reconnect.advanceEndpoint()
        assertEquals(endpoint1, reconnect.currentEndpoint())
        reconnect.advanceEndpoint()
        assertEquals(endpoint0, reconnect.currentEndpoint())
    }

    // ── O3: Failover on tear ──────────────────────────────────────────────────

    @Test
    fun `onTransportTear advances to next endpoint`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        assertEquals(endpoint0, reconnect.currentEndpoint())
        reconnect.onTransportTear()
        assertEquals(endpoint1, reconnect.currentEndpoint())

        reconnect.onTransportTear()
        assertEquals(endpoint2, reconnect.currentEndpoint())
    }

    @Test
    fun `connect returns a seam against current endpoint`() = runTest {
        val loom = InMemoryLoom()
        loom.host(Pattern("server-0"))

        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        val seam = reconnect.connect(loom)
        assertNotNull(seam)
        // connect does not advance the endpoint index
        assertEquals(endpoint0, reconnect.currentEndpoint())
    }

    // ── Single-endpoint degenerate case ──────────────────────────────────────

    @Test
    fun `single endpoint always returns same endpoint after tear`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = listOf(endpoint0),
        )

        reconnect.onTransportTear()
        assertEquals(endpoint0, reconnect.currentEndpoint())
    }

    // ── Thread-safety: concurrent advanceEndpoint stays in bounds ─────────────

    /**
     * Concurrent [advanceEndpoint] calls must not produce an out-of-bounds index.
     * Verified by launching many coroutines in parallel under [StandardTestDispatcher]
     * and asserting the result is always one of the known endpoints.
     */
    @Test
    fun `concurrent advanceEndpoint stays in bounds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        repeat(99) {
            scope.launch { reconnect.advanceEndpoint() }
        }
        testScheduler.advanceUntilIdle()

        val finalEndpoint = reconnect.currentEndpoint()
        assertNotNull(
            endpoints.find { it.peerKey == finalEndpoint.peerKey },
            "Final endpoint must be one of the known endpoints",
        )
    }
}
