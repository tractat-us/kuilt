@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.HoldableConnection
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.identifiedLoomPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Room.flush` drains the room's own queues before it asks the fabric (#2830).
 *
 * The queues are the host's per-recipient lanes: announcements on the admit lane and forwards on the
 * relay lane. Neither reaches the fabric until a writer takes it, so a fabric that confirmed a drain
 * would say nothing about them. The star's [WireTapSeam] holds a send at the fabric's door, which is
 * what makes "the flush waited" something that can fail.
 *
 * Two of these run over the real `LinkSeam`, end to end, instead: they are the whole point of the
 * feature, and the control beside them is what shows the feature changed anything.
 */
class RoomFlushTest {

    /** Long enough for anything runnable to run, and far inside the relay lane's 200 ms send budget. */
    private val settle = 100.milliseconds

    /** A star whose fabric confirms a drain, so a test sees what the room did before asking it. */
    private suspend fun TestScope.drainingStar(coJoiners: Int = 3): RelayStar =
        relayStar(coJoiners = coJoiners).also { star -> star.host.wire.answerFlushWith { FlushOutcome.Drained } }

    private fun RelayStar.farewellsTo(member: StarMember): List<String> = wireFramesTo(member.id)
        .mapNotNull { AdmitMessage.decode(it) }
        .filterIsInstance<AdmitMessage.Farewell>()
        .map { it.peerId }

    /** An announcement queued before the flush goes before the flush returns. */
    @Test
    fun `a flush waits for a farewell still on its way`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.joinerA.room.leave()
        testScheduler.runCurrent()
        assertEquals(listOf(star.joinerAId.value), star.farewellsTo(star.joinerB), "rig: the farewell is at the hold")

        val flush = async { star.host.room.flush(Duration.INFINITE) }
        testScheduler.advanceTimeBy(settle)
        testScheduler.runCurrent()
        assertAll(
            { assertFalse(flush.isCompleted, "the flush returned with the farewell still held") },
            { assertEquals(0, star.host.wire.flushesAsked, "the fabric is asked only after the lanes have drained") },
        )

        star.host.wire.release(star.joinerBId)
        assertEquals(FlushOutcome.Drained, flush.await())
        testScheduler.runCurrent()
        assertAll(
            { assertEquals(1, star.host.wire.flushesAsked, "and then the fabric is asked") },
            { assertFalse(star.joinerB.room.roster.value.any { it.id == star.joinerAId }, "the farewell arrived") },
        )
    }

    /** A frame the host is forwarding between two spokes goes before the flush returns. */
    @Test
    fun `a flush waits for a forward still on its way`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("first"))
        star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("second"))
        testScheduler.runCurrent()
        assertTrue(star.joinerB.appFramesFrom(star.joinerAId).isEmpty(), "rig: both forwards are stuck")

        val flush = async { star.host.room.flush(Duration.INFINITE) }
        testScheduler.advanceTimeBy(settle)
        testScheduler.runCurrent()
        assertAll(
            { assertFalse(flush.isCompleted, "the flush returned with forwards still queued") },
            { assertEquals(0, star.host.wire.flushesAsked, "the fabric is asked only after the lanes have drained") },
        )

        star.host.wire.release(star.joinerBId)
        assertEquals(FlushOutcome.Drained, flush.await())
        testScheduler.runCurrent()
        assertEquals(listOf("first", "second"), star.joinerB.appFramesFrom(star.joinerAId))
    }

    /**
     * A barrier, not a wait for quiet: a frame that arrives after the flush began does not hold it up,
     * even on a lane of its own that is stuck.
     */
    @Test
    fun `a flush does not wait for what came after it began`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.host.wire.hold(star.joinerCId)
        star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("before"))
        testScheduler.runCurrent()

        val flush = async { star.host.room.flush(Duration.INFINITE) }
        testScheduler.runCurrent()
        star.joinerA.sendRelay(RelayDest.One(star.joinerCId), appPayload("after"))
        testScheduler.runCurrent()
        assertTrue(star.joinerC.appFramesFrom(star.joinerAId).isEmpty(), "rig: the later forward is stuck")

        star.host.wire.release(star.joinerBId)
        assertEquals(FlushOutcome.Drained, flush.await(), "only the forward before the flush had to go")
        assertTrue(star.joinerC.appFramesFrom(star.joinerAId).isEmpty(), "and the later one is still stuck")
    }

    /** The timeout path cannot say `Drained`, leaves the room up and the frame on its way. */
    @Test
    fun `a stalled member times the flush out without costing the frame`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = drainingStar()
            star.host.wire.hold(star.joinerBId)
            star.joinerA.room.leave()
            testScheduler.runCurrent()

            assertEquals(FlushOutcome.TimedOut, star.host.room.flush(settle))
            assertEquals(0, star.host.wire.flushesAsked, "a stalled lane never gets as far as the fabric")

            star.host.wire.release(star.joinerBId)
            assertEquals(FlushOutcome.Drained, star.host.room.flush(Duration.INFINITE))
            testScheduler.runCurrent()
            assertFalse(star.joinerB.room.roster.value.any { it.id == star.joinerAId }, "the farewell still arrived")
        }

    /** A fabric that cannot tell means the room cannot either, however empty its own queues are. */
    @Test
    fun `a fabric that cannot tell makes the room say Unsupported`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = relayStar()
        assertEquals(FlushOutcome.Unsupported, star.host.room.flush(Duration.INFINITE))
        assertEquals(1, star.host.wire.flushesAsked, "rig: the answer came from the fabric, not a shortcut")
    }

    /** A member leaving the roster takes its queue with it. A flush waiting on that queue moves on. */
    @Test
    fun `a member leaving releases a flush waiting on its lanes`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("forward"))
        star.joinerC.room.leave()
        testScheduler.runCurrent()
        assertEquals(listOf(star.joinerCId.value), star.farewellsTo(star.joinerB), "rig: both B's lanes are stuck")

        val flush = async { star.host.room.flush(Duration.INFINITE) }
        testScheduler.advanceTimeBy(settle)
        testScheduler.runCurrent()
        assertFalse(flush.isCompleted, "rig: the flush is waiting on B")

        star.joinerB.room.leave()
        testScheduler.runCurrent()
        assertTrue(flush.isCompleted, "the flush is still waiting on a member who has gone")
        assertEquals(FlushOutcome.Drained, flush.await(), "nothing is owed to a member who has gone")
    }

    /** The whole answer is the fabric's to give, including a failure. */
    @Test
    fun `a failure from the fabric comes back as Failed`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = relayStar()
        val failure = IllegalStateException("the fabric refused")
        star.host.wire.answerFlushWith { throw failure }
        val outcome = assertIs<FlushOutcome.Failed>(star.host.room.flush(Duration.INFINITE))
        assertEquals(failure.message, outcome.cause.message)
    }

    /** `withTimeout` inside a fabric's flush mints a cancellation nobody asked for: a failure, not a cancel. */
    @Test
    fun `a cancellation minted by the fabric is a failure`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = relayStar()
        star.host.wire.answerFlushWith { withTimeout(1.milliseconds) { awaitCancellation() } }
        assertIs<FlushOutcome.Failed>(star.host.room.flush(Duration.INFINITE))
    }

    /** A room that has left cannot confirm anything, and its sends are silent no-ops by then. */
    @Test
    fun `a room that has left says Failed without asking the fabric`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.room.leave()
        assertIs<FlushOutcome.Failed>(star.host.room.flush(Duration.INFINITE))
        assertEquals(0, star.host.wire.flushesAsked)
    }

    /**
     * A room that has ended says so even while its fabric is still up, which is what `leave` looks like
     * until its goodbye has gone. The fabric would answer `Drained` here, for a room whose sends have
     * already become silent no-ops.
     */
    @Test
    fun `a room mid-leave says Failed while its fabric is still up`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        val wire = star.joinerA.wire
        wire.answerFlushWith { FlushOutcome.Drained }
        wire.holdBroadcasts()
        val leaving = launch { star.joinerA.room.leave() }
        testScheduler.runCurrent()
        assertIs<SeamState.Woven>(wire.state.value, "rig: the goodbye is stuck, so the fabric is still up")

        assertIs<FlushOutcome.Failed>(star.joinerA.room.flush(Duration.INFINITE))
        assertEquals(0, wire.flushesAsked)
        wire.releaseBroadcasts()
        leaving.join()
    }

    /**
     * A fabric tearing ends a flush at once, rather than after the lane's own send budget (10.6 s here).
     *
     * The writer's send can stay parked on a torn link, so a flush watching only the lanes would wait
     * out that budget and then say nothing useful. Runs no virtual time on purpose.
     */
    @Test
    fun `a fabric tearing ends a flush at once`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.joinerA.room.leave()
        testScheduler.runCurrent()
        val flush = async { star.host.room.flush(Duration.INFINITE) }
        testScheduler.runCurrent()
        assertFalse(flush.isCompleted, "rig: the flush is waiting on the stuck lane")

        star.host.wire.close()
        testScheduler.runCurrent()

        assertAll(
            { assertIs<SeamState.Torn>(star.host.wire.state.value, "rig: the fabric tore") },
            { assertTrue(flush.isCompleted, "the flush ended with no time passing") },
        )
        assertIs<FlushOutcome.Failed>(flush.await())
    }

    /** The caller's own cancellation is a cancellation, and costs the room nothing. */
    @Test
    fun `cancelling a flush leaves the room and its frames alone`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val star = drainingStar()
        star.host.wire.hold(star.joinerBId)
        star.joinerA.room.leave()
        testScheduler.runCurrent()
        val flush = launch { star.host.room.flush(Duration.INFINITE) }
        testScheduler.runCurrent()

        flush.cancelAndJoin()
        assertTrue(flush.isCancelled, "a cancelled flush is cancelled, not returned from")

        star.host.wire.release(star.joinerBId)
        testScheduler.runCurrent()
        assertFalse(star.joinerB.room.roster.value.any { it.id == star.joinerAId }, "the farewell still arrived")
    }

    /** Two rooms over one real `LinkSeam` pair, whose host-side sends can be held. */
    private class LinkedRooms(val host: Room, val joiner: Room, val hostEnd: HoldableConnection)

    private suspend fun TestScope.linkedRooms(): LinkedRooms {
        val (hostConnection, joinerConnection) = connectionPair()
        val hostEnd = HoldableConnection(hostConnection)
        val (hostLoom, joinerLoom) = identifiedLoomPair(hostEnd to HoldableConnection(joinerConnection))
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val host = SeamRoomFactory(hostLoom, backgroundScope, clock, relayHeartbeat).host(Pattern("table"))
        val joiner = SeamRoomFactory(joinerLoom, backgroundScope, clock, relayHeartbeat).join(InMemoryTag("table"))
        host.roster.first { it.size == 1 }
        joiner.roster.first { it.size == 1 }
        return LinkedRooms(host, joiner, hostEnd)
    }

    /** The feature end to end: send a last frame, flush, leave, and the other member has the frame. */
    @Test
    fun `a last frame survives a flush then a leave`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val rooms = linkedRooms()
        val last = async { rooms.joiner.incomingFrom(rooms.host.selfId).first().payload.decodeToString() }
        testScheduler.runCurrent()

        rooms.hostEnd.hold()
        rooms.host.sendTo(rooms.joiner.selfId, appPayload("last"))
        val flush = async { rooms.host.flush(Duration.INFINITE) }
        testScheduler.advanceTimeBy(settle)
        testScheduler.runCurrent()
        assertAll(
            { assertEquals(1, rooms.hostEnd.parkedSends, "rig: the frame is stuck at the transport") },
            { assertFalse(flush.isCompleted, "the flush returned with the frame still stuck") },
        )

        rooms.hostEnd.releaseAll()
        assertEquals(FlushOutcome.Drained, flush.await())
        rooms.host.leave()
        assertEquals("last", last.await())
    }

    /**
     * The control for the test above, and the pin on the ruling: `leave` gets no new wait. It returns
     * with the frame still stuck, and the frame is lost. Reds if anyone adds a drain to `leave`.
     */
    @Test
    fun `without a flush a leave does not wait and the frame is lost`() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val rooms = linkedRooms()
        val received = mutableListOf<String>()
        val reader = launch { rooms.joiner.incomingFrom(rooms.host.selfId).collect { received += it.payload.decodeToString() } }
        testScheduler.runCurrent()

        rooms.hostEnd.hold()
        rooms.host.sendTo(rooms.joiner.selfId, appPayload("last"))
        testScheduler.advanceTimeBy(settle)
        testScheduler.runCurrent()
        assertEquals(1, rooms.hostEnd.parkedSends, "rig: the frame is stuck at the transport")

        // No time passes and nothing is released: a leave that waited would never return from this.
        rooms.host.leave()
        rooms.hostEnd.releaseAll()
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()
        assertTrue(received.isEmpty(), "the leave closed the link under the frame, so it never arrived")
        reader.cancelAndJoin()
    }
}
