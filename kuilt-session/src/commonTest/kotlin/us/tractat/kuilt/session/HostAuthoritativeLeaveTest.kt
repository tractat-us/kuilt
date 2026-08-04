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
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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

    @Test
    fun `joiner ignores a Farewell forged by a non-host peer`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)

            val hostRoom = factory.host(Pattern("Host"))
            val joiner1Room = factory.join(InMemoryTag("Joiner1"))
            val joiner2Room = factory.join(InMemoryTag("Joiner2"))

            hostRoom.roster.first { it.size == 2 }
            joiner1Room.roster.first { it.size == 2 }
            joiner2Room.roster.first { it.size == 2 }

            // Host-authoritative gate: a Farewell is honored only from the identified host.
            // joiner2 forges one naming the host — joiner1 must NOT evict anyone on it.
            val forged = AdmitMessage.encode(AdmitMessage.Farewell(hostRoom.selfId.value))
            joiner2Room.broadcast(forged)
            testScheduler.advanceTimeBy(fastConfig.interval)
            testScheduler.runCurrent()

            val roster = joiner1Room.roster.value
            assertAll(
                {
                    assertTrue(
                        roster.any { it.id == hostRoom.selfId },
                        "forged Farewell from a non-host peer must not evict the host",
                    )
                },
                { assertEquals(2, roster.size, "joiner1's roster must be untouched by the forgery") },
            )
        }
}
