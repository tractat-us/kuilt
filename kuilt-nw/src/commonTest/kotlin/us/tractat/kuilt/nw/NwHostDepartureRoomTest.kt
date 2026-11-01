@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A host's `leave` over [NwSeam]'s real send path (#2670), on the one fake that can lose a frame.
 *
 * [FakeNwRadio] destroys whatever is in flight when a link is cancelled, and a `Farewell` that has only
 * been queued on a room's fan-out lane has not reached the radio at all when `seam.close` runs. So this
 * reds on a `leave` that queues and closes, which `InMemoryLoom` cannot show. It is still a fake: the
 * real binding is `NwHostDepartureLoopbackTest`, and AWDL is real iPhones.
 */
class NwHostDepartureRoomTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val HOST_DEVICE = "dev-host"
    }

    private val heartbeat = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 5.seconds,
    )

    private class Rooms(val radio: FakeNwRadio, val host: Room, val joiner: Room, val events: List<MembershipEvent>)

    private suspend fun TestScope.formRoom(): Rooms {
        val radio = FakeNwRadio()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun factory(device: String, seed: Long) = SeamRoomFactory(
            NwLoom(FakeNwApi(radio, deviceId = device, serviceName = device), serviceType = SERVICE_TYPE, random = Random(seed)),
            backgroundScope,
            clock,
            heartbeat,
        )
        val hostRoom = async { factory(HOST_DEVICE, 0).host(Pattern("room")) }
        val joinerRoom = async {
            factory("dev-joiner", 1).join(InMemoryTag(sessionName = "room", peerKey = "dev-joiner"))
        }
        val host = hostRoom.await()
        val joiner = joinerRoom.await()
        host.roster.first { it.size == 1 }
        joiner.roster.first { it.size == 1 }
        val events = mutableListOf<MembershipEvent>()
        backgroundScope.launch { joiner.events.collect { events += it } }
        testScheduler.runCurrent()
        return Rooms(radio, host, joiner, events)
    }

    /** The radio's record of every `Farewell` the host handed it, and what became of it. */
    private fun FakeNwRadio.farewells(): List<SentFrame> =
        sentFrames.filter { frame -> frame.fromDeviceId == HOST_DEVICE && frame.isFarewell() }

    /** The ledger keeps the framed bytes, so the admit frame sits behind the length prefix and type byte. */
    private fun SentFrame.isFarewell(): Boolean {
        val headerBytes = 4 + NwWire.TYPE_BYTES
        if (bytes.size <= headerBytes) return false
        return AdmitMessage.decode(bytes.copyOfRange(headerBytes, bytes.size)) is AdmitMessage.Farewell
    }

    private fun List<MembershipEvent>.hostLost() = filterIsInstance<MembershipEvent.HostLost>()

    @Test
    fun theFrameLeavesBeforeTheLinkIsCancelled() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rooms = formRoom()
            val before = testScheduler.currentTime

            rooms.host.leave()
            testScheduler.runCurrent()
            val elapsed = testScheduler.currentTime - before
            testScheduler.advanceTimeBy(heartbeat.reconnectWindow * 2)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf(SendFate.Delivered), rooms.radio.farewells().map { it.fate }, "the radio's ledger") },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), rooms.events.hostLost().map { it.reason }) },
                { assertTrue(rooms.events.none { it is MembershipEvent.WindowOpened }, "no window: ${rooms.events}") },
                { assertTrue(elapsed < heartbeat.reconnectWindow.inWholeMilliseconds, "took ${elapsed}ms") },
            )
        }

    /** Held in the radio past the close, so destroyed in flight: exactly #2830's loss, on this fabric. */
    @Test
    fun aFrameDestroyedInFlightFallsBackToTheWindow() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rooms = formRoom()
            rooms.radio.openedLinks.forEach { link ->
                link.endOn(HOST_DEVICE)?.let { rooms.radio.holdSends(it) }
            }

            rooms.host.leave()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(heartbeat.reconnectWindow * 2)
            testScheduler.runCurrent()

            assertAll(
                // Rig receipt: the frame existed and the radio destroyed it. A leave that sent nothing
                // would reach the same window, and this arm would be proving nothing about a loss.
                { assertEquals(listOf(SendFate.DiscardedOnClose), rooms.radio.farewells().map { it.fate }) },
                { assertTrue(rooms.events.any { it is MembershipEvent.WindowOpened }, "today's window: ${rooms.events}") },
                { assertEquals(1, rooms.events.hostLost().size, "terminal once: ${rooms.events}") },
                { assertNotEquals<FailureReason>(FailureReason.HostLeft, rooms.events.hostLost().single().reason) },
            )
        }
}
