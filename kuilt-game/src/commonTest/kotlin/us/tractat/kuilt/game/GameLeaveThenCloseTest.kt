@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val SEND_TIME = 1.milliseconds
private val CLOSE_TIME = 1.milliseconds

/** The vacate marker as it appears inside a presence frame. Only a presence board can carry it. */
private val VACATE_BYTES = "vacate".encodeToByteArray()

private fun ByteArray.carriesVacate(): Boolean =
    (0..size - VACATE_BYTES.size).any { start -> VACATE_BYTES.indices.all { this[start + it] == VACATE_BYTES[it] } }

/**
 * A [Seam] whose sends **really suspend** in [hold] before reaching [delegate], and which counts what
 * happened to every frame carrying the vacate marker: it [vacateEntered] the send, was [vacateCancelled]
 * out of its suspension, or was [vacateHanded] to the fabric underneath.
 *
 * The suspension is the whole rig. Over a send that completes without suspending, a queued broadcast
 * finishes in one go whenever it first runs, which hides one of the two windows this tests. Plain
 * counters are enough: these tests run on one test dispatcher.
 *
 * Its [close] suspends too, for [CLOSE_TIME], and only then closes [delegate]. An in-memory close never
 * reaches a suspension point, so against it a cancelled caller closes the fabric whether or not the
 * teardown is shielded, and the arms that ask about that could not fail.
 */
private class HeldSendSeam(
    private val delegate: Seam,
    private val hold: suspend (ByteArray) -> Unit,
) : Seam by delegate {
    var vacateEntered = 0
        private set
    var vacateCancelled = 0
        private set
    var vacateHanded = 0
        private set
    var sendsEntered = 0
        private set

    override suspend fun close(reason: CloseReason) {
        delay(CLOSE_TIME)
        delegate.close(reason)
    }

    override suspend fun broadcast(payload: ByteArray) = send(payload) { delegate.broadcast(payload) }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = send(payload) { delegate.sendTo(peer, payload) }

    private suspend fun send(payload: ByteArray, deliver: suspend () -> Unit) {
        val vacate = payload.carriesVacate()
        sendsEntered++
        if (vacate) vacateEntered++
        try {
            hold(payload)
        } catch (e: CancellationException) {
            if (vacate) vacateCancelled++
            throw e
        }
        deliver()
        if (vacate) vacateHanded++
    }
}

/**
 * The prescribed departure, `leave()` then `close()`, must free the seat at once (#3027).
 *
 * A three-voter game on [StandardTestDispatcher], so a launched send is queued until something
 * suspends, exactly as on any single-threaded dispatcher. [fastLivenessConfig]'s reconnect window is
 * 50 ms of virtual time and the assertions look at half of it, so a seat freed there can only have
 * been freed by the vacate frame: the dead-man's switch has not had time to fire.
 */
class GameLeaveThenCloseTest {

    private class Table(
        val host: GameSession,
        val leaving: GameSession,
        val leavingId: NodeId,
        val seam: HeldSendSeam,
    )

