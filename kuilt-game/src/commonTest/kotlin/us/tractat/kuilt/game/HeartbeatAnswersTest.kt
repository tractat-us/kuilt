@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.DeliveryPolicy
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
     *
     * **The windows are sized to what each property needs to be falsifiable, and no larger (#3113).**
     * The first version ran a 1 s opening and a 5 s + 5 s liveness window, and took a median ~16 s of
     * real time on `iosSimulatorArm64` against a 30 s backstop. Each knob below has a floor, and each
     * floor is asserted rather than assumed:
     *
     * - [WEDGE_OPENING] must land more pings than [SPOOL_FILL_PINGS]. An answerer that sent its pong
     *   inline would stall its collector on the first ping; the mux stalls Raft only once the
     *   heartbeat view's spool and the shared upstream buffer fill behind it. Fewer pings and the
     *   inline send passes "Raft keeps up". Measured with that mutation: the stall lands at ~301 pings.
     * - [WEDGE_LIVENESS]'s timeout must outlast the commit sample at `WEDGE_OPENING` + 100 ms, or the
     *   wedged joiner is evicted before Raft is read and "keeps up" compares against a non-voter.
     *   `wedgedWasVoterAtSample` pins that ordering.
     * - The eviction assertion has a control: the healthy joiner, pinged on the same schedule, keeps
     *   its seat, so a window short enough to evict everyone cannot pass for one that evicts the
     *   silent peer.
     */
    @Test
    fun wedgedPongSendDoesNotStallRaftAndStaysOneInFlight() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val attempts = atomic(0)
        val pingsLanded = atomic(0)
        val (hostRaw, aRaw, bRaw) = seats(InMemoryLoom(), 3)
        val wedged = object : Seam by bRaw {
            override val incoming = bRaw.incoming.onEach { if (it.payloadSize > 0 && it.byteAt(0) == HEARTBEAT_TAG) pingsLanded.incrementAndGet() }
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
            backgroundScope.gameHost(hostRaw, peerCount = 3, raftConfig = fastRaftConfig(1L), livenessConfig = WEDGE_LIVENESS, clock = clock)
        }
        val joins = listOf<Pair<Seam, Long>>(aRaw to 2L, wedged to 3L).map { (s, seed) ->
            async { backgroundScope.gameJoin(s, raftConfig = fastRaftConfig(seed)) }
        }
        val host = hostD.await()
        val (_, b) = joins.awaitAll()
        val healthyId = NodeId(aRaw.selfId.value)
        val wedgedId = NodeId(wedged.selfId.value)

        run(WEDGE_OPENING)
        val pingsBeforePropose = pingsLanded.value
        val pongsBeforePropose = attempts.value
        TurnSequencer(host.node, Int.serializer()).propose(7)
        run(100.milliseconds)
        val hostCommit = host.node.commitIndex.value
        val joinerCommit = b.node.commitIndex.value
        val wedgedWasVoterAtSample = wedgedId in host.node.membership.value.voters
        run(WEDGE_LIVENESS.timeout + WEDGE_LIVENESS.reconnectWindow)
        val voters = host.node.membership.value.voters

        assertAll(
            { assertTrue(pingsBeforePropose > SPOOL_FILL_PINGS, "the rig fired: enough pings landed to fill the spool behind an inline send, was $pingsBeforePropose") },
            { assertTrue(pongsBeforePropose > 0, "the rig fired: a pong send entered the wedge") },
            { assertEquals(1, pongsBeforePropose, "one pong in flight, however many pings landed") },
            { assertTrue(hostCommit > 0, "the rig fired: the host committed, was $hostCommit") },
            { assertTrue(wedgedWasVoterAtSample, "the rig fired: the wedged joiner was still a voter when Raft was read") },
            { assertEquals(hostCommit, joinerCommit, "Raft on the wedged joiner must keep up") },
            { assertTrue(healthyId in voters, "control: the joiner that answers keeps its seat") },
            { assertFalse(wedgedId in voters, "a wedged pong path is a silent peer") },
        )
    }

    private companion object {
        /**
         * Pings that must land before an inline pong send would stall Raft: the heartbeat view's
         * spool plus the shared upstream's default `shareIn` buffer, which also holds other channels'
         * frames, so the measured stall (~301) sits a little under this sum.
         */
        const val SPOOL_FILL_PINGS = DeliveryPolicy.DEFAULT_CAPACITY + 64

        /** At a 1 ms ping interval, 400 pings: clear of [SPOOL_FILL_PINGS]. */
        val WEDGE_OPENING: Duration = 400.milliseconds

        /** Timeout outlasts the commit sample at 500 ms; the whole window ends 1 s after the opening. */
        val WEDGE_LIVENESS = HeartbeatConfig(interval = 1.milliseconds, timeout = 800.milliseconds, reconnectWindow = 200.milliseconds)
    }
}
