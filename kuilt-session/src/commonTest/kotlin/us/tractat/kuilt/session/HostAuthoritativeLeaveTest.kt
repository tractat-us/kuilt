@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Host-authoritative prompt propagation of clean leaves (#1292).
 *
 * On a clean [AdmitMessage.Goodbye] the host — the membership authority — fans out an
 * authoritative leave notification to every remaining member, so a **non-host** member
 * removes the departed peer promptly with [LeaveReason.Normal] instead of waiting out
 * its own heartbeat window (~timeout + reconnectWindow) and mislabelling the clean
 * leave as [LeaveReason.PartitionExpired].
 */
class HostAuthoritativeLeaveTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    @Test
    fun `non-host member observes Left Normal promptly after a peer's clean leave`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)

            val hostRoom = factory.host(Pattern("Host"))
            val joiner1Room = factory.join(InMemoryTag("Joiner1"))
            val joiner2Room = factory.join(InMemoryTag("Joiner2"))

            hostRoom.roster.first { it.size == 2 }
            joiner1Room.roster.first { it.size == 2 }
            joiner2Room.roster.first { it.size == 2 }

            val joiner2Id = joiner2Room.selfId
            val left = async {
                joiner1Room.events
                    .filterIsInstance<MembershipEvent.Left>()
                    .first { it.peerId == joiner2Id }
            }
            val before = testScheduler.currentTime

            joiner2Room.leave()

            val leftEvent = left.await()
            val elapsedMs = testScheduler.currentTime - before
            assertAll(
                {
                    assertEquals(
                        LeaveReason.Normal,
                        leftEvent.reason,
                        "a clean leave must propagate to non-host members as Normal, " +
                            "not as a heartbeat-window PartitionExpired",
                    )
                },
                {
                    assertTrue(
                        elapsedMs < fastConfig.timeout.inWholeMilliseconds,
                        "eviction must be prompt (host-propagated), not after the heartbeat " +
                            "window — took ${elapsedMs}ms of virtual time",
                    )
                },
            )
            joiner1Room.roster.first { members -> members.none { it.id == joiner2Id } }
        }
}