    private suspend fun TestScope.table(hold: suspend (ByteArray) -> Unit = { delay(SEND_TIME) }): Table {
        val loom = InMemoryLoom()
        val (hostRaw, stayingRaw, leavingRaw) = seats(loom, 3)
        val seam = HeldSendSeam(leavingRaw, hold)
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = fastLivenessConfig(),
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            )
        }
        val stayingDeferred = async { backgroundScope.gameJoin(stayingRaw, raftConfig = fastRaftConfig(seed = 2L)) }
        val leavingDeferred = async { backgroundScope.gameJoin(seam, raftConfig = fastRaftConfig(seed = 3L)) }
        val host = hostDeferred.await()
        stayingDeferred.await()
        val leaving = leavingDeferred.await()
        runCurrent()
        check(host.node.membership.value.voters.size == 3) { "precondition: all three seats are taken" }
        return Table(host, leaving, NodeId(leavingRaw.selfId.value), seam)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private val halfWindow = fastLivenessConfig().reconnectWindow / 2

    /** The issue's shape, in the order `GameSession`'s KDoc prescribes it. */
    @Test
    fun leaveThenCloseFreesTheSeatInsideTheWindow() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = table()

        table.leaving.leave()
        assertEquals(0, table.seam.vacateEntered, "precondition: leave returned with the send still queued")

        table.leaving.close()
        advance(halfWindow)

        val voters = table.host.node.membership.value.voters
        val hostHeard = table.host.lobbyPresence?.vacaters().orEmpty().map { it.value }
        assertAll(
            { assertEquals(1, table.seam.vacateEntered, "the vacate frame reached the seam's send") },
            { assertEquals(0, table.seam.vacateCancelled, "and was not cancelled out of it") },
            { assertEquals(1, table.seam.vacateHanded, "and went through to the fabric") },
            { assertEquals(listOf(table.leavingId.value), hostHeard, "and the host's board holds the marker") },
            { assertFalse(table.leavingId in voters, "the host freed the seat inside the window, voters were $voters") },
            { assertEquals(2, voters.size, "and only that seat, voters were $voters") },
        )
    }

    /**
     * The control: the same table and the same teardown, with no [GameSession.leave]. The seat is held
     * at the point the arm above asserts it free, so that assertion can be false, and it is freed once
     * the window has passed, so the host's eviction path is alive in this fixture.
     */
    @Test
    fun closeWithoutLeaveHoldsTheSeatForTheWindow() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = table()

        table.leaving.close()
        advance(halfWindow)

        val insideTheWindow = table.host.node.membership.value.voters
        assertAll(
            { assertEquals(0, table.seam.vacateEntered, "precondition: nothing announced a departure") },
            { assertTrue(table.leavingId in insideTheWindow, "held inside the window, voters were $insideTheWindow") },
        )

        advance(fastLivenessConfig().reconnectWindow * 2)

        val afterTheWindow = table.host.node.membership.value.voters
        assertFalse(table.leavingId in afterTheWindow, "freed after the window, voters were $afterTheWindow")
    }

    // ── The wait's bound, and what ends up closed however the wait ends ───────────────────────

    /** One session over a real presence board and a [FakeRaftNode], whose `closed` can be read back. */
    private class Lone(
        val session: GameSession,
        val node: FakeRaftNode,
        val seam: HeldSendSeam,
        val presence: GamePresence,
    ) {
        fun assertAllClosed() {
            assertAll(
                { assertTrue(node.closed, "the node was closed") },
                { assertIs<SeamState.Torn>(seam.state.value, "the fabric was closed") },
            )
        }
    }

    private suspend fun TestScope.lone(hold: suspend (ByteArray) -> Unit): Lone {
        val (raw, _) = seats(InMemoryLoom(), 2)
        val seam = HeldSendSeam(raw, hold)
        val mux = MuxSeam(seam, backgroundScope)
        val presence = GamePresence(mux.channel(2), backgroundScope, expectVirtualTime = true)
        val node = FakeRaftNode(NodeId(raw.selfId.value))
        val session = GameSession(node, seam, NamedMux(mux.channel(3), backgroundScope), presence)
        runCurrent()
        return Lone(session, node, seam, presence)
    }

    /** A vacate frame the seam never finishes sending. Every other frame goes straight through. */
    private fun wedgedVacate(): suspend (ByteArray) -> Unit {
        val neverReleased = CompletableDeferred<Unit>()
        return { payload -> if (payload.carriesVacate()) neverReleased.await() }
    }

    /** A caller with no timeout of its own must not hang on a seam that holds the frame for ever. */
    @Test
    fun aWedgedSendHoldsCloseOnlyForTheBound() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(wedgedVacate())

        lone.session.leave()
        val start = testScheduler.currentTime
        lone.session.close()

        assertAll(
            {
                assertEquals(
                    DEPARTURE_HANDOFF_BOUND.inWholeMilliseconds + CLOSE_TIME.inWholeMilliseconds,
                    testScheduler.currentTime - start,
                    "close waited out the bound, then the fabric's own close, and no longer",
                )
            },
            { assertEquals(1, lone.seam.vacateEntered, "precondition: the wait really was on a stuck send") },
            { assertEquals(1, lone.seam.vacateCancelled, "expiring the bound cancelled the stuck send") },
            { assertEquals(0, lone.seam.vacateHanded, "the frame never reached the fabric") },
            { lone.assertAllClosed() },
        )
    }

    /** The caller's timeout fires during the wait: it surfaces, on time, and leaves nothing open. */
    @Test
    fun aCallerTimeoutDuringTheWaitStillClosesEverything() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(wedgedVacate())
        val callerBound = DEPARTURE_HANDOFF_BOUND / 2

        lone.session.leave()
        val start = testScheduler.currentTime
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(callerBound) { lone.session.close() }
        }

        assertAll(
            {
                assertEquals(
                    callerBound.inWholeMilliseconds + CLOSE_TIME.inWholeMilliseconds,
                    testScheduler.currentTime - start,
                    "the caller's bound ended the wait, not the session's",
                )
            },
            { assertEquals(1, lone.seam.vacateEntered, "precondition: the wait really was on a stuck send") },
            { assertEquals(1, lone.seam.vacateCancelled, "the stuck send was cancelled") },
            { lone.assertAllClosed() },
        )
    }

    /** The same, for a caller cancelled from outside rather than by a bound of its own. */
    @Test
    fun aCancelledCallerStillClosesEverything() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(wedgedVacate())

        lone.session.leave()
        val closing = launch { lone.session.close() }
        runCurrent()
        assertAll(
            { assertEquals(1, lone.seam.vacateEntered, "precondition: the wait really was on a stuck send") },
            { assertFalse(lone.node.closed, "precondition: close is still waiting") },
        )

        closing.cancelAndJoin()

        assertAll(
            { assertTrue(closing.isCancelled, "the cancellation went on to the caller") },
            { lone.assertAllClosed() },
        )
    }

    /**
     * [GameSession.close] with no departure stays a hard local teardown: a stuck send it has no reason
     * to care about does not delay it by a single virtual millisecond beyond the fabric's own close.
     */
    @Test
    fun closeWithoutLeaveWaitsForNoSend() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val neverReleased = CompletableDeferred<Unit>()
        val lone = lone { neverReleased.await() }
        val sendsBefore = lone.seam.sendsEntered

        lone.presence.declarePresent()
        runCurrent()
        assertEquals(sendsBefore + 1, lone.seam.sendsEntered, "precondition: a send is stuck in the seam")

        val start = testScheduler.currentTime
        lone.session.close()

        assertAll(
            { assertEquals(CLOSE_TIME.inWholeMilliseconds, testScheduler.currentTime - start, "close waited for no send") },
            { lone.assertAllClosed() },
        )
    }

    @Test
    fun aSecondCloseAfterLeaveIsANoOp() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone { delay(SEND_TIME) }

        lone.session.leave()
        lone.session.close()
        val handed = lone.seam.vacateHanded
        lone.session.close()

        assertAll(
            { assertEquals(1, handed, "precondition: the first close handed the frame over") },
            { assertEquals(1, lone.seam.vacateHanded, "the second close sent nothing") },
            { lone.assertAllClosed() },
        )
    }
}
