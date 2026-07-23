@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.test.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A game bootstrapped over a [us.tractat.kuilt.session.Room] via `gameOverRoom` exposes the
 * **same** presence stream as the backing room — the surface-identity guarantee of #1618's Track 1.
 */
class RoomGamePresenceTest {

    @Test
    fun `gameOverRoom presence surfaces the backing room's partition events`() =
        runTest(timeout = 5.seconds) {
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
                clock = clock,
                raftConfig = fastRaftConfig(seed = 1L),
            )

            // Arm the collector on the game session's presence surface before the drop.
            val partitioned = async {
                session.presence.filterIsInstance<MembershipEvent.Partitioned>()
                    .first { it.peerId == fixture.victimId }
            }

            // Silent Wi-Fi loss: drop the victim member's frames both ways.
            fixture.faulty.links.first { it.selfId == fixture.victimId }.partition(Direction.Both)

            // Advance past the heartbeat timeout (200 ms) with margin.
            repeat(5) { tick() }

            assertEquals(
                fixture.victimId,
                partitioned.await().peerId,
                "the game session's presence must surface the room's Partitioned event",
            )

            session.close()
        }
}
