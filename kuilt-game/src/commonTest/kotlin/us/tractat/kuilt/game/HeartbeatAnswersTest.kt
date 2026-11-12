@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
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
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.FakeSeam
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
     * real time on `iosSimulatorArm64` against a 30 s backstop. Each window now has a floor, and each
     * floor is asserted rather than assumed:
     *
     * - The opening must land more pings than an inline pong send needs to stall Raft. Such a send
     *   parks the collector on the first ping, but the mux stalls the other channels only once the
     *   heartbeat view's spool and the shared upstream buffer have filled behind it (#2863, pinned in
     *   `NamedMuxUnreadChannelTest`). Too few pings and the inline send passes "Raft keeps up". So the
     *   test **measures** that floor ([measuredMuxStallFloor]) and derives the opening from it: a
     *   larger buffer lengthens the opening instead of quietly emptying the inline-send arm. The
     *   precondition counts pings the host *sent*, so a fixture too short to fill the buffer is told
     *   apart from a defect that starved the joiner's incoming flow.
     * - The proposal is launched rather than awaited. An inline send that runs long enough backs up
     *   the in-memory link until the *host's* Raft stops too, and an awaited `propose` then parks the
     *   body until the backstop: a timeout that names nothing. Launched, the stall reads as a commit
     *   that never came.
     * - The liveness timeout must outlast the commit sample, or the wedged joiner is evicted before
     *   Raft is read and "keeps up" compares against a non-voter. `wedgedWasVoterAtSample` pins that.
     * - The eviction assertion has a control: the healthy joiner, pinged on the same schedule, keeps
     *   its seat, so a window short enough to evict everyone cannot pass for one that evicts the
     *   silent peer.
     */
    @Test
    fun wedgedPongSendDoesNotStallRaftAndStaysOneInFlight() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val stallFloor = measuredMuxStallFloor()
        // Fail before pinging for FLOOD_LIMIT milliseconds, not after.
        assertTrue(stallFloor < FLOOD_LIMIT, "the mux no longer backpressures an unread view (#2863 fixed?): an inline pong send cannot stall Raft, so this arm cannot fail — revisit it")
        val opening = PING_INTERVAL * (stallFloor + stallFloor / 4)
        val liveness = HeartbeatConfig(
            interval = PING_INTERVAL,
            timeout = opening + COMMIT_SETTLE + EVICTION_MARGIN,
            reconnectWindow = 200.milliseconds,
        )
        val attempts = atomic(0)
        val pingsSent = atomic(0)
        val (hostTransport, aRaw, bRaw) = seats(InMemoryLoom(), 3)
        val hostRaw = object : Seam by hostTransport {
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (peer == bRaw.selfId && payload.firstOrNull() == HEARTBEAT_TAG) pingsSent.incrementAndGet()
                hostTransport.sendTo(peer, payload)
            }
        }
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
        val healthyId = NodeId(aRaw.selfId.value)
        val wedgedId = NodeId(wedged.selfId.value)

        run(opening)
        val pingsBeforePropose = pingsSent.value
        val pongsBeforePropose = attempts.value
        val commitBeforePropose = host.node.commitIndex.value
        // Launched, not awaited: a stalled Raft must surface as a commit that never came, not as a body
        // parked on `propose` until the wall-clock backstop.
        backgroundScope.launch { TurnSequencer(host.node, Int.serializer()).propose(7) }
        run(COMMIT_SETTLE)
        val hostCommit = host.node.commitIndex.value
        val joinerCommit = b.node.commitIndex.value
        val wedgedWasVoterAtSample = wedgedId in host.node.membership.value.voters
        run(liveness.timeout + liveness.reconnectWindow)
        val voters = host.node.membership.value.voters

        assertAll(
            { assertTrue(pingsBeforePropose > stallFloor, "the rig fired: the host sent more pings than the mux stall floor $stallFloor, was $pingsBeforePropose") },
            { assertTrue(pongsBeforePropose > 0, "the rig fired: a pong send entered the wedge") },
            { assertEquals(1, pongsBeforePropose, "one pong in flight, however many pings landed") },
            { assertTrue(hostCommit > commitBeforePropose, "Raft keeps committing: the host committed the proposal, was $hostCommit after $commitBeforePropose") },
            { assertTrue(wedgedWasVoterAtSample, "the rig fired: the wedged joiner was still a voter when Raft was read") },
            { assertEquals(hostCommit, joinerCommit, "Raft on the wedged joiner must keep up") },
            { assertTrue(healthyId in voters, "control: the joiner that answers keeps its seat") },
            { assertFalse(wedgedId in voters, "a wedged pong path is a silent peer") },
        )
    }

    /**
     * Upstream frames a [MuxSeam] accepts for one channel whose collector took a frame and never came
     * back, before its shared collector stops: the ceiling on pings an inline pong send absorbs before
     * it stalls every other channel. Other channels' frames share the buffer, so in the full rig the
     * stall comes a little earlier (~301 against 322 at the default buffer).
     *
     * Measured rather than computed from [us.tractat.kuilt.core.DeliveryPolicy] and the coroutines
     * default buffer, the way `NamedMuxUnreadChannelTest` (kuilt-core, #2863) measures it, so neither a
     * larger buffer in the mux nor a `kotlinx.coroutines.channels.defaultBuffer` override can make the
     * opening too short to see the defect. A flood that never stops returns [FLOOD_LIMIT].
     */
    private fun TestScope.measuredMuxStallFloor(): Int {
        val probe = Job(backgroundScope.coroutineContext[Job])
        val probeScope = backgroundScope + probe
        var emitted = 0
        val flood = object : Seam by FakeSeam() {
            override val incoming = flow {
                repeat(FLOOD_LIMIT) {
                    emit(Swatch(byteArrayOf(HEARTBEAT_TAG, 0), sender = PeerId("flood")))
                    emitted++
                }
            }
        }
        val view = MuxSeam(flood, probeScope).channel(HEARTBEAT_TAG)
        probeScope.launch { view.incoming.collect { awaitCancellation() } }
        runCurrent()
        probe.cancel()
        return emitted
    }

    private companion object {
        val PING_INTERVAL: Duration = 1.milliseconds

        /** How long the proposed entry gets to commit before both nodes' commit indexes are read. */
        val COMMIT_SETTLE: Duration = 100.milliseconds

        /** How far past the commit sample the wedged joiner's eviction may fall. */
        val EVICTION_MARGIN: Duration = 300.milliseconds

        /** Bound on [measuredMuxStallFloor]'s flood, far past any stall the mux has had. */
        const val FLOOD_LIMIT = 10_000
    }
}
