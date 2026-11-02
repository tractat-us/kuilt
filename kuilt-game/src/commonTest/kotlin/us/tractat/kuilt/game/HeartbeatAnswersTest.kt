@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val PRESENCE_TAG: Byte = 2
private const val HEARTBEAT_TAG: Byte = 4
private val PING = "kuilt.heartbeat.ping".encodeToByteArray()

/**
 * The joiner's heartbeat answerer, tested on its own (#3078).
 *
 * "I have left" is a local fact. These arms pin that the answerer reads the player's own latch and
 * never the replicated presence board, which any peer can write.
 */
class HeartbeatAnswersTest {

    private fun TestScope.run(forTime: Duration, step: Duration = 2.milliseconds) {
        var elapsed = Duration.ZERO
        while (elapsed < forTime) {
            advanceTimeBy(step)
            runCurrent()
            elapsed += step
        }
    }

    private class Rig(
        val presence: GamePresence,
        val answerer: Seam,
        val pinger: Seam,
        val pongs: AtomicInt,
        val forger: Quilter<EphemeralMap<String>>,
    )

    private suspend fun TestScope.rig(): Rig {
        val (pingerRaw, answererRaw, forgerRaw) = seats(InMemoryLoom(), 3)
        val pinger = MuxSeam(pingerRaw, backgroundScope).channel(HEARTBEAT_TAG)
        val pongs = atomic(0)
        backgroundScope.launch { pinger.incoming.collect { pongs.incrementAndGet() } }
        val mux = MuxSeam(answererRaw, backgroundScope)
        val presence = GamePresence(mux.channel(PRESENCE_TAG), backgroundScope, expectVirtualTime = true)
        backgroundScope.answerHeartbeatPings(mux.channel(HEARTBEAT_TAG), presence)
        val forger = Quilter(
            seam = MuxSeam(forgerRaw, backgroundScope).channel(PRESENCE_TAG),
            initial = EphemeralMap.empty<String>(),
            valueSerializer = EphemeralMap.serializer(String.serializer()),
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true),
        )
        runCurrent()
        return Rig(presence, answererRaw, pinger, pongs, forger)
    }

    private suspend fun TestScope.pingAndCount(rig: Rig): Int {
        val before = rig.pongs.value
        rig.pinger.sendTo(rig.answerer.selfId, PING)
        run(20.milliseconds)
        return rig.pongs.value - before
    }

    /** A vacate written by a peer that never held the seat leaves the answerer answering. */
    @Test
    fun aForgedVacateLeavesPongsFlowing() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        val control = pingAndCount(rig)

        val victim = ReplicaId(rig.answerer.selfId.value)
        rig.forger.mutateOrSkip { board -> Patch(board.put(victim, "vacate", 1_000L)) }
        run(50.milliseconds)
        val afterForgery = pingAndCount(rig)

        assertAll(
            { assertEquals(1, control, "control: an unforged answerer pongs") },
            { assertTrue(victim in rig.presence.vacaters(), "the rig fired: the victim's own board reads the forged vacate") },
            { assertFalse(rig.presence.hasLeft(), "the victim called nothing") },
            { assertEquals(1, afterForgery, "a forged vacate must not silence a healthy player") },
        )
    }

    /** A real leave stops the pongs. */
    @Test
    fun aRealLeaveStopsPongs() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        val control = pingAndCount(rig)

        rig.presence.declareVacate()
        val afterLeave = pingAndCount(rig)

        assertAll(
            { assertEquals(1, control, "control: the answerer pongs before it leaves") },
            { assertEquals(0, afterLeave, "a player who has left must not answer") },
        )
    }

    /**
     * A peer whose pong sends wedge forever must not stall the answerer's collector: Raft on the same
     * mux keeps committing, and only one pong is ever in flight however many pings land.
     */
    @Test
    fun wedgedPongSendDoesNotStallRaftAndStaysOneInFlight() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val attempts = atomic(0)
        val liveness = HeartbeatConfig(interval = 1.milliseconds, timeout = 5.seconds, reconnectWindow = 5.seconds)
        val (hostRaw, aRaw, bRaw) = seats(InMemoryLoom(), 3)
        val wedged = object : Seam by bRaw {
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (payload.firstOrNull() == HEARTBEAT_TAG) {
                    attempts.incrementAndGet()
                    CompletableDeferred<Unit>().await()
                }
                bRaw.sendTo(peer, payload)
            }
        }
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val hostD = async {
            backgroundScope.gameHost(hostRaw, peerCount = 3, raftConfig = fastRaftConfig(1L), livenessConfig = liveness, clock = clock)
        }
        val joins = listOf<Pair<Seam, Long>>(aRaw to 2L, wedged to 3L).map { (s, seed) ->
            async { backgroundScope.gameJoin(s, raftConfig = fastRaftConfig(seed)) }
        }
        val host = hostD.await()
        val (_, b) = joins.awaitAll()

        run(1.seconds)
        val pingsSoFar = attempts.value
        TurnSequencer(host.node, Int.serializer()).propose(7)
        run(100.milliseconds)
        val hostCommit = host.node.commitIndex.value
        val joinerCommit = b.node.commitIndex.value
        run(liveness.timeout + liveness.reconnectWindow)

        assertAll(
            { assertEquals(1, pingsSoFar, "one pong in flight, however many pings landed") },
            { assertTrue(hostCommit > 0, "the rig fired: the host committed, was $hostCommit") },
            { assertEquals(hostCommit, joinerCommit, "Raft on the wedged joiner must keep up") },
            { assertFalse(NodeId(wedged.selfId.value) in host.node.membership.value.voters, "a wedged pong path is a silent peer") },
        )
    }
}
