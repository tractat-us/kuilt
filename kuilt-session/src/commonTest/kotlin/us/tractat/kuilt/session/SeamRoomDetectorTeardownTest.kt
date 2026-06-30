@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Guards #1001: every coroutine a [SeamRoom] owns for a per-peer
 * [us.tractat.kuilt.liveness.HeartbeatPartitionDetector] — the heartbeat loop, the inbound
 * collector, and the event collector — must be cancelled when the member is evicted, not just
 * the event collector. The detector's inbound collector subscribes to `rawIncoming` (a
 * never-completing shared flow), so an un-cancelled detector outlives the evicted member.
 */
class SeamRoomDetectorTeardownTest {

    @Test
    fun `leave cancels all per-peer detector coroutines`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            // Inspectable host-room scope: a child of backgroundScope so it inherits the test
            // dispatcher and any survivor is auto-cancelled at test end (no leak error).
            val roomJob = Job(backgroundScope.coroutineContext[Job])
            val roomScope = CoroutineScope(backgroundScope.coroutineContext + roomJob)

            val loom = InMemoryLoom()
            val hostRoom = SeamRoom(
                seam = loom.host(Pattern("Alice")),
                role = SessionRole.Host,
                displayName = "Alice",
                scope = roomScope,
                clock = clock,
                heartbeatConfig = HeartbeatConfig(),
                roomId = RoomId("room-1"),
            ).also { it.start() }

            // Joiner on backgroundScope — only the host's scope is inspected.
            SeamRoom(
                seam = loom.join(InMemoryTag("Bob")),
                role = SessionRole.Joiner,
                displayName = "Bob",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = HeartbeatConfig(),
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 } // member admitted → detector started

            hostRoom.leave()
            runCurrent() // let cancellation propagate

            assertEquals(
                0,
                roomJob.children.count { it.isActive },
                "host room left active coroutines after leave() — a per-peer detector leaked",
            )
        }
}
