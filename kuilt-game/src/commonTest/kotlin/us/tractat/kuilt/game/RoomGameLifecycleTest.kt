@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

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
}
