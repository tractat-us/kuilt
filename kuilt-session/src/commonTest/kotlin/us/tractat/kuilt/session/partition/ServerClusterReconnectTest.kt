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
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [ServerClusterReconnect].
 *
 * Tests are structured around four concerns matching the design:
 *  - O3: Deterministic round-robin rotation across endpoints.
 *  - O3/failover: On transport tear, the selector advances to the next endpoint.
 *  - O4: Token re-presentation on reconnect (token survives endpoint change).
 *  - O4/cross-endpoint: Resume presented against a *different* endpoint, not the same one.
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

    private val roomId = RoomId("room-abc")
    private val token = ResumeToken(
        peerId = PeerId("self"),
        roomId = roomId,
        issuedAt = 1_000L,
    )

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

    // ── O4: Token re-presentation ─────────────────────────────────────────────

    @Test
    fun `pendingToken is null before any token is set`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )
        assertNull(reconnect.pendingToken())
    }

    @Test
    fun `pendingToken returns the registered token`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        reconnect.setToken(token)
        assertEquals(token, reconnect.pendingToken())
    }

    @Test
    fun `pendingToken survives endpoint change`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        reconnect.setToken(token)
        reconnect.advanceEndpoint()

        // Token must survive an endpoint rotation — keyed on RoomId, not endpoint.
        assertEquals(token, reconnect.pendingToken())
    }

    @Test
    fun `clearToken clears the pending token`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        reconnect.setToken(token)
        reconnect.clearToken()
        assertNull(reconnect.pendingToken())
    }

    // ── O4: Resume-vs-fresh-join on a different endpoint ─────────────────────

    /**
     * Central S1a contract: after connecting to endpoint-0 and acquiring a token,
     * a transport tear advances to endpoint-1. The same token must be retrievable
     * for re-presentation against the *new* server endpoint — proving that the
     * [ResumeToken.roomId] survives the endpoint change.
     */
    @Test
    fun `token is re-presented after failover to different endpoint`() = runTest {
        val loom0 = InMemoryLoom()
        val loom1 = InMemoryLoom()
        loom0.host(Pattern("server-0"))
        loom1.host(Pattern("server-1"))

        val reconnect = ServerClusterReconnect(
            endpoints = listOf(endpoint0, endpoint1),
        )

        // Connect to endpoint-0 and register a token (simulates post-admit state).
        val seam0 = reconnect.connect(loom0)
        assertNotNull(seam0)
        reconnect.setToken(token)

        // Simulate transport tear: advance to endpoint-1.
        reconnect.onTransportTear()
        assertEquals(endpoint1, reconnect.currentEndpoint())

        // Token still available — re-present to endpoint-1's room.
        val tokenForResume = reconnect.pendingToken()
        assertEquals(token, tokenForResume, "Token must survive failover to endpoint-1")
        assertEquals(roomId, tokenForResume?.roomId, "RoomId must match across endpoints")
    }

    /**
     * After a successful resume, [clearToken] removes the token.
     * A subsequent tear rotates the endpoint and leaves no stale token.
     */
    @Test
    fun `after clearToken second tear leaves no stale token`() = runTest {
        val reconnect = ServerClusterReconnect(
            endpoints = endpoints,
        )

        reconnect.setToken(token)
        reconnect.clearToken() // successful resume consumed the token

        reconnect.onTransportTear()

        assertNull(reconnect.pendingToken(), "No token should remain after clear + tear")
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
