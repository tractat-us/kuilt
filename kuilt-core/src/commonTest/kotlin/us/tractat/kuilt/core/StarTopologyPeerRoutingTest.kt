/**
 * Does a hub/star fabric give a joiner a route to **another joiner**? (#1576)
 *
 * [SeamRoom][us.tractat.kuilt.session.SeamRoom] starts a per-peer liveness detector for every
 * admitted member, and that detector pings via [Seam.sendTo]. On a mesh that is sound — every
 * member has a direct link to every other. On a host-relayed star it is only sound if the fabric
 * **relays peer-addressed frames** between spokes. #1576 asks which side each shipped fabric
 * falls on; this test answers it for the [MuxServerLoom] hub, which is the star fabric in
 * `:kuilt-core`.
 *
 * The answer is **no route**, and it is structural rather than incidental:
 *
 * - [MuxServerLoom]'s read loop forwards every demuxed spoke frame into the matching
 *   [RoomHubSeam] via `deliver`, which spools it to the **host's own** `incoming`. There is no
 *   spoke→spoke forwarding path anywhere in the hub.
 * - A joiner's own seam is a 2-peer link to the server, so its `peers` is `{self, server}` and a
 *   peer-addressed send to another joiner has no registered target at all.
 *
 * So a joiner's heartbeat ping to a co-joiner is not merely dropped in transit — it cannot be
 * addressed in the first place. Because [HeartbeatPartitionDetector][us.tractat.kuilt.liveness.HeartbeatPartitionDetector]'s
 * timeout branch is **not** gated on the peer being present in `link.peers`, silence from an
 * unroutable peer still matures into `PeerUnresponsive(Timeout)` → `PeerLost` — evicting a
 * perfectly healthy member. That is the spurious eviction #1576 predicted.
 *
 * These tests pin the routing fact only. They deliberately do **not** assert the eviction
 * behaviour, which belongs to `:kuilt-session` and is what the fix will change.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StarTopologyPeerRoutingTest {

    private val roomName = "table"
    private val hostId = PeerId("server")
    private val joinerA = PeerId("joiner-a")
    private val joinerB = PeerId("joiner-b")

    /** One admitted spoke: its raw link seam plus the room-channel view the app would hold. */
    private class Spoke(val raw: Seam, val channel: Seam)

    private fun CoroutineScope.newLoom(
        source: InMemoryConnectionSource,
        dispatcher: CoroutineContext,
    ): MuxServerLoom = MuxServerLoom(
        source = source,
        scope = this,
        selfId = hostId,
        authorizer = RoomAuthorizer.AllowAll,
        dispatcher = dispatcher,
        random = Random(0L),
    )

    /**
     * Connect [peer] to the hub and register it into [room] by sending its first frame on the
     * room channel. Returns once the hub's roster contains [peer].
     */
    private suspend fun CoroutineScope.admit(
        source: InMemoryConnectionSource,
        dispatcher: CoroutineContext,
        room: Seam,
        peer: PeerId,
        seed: Long,
    ): Spoke {
        val (serverConn, clientConn) = connectionPair()
        source.offer(serverConn)
        val raw = hubMesh(peer, listOf(clientConn), dispatcher, Random(seed))
        val channel = NamedMux(raw, this).channel(roomName)
        channel.broadcast(byteArrayOf())
        room.peers.first { peer in it }
        return Spoke(raw, channel)
    }

    /**
     * The headline: joiner A cannot address joiner B. A's link knows only itself and the host, so
     * the peer-addressed send fails outright with [PeerNotConnected] — there is no route for a
     * heartbeat ping to travel, relayed or otherwise.
     */
    @Test
    fun joinerCannotAddressACoJoiner() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern(roomName))

        val a = backgroundScope.admit(source, dispatcher, room, joinerA, seed = 1L)
        val b = backgroundScope.admit(source, dispatcher, room, joinerB, seed = 2L)

        val thrown = assertFailsWith<PeerNotConnected> {
            a.channel.sendTo(joinerB, "ping".encodeToByteArray())
        }

        assertAll(
            { assertEquals(joinerB, thrown.peer, "the unroutable target is the co-joiner") },
            {
                assertEquals(
                    setOf(joinerA, hostId),
                    a.channel.peers.value,
                    "a spoke's link knows only itself and the host — never a co-joiner",
                )
            },
            {
                assertTrue(
                    joinerB in room.peers.value,
                    "yet the hub's roster does contain the co-joiner, so a roster-driven " +
                        "detector would happily be started for an unroutable peer",
                )
            },
        )

        a.raw.close()
        b.raw.close()
    }

    /**
     * The control, and the reason the failure above is a *routing* fact and not a broken harness:
     * the same channel reaches the **host** fine. Only the spoke→spoke direction has no route, and
     * a frame sent by A lands in the host's `incoming` rather than being relayed onward to B.
     */
    @Test
    fun spokeFramesReachOnlyTheHostNeverAnotherSpoke() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val loom = backgroundScope.newLoom(source, dispatcher)
        val room = loom.host(Pattern(roomName))

        val a = backgroundScope.admit(source, dispatcher, room, joinerA, seed = 1L)
        val b = backgroundScope.admit(source, dispatcher, room, joinerB, seed = 2L)

        val hostSeen = mutableListOf<Swatch>()
        val bSeen = mutableListOf<Swatch>()
        backgroundScope.launch { room.incoming.toList(hostSeen) }
        backgroundScope.launch { b.channel.incoming.toList(bSeen) }
        runCurrent()

        val payload = "from-a"
        a.channel.broadcast(payload.encodeToByteArray())
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(payload),
                    hostSeen.map { it.decodeToString() }.filter { it.isNotEmpty() },
                    "the host receives the spoke's frame",
                )
            },
            {
                assertTrue(
                    bSeen.none { it.decodeToString() == payload },
                    "the co-joiner never sees it — the hub does not relay spoke→spoke",
                )
            },
        )

        a.raw.close()
        b.raw.close()
    }
}
