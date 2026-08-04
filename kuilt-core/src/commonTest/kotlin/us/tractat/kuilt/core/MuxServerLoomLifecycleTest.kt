/**
 * Lifecycle contract for [MuxServerLoom] (#1366).
 *
 * [MuxServerLoom] launches an accept loop plus a read/watch pump per accepted connection. Before
 * #1366 those pumps ran in a **parentless** `SupervisorJob`, so cancelling the caller's scope
 * never stopped them and the loom had no `close()` at all — a lifecycle leak. It now adopts
 * [ScopedCloseable], so:
 *
 * - the pump job is a **child** of the injected scope's job (parent cancellation propagates), and
 * - [AutoCloseable.close] tears the loom down: it stops the accept loop, cancels every
 *   per-connection pump, and (via each read loop's teardown) deregisters the connection from the
 *   rooms it joined. Idempotent.
 *
 * Kept in `:kuilt-core` `commonTest` (not driven through
 * [us.tractat.kuilt.conformance.CloseableLifecycleConformanceSuite]) because the job-observation
 * hook [MuxServerLoom.backgroundJobsForTest] is `internal` — reachable only from this module, and
 * `:kuilt-core` cannot depend on `:kuilt-conformance` (that would be a cycle). Uses
 * [StandardTestDispatcher] + virtual time; registration is awaited on observable state, never
 * polled after `advanceUntilIdle`.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuxServerLoomLifecycleTest {

    private fun CoroutineScope.newLoom(
        source: InMemoryConnectionSource,
        dispatcher: CoroutineContext,
        seed: Long = 0L,
    ): MuxServerLoom = MuxServerLoom(
        source = source,
        scope = this,
        selfId = PeerId("server"),
        authorizer = RoomAuthorizer.AllowAll,
        dispatcher = dispatcher,
        random = Random(seed),
    )

    /**
     * Register one client connection into [roomName] on [loom] and return the client's raw seam so
     * the caller can close it. On return the per-connection read/watch pumps are live and the room's
     * membership contains [peer].
     */
    private suspend fun CoroutineScope.admitClient(
        loom: MuxServerLoom,
        source: InMemoryConnectionSource,
        dispatcher: CoroutineContext,
        room: Seam,
        peer: PeerId,
        roomName: String,
        seed: Long,
    ): Seam {
        val (serverConn, clientConn) = connectionPair()
        source.offer(serverConn)
        val seam = hubMesh(peer, listOf(clientConn), dispatcher, Random(seed))
        NamedMux(seam, this).channel(roomName).broadcast(byteArrayOf())
        room.peers.first { peer in it }
        return seam
    }

    @Test
    fun backgroundJobsActiveBeforeClose() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val seam = backgroundScope.admitClient(loom, source, dispatcher, room, PeerId("c1"), "t", seed = 1L)

        val jobs = loom.backgroundJobsForTest
        assertAll(
            { assertTrue(jobs.isNotEmpty(), "loom has launched pumps (accept + per-connection)") },
            { assertTrue(jobs.all { it.isActive }, "all pumps active before close()") },
        )
        seam.close()
    }

    @Test
    fun closeStopsAllBackgroundJobs() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val seam = backgroundScope.admitClient(loom, source, dispatcher, room, PeerId("c1"), "t", seed = 1L)

        loom.close()
        runCurrent()
        assertFalse(
            loom.backgroundJobsForTest.any { it.isActive },
            "no pump remains active after close()",
        )
        seam.close()
    }

    @Test
    fun closeIsIdempotent() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        loom.host(Pattern("t"))

        loom.close()
        loom.close() // must not throw
        assertFalse(
            loom.backgroundJobsForTest.any { it.isActive },
            "pumps remain inactive after double-close()",
        )
    }

    /**
     * Cancelling the **injected** scope's job stops every loom pump — the pump job is a child of it,
     * not a parentless `SupervisorJob`. This is the core #1366 regression: before the fix, parent
     * cancellation left the accept/read/watch loops running forever.
     */
    @Test
    fun parentScopeCancellationStopsAllPumps() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val parentJob = Job(coroutineContext[Job])
        val parentScope = CoroutineScope(coroutineContext + parentJob)
        val source = InMemoryConnectionSource()
        val loom = parentScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val seam = parentScope.admitClient(loom, source, dispatcher, room, PeerId("c1"), "t", seed = 1L)

        assertTrue(loom.backgroundJobsForTest.all { it.isActive }, "pumps active before parent cancel")

        parentJob.cancel()
        runCurrent()
        assertFalse(
            loom.backgroundJobsForTest.any { it.isActive },
            "parent-scope cancellation propagates to every loom pump",
        )
        seam.close()
    }

    /**
     * [MuxServerLoom.connectedPeers] tracks a peer from the moment its link is admitted until the
     * link tears — independent of any room membership. Admitting a client makes it appear; closing
     * its transport makes it disappear.
     */
    @Test
    fun connectedPeersTracksLinkLifecycle() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val peer = PeerId("c1")
        assertTrue(loom.connectedPeers.value.isEmpty(), "no peers connected initially")

        val seam = backgroundScope.admitClient(loom, source, dispatcher, room, peer, "t", seed = 1L)
        loom.connectedPeers.first { peer in it }
        assertTrue(peer in loom.connectedPeers.value, "an admitted peer appears in connectedPeers")

        seam.close()
        loom.connectedPeers.first { peer !in it }
        assertFalse(peer in loom.connectedPeers.value, "a torn link removes the peer from connectedPeers")
    }

    /** [MuxServerLoom.close] clears [MuxServerLoom.connectedPeers] as every connection's pump tears down. */
    @Test
    fun connectedPeersEmptiesOnClose() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val peer = PeerId("c1")
        val seam = backgroundScope.admitClient(loom, source, dispatcher, room, peer, "t", seed = 1L)
        loom.connectedPeers.first { peer in it }

        loom.close()
        loom.connectedPeers.first { it.isEmpty() }
        assertFalse(peer in loom.connectedPeers.value, "close() clears connectedPeers")
        seam.close()
    }

    /**
     * On [MuxServerLoom.close] each connection's read loop tears down, deregistering it from the
     * rooms it joined — so the room's membership no longer lists the peer.
     */
    @Test
    fun closeDeregistersConnectionsFromRooms() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern("t"))
        val peer = PeerId("c1")
        val seam = backgroundScope.admitClient(loom, source, dispatcher, room, peer, "t", seed = 1L)
        assertTrue(peer in room.peers.value, "peer registered before close")

        loom.close()
        runCurrent()
        assertFalse(peer in room.peers.value, "close() deregisters the connection from its room")
        seam.close()
    }
}
