/**
 * Isolation gate for [MuxServerLoom] / [RoomHubSeam]:
 * a broadcast on channel "table-7" reaches only the connections that joined "table-7",
 * never those on "table-9". Isolation is structural — the non-member is never in the
 * fanout list, so a leak is unrepresentable.
 *
 * Uses [StandardTestDispatcher] + virtual time for deterministic ordering.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
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
 * Verifies structural per-room isolation: [MuxServerLoom] + [RoomHubSeam] guarantee that
 * a frame broadcast on room "table-7" reaches only its members.
 */
class RoomHubSeamIsolationTest {

    /**
     * Core isolation gate: clients A and B on "table-7"; client C on "table-9".
     * A broadcast on "table-7" is observed by B and NEVER by C. The assertion on C is
     * structural: C's inbox has zero frames — it was never in the "table-7" fanout list.
     */
    @Test
    fun broadcastOnRoomReachesOnlyRoomMembers() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(42L),
        )

        // Host both rooms before connections arrive.
        val serverRoom7 = serverLoom.host(Pattern("table-7"))
        val serverRoom9 = serverLoom.host(Pattern("table-9"))

        // Connect client A (table-7).
        val (aServerConn, aClientConn) = connectionPair()
        source.offer(aServerConn)
        val clientA = async {
            meshSeam(PeerId("client-a"), listOf(aClientConn), dispatcher, Random(1L))
        }

        // Connect client B (table-7).
        val (bServerConn, bClientConn) = connectionPair()
        source.offer(bServerConn)
        val clientB = async {
            meshSeam(PeerId("client-b"), listOf(bClientConn), dispatcher, Random(2L))
        }

        // Connect client C (table-9 only).
        val (cServerConn, cClientConn) = connectionPair()
        source.offer(cServerConn)
        val clientC = async {
            meshSeam(PeerId("client-c"), listOf(cClientConn), dispatcher, Random(3L))
        }

        val seamA = clientA.await()
        val seamB = clientB.await()
        val seamC = clientC.await()

        val muxA = NamedMux(seamA, backgroundScope)
        val muxB = NamedMux(seamB, backgroundScope)
        val muxC = NamedMux(seamC, backgroundScope)

        // Signal room membership: clients send their first frame on the room's channel.
        muxA.channel("table-7").broadcast(byteArrayOf())
        muxB.channel("table-7").broadcast(byteArrayOf())
        muxC.channel("table-9").broadcast(byteArrayOf())

        // Await registration on an observable (deterministic) rather than polling after
        // advanceUntilIdle: the server admits A and B into table-7, C into table-9.
        serverRoom7.peers.first { it.size == 2 }
        serverRoom9.peers.first { it.size == 1 }

        // C starts collecting on table-7 BEFORE the broadcast (so it can't miss frames).
        val cTable7Inbox = muxC.channel("table-7").incoming.produceIn(backgroundScope)

        // Server broadcasts on table-7.
        val payload = byteArrayOf(1, 2, 3)
        serverRoom7.broadcast(payload)
        testScheduler.advanceUntilIdle()

        // B must receive the frame.
        val bFrame = withTimeout(1.seconds) {
            muxB.channel("table-7").incoming.first()
        }
        assertTrue(bFrame.toByteArray().contentEquals(payload), "B must receive the table-7 broadcast")

        // C must NOT receive anything on table-7 — it was never in the fanout.
        assertTrue(cTable7Inbox.isEmpty, "C must receive ZERO frames on table-7 (structural isolation)")

        // table-9 still works: C receives its own room's broadcast.
        val cPayload = byteArrayOf(9, 8, 7)
        serverRoom9.broadcast(cPayload)
        testScheduler.advanceUntilIdle()
        val cFrame = withTimeout(1.seconds) {
            muxC.channel("table-9").incoming.first()
        }
        assertTrue(cFrame.toByteArray().contentEquals(cPayload), "C must receive the table-9 broadcast")
    }

    /**
     * [RoomHubSeam.peers] reflects only the room's registered members.
     * "table-7" sees A and B; "table-9" sees only C.
     */
    @Test
    fun perRoomPeersReflectsOnlyRoomMembers() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(99L),
        )

        val serverRoom7 = serverLoom.host(Pattern("table-7"))
        val serverRoom9 = serverLoom.host(Pattern("table-9"))

        suspend fun connectClient(id: String, rng: Random): NamedMux {
            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val seam = meshSeam(PeerId(id), listOf(clientConn), dispatcher, rng)
            return NamedMux(seam, backgroundScope)
        }

        val muxA = connectClient("client-a", Random(1L))
        val muxB = connectClient("client-b", Random(2L))
        val muxC = connectClient("client-c", Random(3L))

        muxA.channel("table-7").broadcast(byteArrayOf())
        muxB.channel("table-7").broadcast(byteArrayOf())
        muxC.channel("table-9").broadcast(byteArrayOf())

        // Wait for all registrations to propagate.
        testScheduler.advanceUntilIdle()

        // Allow peers state-flows to settle.
        val room7Peers = serverRoom7.peers.first { it.size >= 2 }
        val room9Peers = serverRoom9.peers.first { it.isNotEmpty() }

        assertAll(
            { assertEquals(2, room7Peers.size, "table-7 must have exactly 2 peers; got $room7Peers") },
            { assertEquals(1, room9Peers.size, "table-9 must have exactly 1 peer; got $room9Peers") },
        )
    }

    /**
     * Closing room "table-7" does not drop room "table-9" or prevent further broadcasts on it.
     */
    @Test
    fun closingOneRoomDoesNotAffectSibling() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(7L),
        )

        val serverRoom7 = serverLoom.host(Pattern("table-7"))
        val serverRoom9 = serverLoom.host(Pattern("table-9"))

        val (cServerConn, cClientConn) = connectionPair()
        source.offer(cServerConn)
        val seamC = meshSeam(PeerId("client-c"), listOf(cClientConn), dispatcher, Random(3L))
        val muxC = NamedMux(seamC, backgroundScope)
        muxC.channel("table-9").broadcast(byteArrayOf())

        // Await C's registration in table-9 deterministically.
        serverRoom9.peers.first { it.size == 1 }

        // Close table-7 — table-9 must remain usable.
        serverRoom7.close()

        val cPayload = byteArrayOf(42, 43)
        serverRoom9.broadcast(cPayload)

        val cFrame = withTimeout(1.seconds) {
            muxC.channel("table-9").incoming.first()
        }
        assertTrue(cFrame.toByteArray().contentEquals(cPayload), "table-9 must work after table-7 is closed")
    }
}
