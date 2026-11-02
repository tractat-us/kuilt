@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
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
import us.tractat.kuilt.raft.RaftNode
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
private val CLOSE_TIME = 2.milliseconds

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

/** A stubbed node whose [close] fails, for what teardown does after that. */
internal class ThrowingCloseNode(private val fake: FakeRaftNode) : RaftNode by fake {
    override suspend fun close() {
        fake.close()
        throw IllegalStateException("node close failed")
    }
}

/**
 * What `leave()` then `close()` does (#3027), including the part that is not fixed.
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

    private fun TestScope.assertSeatHeldThenFreedAtTheWindow(table: Table) {
        advance(halfWindow)
        val insideTheWindow = table.host.node.membership.value.voters
        val hostHeard = table.host.lobbyPresence?.vacaters().orEmpty()
        assertAll(
            { assertEquals(0, table.seam.vacateHanded, "the vacate frame never reached the fabric") },
            { assertEquals(emptySet(), hostHeard, "so the host's board has no marker") },
            { assertTrue(table.leavingId in insideTheWindow, "the seat is held inside the window, voters were $insideTheWindow") },
        )

        advance(fastLivenessConfig().reconnectWindow * 2)

        val afterTheWindow = table.host.node.membership.value.voters
        assertFalse(table.leavingId in afterTheWindow, "freed after the window, voters were $afterTheWindow")
    }

    /**
     * **The remaining gap, stated as a test.** `close` waits for nothing, so `leave()` then `close()`
     * loses the frame and the seat waits for the window. When a public `flush` lands (#2830) and a
     * caller can wait for the frame, this is the arm to change: it pins the behaviour of the two calls
     * with nothing between them, which is what a caller has today.
     */
    @Test
    fun leaveThenCloseLosesTheFrameAndTheSeatWaitsForTheWindow() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = table()

        table.leaving.leave()
        assertEquals(0, table.seam.vacateEntered, "precondition: leave returned with the send still queued")
        table.leaving.close()

        assertSeatHeldThenFreedAtTheWindow(table)
    }

    /** The gap does not depend on the seam holding the send: nothing runs the send before the close. */
    @Test
    fun leaveThenCloseLosesTheFrameOverASeamThatNeverHolds() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = table(hold = {})

        table.leaving.leave()
        assertEquals(0, table.seam.vacateEntered, "precondition: leave returned with the send still queued")
        table.leaving.close()

        assertSeatHeldThenFreedAtTheWindow(table)
    }

    /**
     * What this PR does fix: once the frame does reach the host, the host acts on it inside the window.
     * The time between the calls stands in for the wait a caller cannot yet ask for.
     */
    @Test
    fun aFrameThatGotOutBeforeTheCloseFreesTheSeatInsideTheWindow() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = table()

        table.leaving.leave()
        advance(SEND_TIME)
        assertEquals(1, table.seam.vacateHanded, "precondition: the frame went through to the fabric")
        table.leaving.close()
        advance(halfWindow)

        val voters = table.host.node.membership.value.voters
        val hostHeard = table.host.lobbyPresence?.vacaters().orEmpty().map { it.value }
        assertAll(
            { assertEquals(listOf(table.leavingId.value), hostHeard, "the host's board holds the marker") },
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

    // ── close waits for nothing, and what ends up closed however it is called ─────────────────

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

    private suspend fun TestScope.lone(
        hold: suspend (ByteArray) -> Unit,
        wrap: (FakeRaftNode) -> RaftNode = { it },
    ): Lone {
        val (raw, _) = seats(InMemoryLoom(), 2)
        val seam = HeldSendSeam(raw, hold)
        val mux = MuxSeam(seam, backgroundScope)
        val presence = GamePresence(mux.channel(2), backgroundScope, expectVirtualTime = true)
        val node = FakeRaftNode(NodeId(raw.selfId.value))
        val session = GameSession(wrap(node), seam, NamedMux(mux.channel(3), backgroundScope), presence)
        runCurrent()
        return Lone(session, node, seam, presence)
    }

    /** A vacate frame the seam never finishes sending. Every other frame goes straight through. */
    private fun wedgedVacate(): suspend (ByteArray) -> Unit {
        val neverReleased = CompletableDeferred<Unit>()
        return { payload -> if (payload.carriesVacate()) neverReleased.await() }
    }

    /**
     * The ruling this PR carries: `close` does not wait, even after `leave`, even for a send already in
     * the seam. It takes the fabric's own close and no longer, and leaves the stuck frame behind.
     */
    @Test
    fun closeAfterLeaveWaitsForNoSend() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(wedgedVacate())

        lone.session.leave()
        runCurrent()
        assertEquals(1, lone.seam.vacateEntered, "precondition: the vacate frame is stuck in the seam")

        val start = testScheduler.currentTime
        lone.session.close()

        assertAll(
            { assertEquals(CLOSE_TIME.inWholeMilliseconds, testScheduler.currentTime - start, "close waited for no send") },
            { assertEquals(0, lone.seam.vacateHanded, "the frame never reached the fabric") },
            { lone.assertAllClosed() },
        )
    }

    /** The same, with no departure in the picture at all. */
    @Test
    fun closeWithoutLeaveWaitsForNoSend() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val neverReleased = CompletableDeferred<Unit>()
        val lone = lone(hold = { neverReleased.await() })
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

    /** The caller's timeout fires during the fabric's close: it surfaces, and leaves nothing open. */
    @Test
    fun aCallerTimeoutDuringTheCloseStillClosesEverything() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(hold = { delay(SEND_TIME) })
        val callerBound = CLOSE_TIME / 2

        val start = testScheduler.currentTime
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(callerBound) { lone.session.close() }
        }

        assertAll(
            {
                assertEquals(
                    CLOSE_TIME.inWholeMilliseconds,
                    testScheduler.currentTime - start,
                    "the shield outlasted the caller's bound, so the fabric's close ran to its end",
                )
            },
            { lone.assertAllClosed() },
        )
    }

    /** The same, for a caller whose job was already cancelled when it called `close`. */
    @Test
    fun anAlreadyCancelledCallerStillClosesEverything() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(hold = { delay(SEND_TIME) })

        var cancelledBeforeTheCall = false
        val closing = launch {
            coroutineContext.job.cancel()
            cancelledBeforeTheCall = coroutineContext.job.isCancelled
            lone.session.close()
        }
        closing.join()

        assertAll(
            { assertTrue(cancelledBeforeTheCall, "precondition: the job was cancelled before the call") },
            { lone.assertAllClosed() },
        )
    }

    /** A node whose close fails does not cost the fabric, and the failure is not swallowed. */
    @Test
    fun aThrowingNodeCloseStillClosesTheFabric() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(hold = { delay(SEND_TIME) }, wrap = { ThrowingCloseNode(it) })

        val failure = assertFailsWith<IllegalStateException> { lone.session.close() }

        assertAll(
            { assertEquals("node close failed", failure.message, "the rig fired, and its failure went on to the caller") },
            { lone.assertAllClosed() },
        )
    }

    @Test
    fun aSecondCloseAfterLeaveIsANoOp() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val lone = lone(hold = { delay(SEND_TIME) })

        lone.session.leave()
        lone.session.close()
        val entered = lone.seam.sendsEntered
        lone.session.close()

        assertAll(
            { assertEquals(entered, lone.seam.sendsEntered, "the second close sent nothing") },
            { lone.assertAllClosed() },
        )
    }
}
