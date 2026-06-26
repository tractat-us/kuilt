/**
 * Membership gates for [MuxServerLoom] / [RoomHubSeam] beyond the core isolation suite
 * ([RoomHubSeamIsolationTest]): the authorizer's structural rejection, and server-side
 * reconnect/resume re-association by [PeerId].
 *
 * Uses [StandardTestDispatcher] + virtual time; registration is awaited on observable state
 * (`peers.first { … }`) rather than polled after `advanceUntilIdle`, so the data path is driven
 * deterministically.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
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
 * Verifies the authorizer gate and reconnect/resume re-association of [MuxServerLoom].
 */
class RoomHubSeamMembershipTest {

    /**
     * A connection the authorizer rejects for "table-7" is structurally excluded: it never
     * appears in [RoomHubSeam.peers] and observes ZERO frames on table-7. A second, admitted
     * connection on the same room still works.
     */
    @Test
    fun rejectedConnectionIsStructurallyExcluded() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()

        val rejectedPeer = PeerId("client-rejected")
        // Authorize everyone for table-7 EXCEPT the rejected peer.
        val authorizer = RoomAuthorizer { peer, tag ->
            !(tag == "table-7" && peer == rejectedPeer)
        }
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = authorizer,
            dispatcher = dispatcher,
            random = Random(11L),
        )
        val serverRoom7 = serverLoom.host(Pattern("table-7"))

        // Admitted connection.
        val (okServerConn, okClientConn) = connectionPair()
        source.offer(okServerConn)
        val okSeam = meshSeam(PeerId("client-ok"), listOf(okClientConn), dispatcher, Random(1L))
        val okMux = NamedMux(okSeam, backgroundScope)

        // Rejected connection.
        val (noServerConn, noClientConn) = connectionPair()
        source.offer(noServerConn)
        val noSeam = meshSeam(rejectedPeer, listOf(noClientConn), dispatcher, Random(2L))
        val noMux = NamedMux(noSeam, backgroundScope)

        // Both clients try to join table-7.
        okMux.channel("table-7").broadcast(byteArrayOf())
        noMux.channel("table-7").broadcast(byteArrayOf())

        // Await the admitted peer's registration; the rejected one must never register.
        serverRoom7.peers.first { it.contains(PeerId("client-ok")) }

        // Rejected client begins collecting BEFORE the broadcast so it cannot merely miss a frame.
        val rejectedInbox = noMux.channel("table-7").incoming.produceIn(backgroundScope)

        val payload = byteArrayOf(7, 7, 7)
        serverRoom7.broadcast(payload)

        val okFrame = withTimeout(1.seconds) { okMux.channel("table-7").incoming.first() }

        assertAll(
            { assertTrue(okFrame.toByteArray().contentEquals(payload), "admitted client must receive the broadcast") },
            { assertTrue(rejectedInbox.isEmpty, "rejected client must receive ZERO frames on table-7") },
            { assertEquals(setOf(PeerId("client-ok")), serverRoom7.peers.value, "only the admitted peer is in table-7") },
        )
    }

    /**
     * Server-side reconnect/resume by [PeerId]: a peer registers in table-7, its connection drops,
     * then it reconnects over a NEW connection with the SAME [PeerId], re-emits its tag, and lands
     * back in table-7's fanout (receives a subsequent broadcast) and [RoomHubSeam.peers]. Membership
     * is re-associated by id, never duplicated.
     */
    @Test
    fun reconnectResumesMembershipByPeerId() = runTest(StandardTestDispatcher()) {
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
