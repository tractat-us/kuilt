/**
 * Server-side reconnect/resume re-association by [PeerId] for [MuxServerLoom] / [RoomHubSeam].
 *
 * The structural-isolation gate (per-room fanout, per-room teardown, authorizer rejection) is
 * verified by the reusable [us.tractat.kuilt.conformance.RoomFanoutIsolationConformanceSuite];
 * this file covers only the reconnect concern that suite does not.
 *
 * Uses [StandardTestDispatcher] + virtual time; registration is awaited on observable state
 * (`peers.first { … }`) rather than polled after `advanceUntilIdle`, so the data path is driven
 * deterministically.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies reconnect/resume re-association of [MuxServerLoom].
 */
class RoomHubSeamMembershipTest {

    /**
     * Server-side reconnect/resume by [PeerId]: a peer registers in table-7, its connection drops,
     * then it reconnects over a NEW connection with the SAME [PeerId], re-emits its tag, and lands
     * back in table-7's fanout (receives a subsequent broadcast) and [RoomHubSeam.peers]. Membership
     * is re-associated by id, never duplicated.
     */
    @Test
    fun reconnectResumesMembershipByPeerId() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(13L),
        )
        val serverRoom7 = serverLoom.host(Pattern("table-7"))
        val peer = PeerId("client-resumer")

        // First connection: join table-7.
        val (server1, client1) = connectionPair()
        source.offer(server1)
        val seam1 = meshSeam(peer, listOf(client1), dispatcher, Random(1L))
        val mux1 = NamedMux(seam1, backgroundScope)
        mux1.channel("table-7").broadcast(byteArrayOf())
        serverRoom7.peers.first { it.contains(peer) }
        assertEquals(setOf(peer), serverRoom7.peers.value, "peer registered on first connection")

        // Drop the first connection.
        seam1.close()
        client1.close()

        // Reconnect over a fresh connection with the SAME PeerId; re-emit the tag.
        val (server2, client2) = connectionPair()
        source.offer(server2)
        val seam2 = meshSeam(peer, listOf(client2), dispatcher, Random(2L))
        val mux2 = NamedMux(seam2, backgroundScope)
        mux2.channel("table-7").broadcast(byteArrayOf())

        // Await re-association on the resumed connection (membership re-keyed, not duplicated).
        serverRoom7.peers.first { it.contains(peer) }

        val payload = byteArrayOf(9, 9)
        serverRoom7.broadcast(payload)

        val frame = withTimeout(1.seconds) { mux2.channel("table-7").incoming.first() }

        assertAll(
            { assertTrue(frame.toByteArray().contentEquals(payload), "resumed connection must receive the broadcast") },
            { assertEquals(setOf(peer), serverRoom7.peers.value, "membership re-associated by PeerId, not duplicated") },
        )
    }
}
