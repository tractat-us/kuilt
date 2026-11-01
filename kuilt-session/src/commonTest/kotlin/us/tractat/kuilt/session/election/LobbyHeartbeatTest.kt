@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.election

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A lobby is alive for as long as it can answer, whatever its role and however long its user takes
 * (#2995).
 *
 * Only a [HeartbeatPartitionDetector] used to answer a ping, and a host creates its detectors inside
 * `start()`. So an elected host waiting for its user's Start tap answered nobody, and each member
 * waiting in `awaitRoom` was torn as unreachable about 6 s later.
 */
class LobbyHeartbeatTest {
    private val ping = HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()

    private fun TestScope.lobbyOver(seam: Seam): SeamElectionLobby {
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        return SeamElectionLobby(
            seam = seam,
            factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock = clock),
            scope = backgroundScope,
            clock = clock,
            roomKey = null,
        )
    }

    private fun FakeSeam.framesTo(peer: PeerId, prefix: String): Int =
        directed.count { (to, bytes) -> to == peer && bytes.decodeToString().startsWith(prefix) }

    /** The issue's headline, over a real mesh: a host that takes its time keeps its members. */
    @Test
    fun `a member outlasts a live host that has not called start`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val hostLobby = lobbyOver(loom.weave(Rendezvous.New(Pattern("g"))))
            val memberLobby = lobbyOver(loom.weave(Rendezvous.Existing(InMemoryTag("g"))))
            runCurrent()
            assertEquals(hostLobby.selfId, memberLobby.host.value, "rig: the second peer is the member")

            val outcome = backgroundScope.async { memberLobby.awaitRoom() }
            // Past the 3 s timeout and 3 s window that tear a member whose host is silent.
            advanceTimeBy(20.seconds)
            runCurrent()
            assertFalse(outcome.isCompleted, "a host that is alive and waiting must not read as unreachable")

            val hostRoom = hostLobby.start()
            val memberOutcome = outcome.await()
            assertAll(
                { assertIs<ElectionOutcome.Adopted>(memberOutcome, "the wait ends in the room the host opened") },
                { assertEquals(hostLobby.selfId, hostRoom.selfId, "and the host adopted it") },
            )
        }

    /** Before any role is chosen: an app reads `host` and only then picks `start` or `awaitRoom`. */
    @Test
    fun `a lobby with no detector running answers a ping`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("peer-a")
            val member = PeerId("peer-z")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, member))
            lobbyOver(seam)
            runCurrent()

            seam.deliver(member, ping)
            runCurrent()

            assertEquals(1, seam.framesTo(member, HeartbeatPartitionDetector.PONG_PREFIX), "one ping, one pong")
        }

    /**
     * A peer's link can wedge, and that peer is exactly who a heartbeat exists to catch, so answering
     * it must not cost anyone else. The inbound collector is what carries `Freeze` and `Commit`: a pong
     * sent from it would hold the whole election behind one stuck send.
     */
    @Test
    fun `a wedged pong delays neither another peer's pong nor the election, and does not stop adoption`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("peer-z")
            val host = PeerId("peer-a")
            val wedged = PeerId("peer-b")
            val other = PeerId("peer-c")
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, host, wedged, other))
            val seam = WedgedSendSeam(fake, wedged)
            val lobby = lobbyOver(seam)
            val outcome = backgroundScope.async { lobby.awaitRoom() }
            runCurrent()

            repeat(3) { fake.deliver(wedged, ping) }
            runCurrent()
            fake.deliver(other, ping)
            runCurrent()
            val freeze = LobbyMessage.Freeze(host.value, setOf(host.value, wedged.value, other.value, self.value), epoch = 1L)
            fake.deliver(host, LobbyMessage.encode(freeze))
            runCurrent()

            assertAll(
                { assertEquals(1, seam.wedgedSends, "rig: the wedged peer's send started, and a second is pointless") },
                { assertEquals(1, fake.framesTo(other, HeartbeatPartitionDetector.PONG_PREFIX), "another peer still gets its pong") },
                {
                    assertTrue(
                        fake.broadcasts.any { LobbyMessage.decode(it) == LobbyMessage.FreezeAck(host.value, 1L) },
                        "and the Freeze behind the wedged send was still acked",
                    )
                },
            )

            fake.deliver(host, LobbyMessage.encode(LobbyMessage.Commit(host.value, 1L)))
            assertIs<ElectionOutcome.Adopted>(outcome.await(), "adoption joins the responder, so a wedged pong must not hold it")
        }

    /** The other half of #2995: the host captured its detector set once, on entering `start`. */
    @Test
    fun `start monitors a member that joined after it was entered`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("peer-a")
            val early = PeerId("peer-b")
            val late = PeerId("peer-c")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, early))
            val lobby = lobbyOver(seam)
            var endedAt = -1L
            val started = backgroundScope.async {
                runCatchingForTest { lobby.start() }.also { endedAt = testScheduler.currentTime }
            }
            runCurrent()

            seam.addPeer(late)
            val joinedAt = testScheduler.currentTime
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(seam.framesTo(late, HeartbeatPartitionDetector.PING_PREFIX) > 0, "the late member must be pinged")

            // The early member stays audibly alive, so only the late one's silence can end this.
            repeat(9) {
                seam.deliver(early, byteArrayOf(0))
                advanceTimeBy(1.seconds)
                runCurrent()
            }

            val ended = started.await()
            assertAll(
                { assertIs<LobbyTornException>(ended, "a silent late member must collapse the election") },
                {
                    assertTrue(
                        endedAt - joinedAt in 3_000..9_999,
                        "past a transient blip and inside the freeze window, was ${endedAt - joinedAt}ms",
                    )
                },
            )
        }

    /**
     * The same captured set, read the other way: a member that left was still being watched, and its
     * detector reported it lost, tearing an election the remaining members could still complete.
     */
    @Test
    fun `start does not collapse because a member left and others remain`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("peer-a")
            val stays = PeerId("peer-b")
            val goes = PeerId("peer-c")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, stays, goes))
            val lobby = lobbyOver(seam)
            val started = backgroundScope.async { runCatchingForTest { lobby.start() } }
            runCurrent()

            seam.removePeer(goes)
            repeat(8) {
                seam.deliver(stays, byteArrayOf(0))
                advanceTimeBy(1.seconds)
                runCurrent()
            }

            assertFalse(started.isCompleted, "the election goes on with the member that stayed")
        }

    /** A silent host must still collapse the wait: answering pings cannot be what keeps a member here. */
    @Test
    fun `a member's own responder does not mask a silent host`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("peer-z")
            val host = PeerId("peer-a")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, host))
            val lobby = lobbyOver(seam)
            val outcome = backgroundScope.async { lobby.awaitRoom() }
            runCurrent()

            // One ping, so this member has something to answer; the host then says nothing more.
            seam.deliver(host, ping)
            runCurrent()
            assertTrue(seam.framesTo(host, HeartbeatPartitionDetector.PONG_PREFIX) > 0, "rig: this member answered")

            val ended = outcome.await()
            assertAll(
                { assertIs<ElectionOutcome.Torn>(ended, "our own pongs are no evidence that the host is alive") },
                { assertTrue(testScheduler.currentTime in 3_000..9_999, "was ${testScheduler.currentTime}ms") },
            )
        }

    /** Hands back the failure as a value so a thrown collapse does not cancel the test's scope. */
    private suspend fun runCatchingForTest(block: suspend () -> Any?): Any? =
        try {
            block()
        } catch (e: LobbyTornException) {
            e
        }

    /** A [Seam] whose send to [wedged] never returns, the way a stuck link's can. */
    private class WedgedSendSeam(private val delegate: FakeSeam, private val wedged: PeerId) : Seam by delegate {
        var wedgedSends: Int = 0
            private set

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (peer == wedged) {
                wedgedSends++
                awaitCancellation()
            }
            delegate.sendTo(peer, payload)
        }
    }
}
