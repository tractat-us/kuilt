@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A room whose [leave] suspends before it does anything, as one over a real fabric's close does at
 * some point. The in-memory room can run its whole leave without reaching a suspension point, and
 * against that a cancelled caller leaves the room whether or not the call is shielded.
 */
private class SuspendingLeaveRoom(private val room: Room) : Room by room {
    override suspend fun leave(reason: LeaveReason) {
        delay(1.milliseconds)
        room.leave(reason)
    }
}

/**
 * A [RoomGameSession] owns the backing room's lifecycle: [RoomGameSession.close] tears down the
 * Raft node **and** leaves the room (a bare channel-view close would be a no-op, leaking the room,
 * its detectors, and the fabric). Double-close is safe.
 */
class RoomGameLifecycleTest {

    @Test
    fun `close leaves the backing room and is idempotent`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick() {
                nowMs += 100L
                advanceTimeBy(100L)
                runCurrent()
            }

            val fixture = adopt3PeerMeshRoom(backgroundScope, fastHeartbeat, clock)
            val session = backgroundScope.gameOverRoom(
                fixture.hostRoom,
                raftConfig = fastRaftConfig(seed = 1L),
            )

            // The room's transport is live before close (a channel view forwards the room's seam state).
            val roomSeamState = fixture.hostRoom.channel("probe").state
            assertFalse(roomSeamState.value is SeamState.Torn, "the room must be live before close()")

            session.close()
            repeat(2) { tick() }

            // Terminal via seam state — NOT "roster empties" (leave does not clear the roster).
            assertIs<SeamState.Torn>(
                roomSeamState.value,
                "close() must leave the backing room, tearing its underlying seam",
            )

            // Double-close is safe (the leave latch swallows the second call).
            session.close()
        }

    @Test
    fun `an already cancelled job still leaves the backing room`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val fixture = adopt3PeerMeshRoom(backgroundScope, fastHeartbeat) { Instant.fromEpochMilliseconds(0L) }
            val room = fixture.hostRoom
            val session = backgroundScope.gameOverRoom(
                SuspendingLeaveRoom(room),
                raftConfig = fastRaftConfig(seed = 1L),
            )
            val roomSeamState = room.channel("probe").state
            assertFalse(roomSeamState.value is SeamState.Torn, "precondition: the room is live before close()")

            var cancelledBeforeTheCall = false
            val closing = launch {
                coroutineContext.job.cancel()
                cancelledBeforeTheCall = coroutineContext.job.isCancelled
                session.close()
            }
            closing.join()

            assertAll(
                { assertTrue(cancelledBeforeTheCall, "precondition: the job was cancelled before the call") },
                { assertIs<SeamState.Torn>(roomSeamState.value, "close() left the room anyway") },
            )
        }

    @Test
    fun `a node whose close throws still leaves the backing room`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val fixture = adopt3PeerMeshRoom(backgroundScope, fastHeartbeat) { Instant.fromEpochMilliseconds(0L) }
            val room = fixture.hostRoom
            val roomSeamState = room.channel("probe").state
            val node = FakeRaftNode(NodeId(room.selfId.value))
            val session = RoomGameSession(
                ThrowingCloseNode(node),
                room,
                NamedMux(room.channel("app"), backgroundScope),
                lobby = null,
            )
            assertFalse(roomSeamState.value is SeamState.Torn, "precondition: the room is live before close()")

            val failure = assertFailsWith<IllegalStateException> { session.close(CloseReason.Normal) }

            assertAll(
                { assertEquals("node close failed", failure.message, "the rig fired, and its failure went on to the caller") },
                { assertIs<SeamState.Torn>(roomSeamState.value, "close() left the room anyway") },
            )
        }
}
