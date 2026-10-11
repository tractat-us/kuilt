@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomReservedPrefixTest {
    @Test
    fun admitIsRefused() = refuses(RoomFramePrefix.Admit, "a")

    @Test
    fun channelIsRefused() = refuses(RoomFramePrefix.Channel, "c\u0000\u0001")

    @Test
    fun lobbyIsRefused() = refuses(RoomFramePrefix.Lobby, "e")

    @Test
    fun relayIsRefused() = refuses(RoomFramePrefix.Relay, "r")

    @Test
    fun heartbeatPingIsRefused() =
        refuses(RoomFramePrefix.Heartbeat, HeartbeatPartitionDetector.PING_PREFIX)

    @Test
    fun heartbeatPongIsRefused() =
        refuses(RoomFramePrefix.Heartbeat, HeartbeatPartitionDetector.PONG_PREFIX)

    private fun refuses(prefix: RoomFramePrefix, text: String) = runTest(StandardTestDispatcher()) {
        val star = relayStar()
        // The host sends directly; a spoke with a co-spoke routes through the host.
        for (sender in listOf(star.host, star.joinerA)) {
            for (broadcast in listOf(true, false)) {
                val before = sender.wire.sentFrameCount()
                val failure = assertFailsWith<ReservedRoomPrefix>("$prefix, ${sender.id}, broadcast=$broadcast") {
                    if (broadcast) sender.room.broadcast(text.encodeToByteArray())
                    else sender.room.sendTo(star.joinerBId, text.encodeToByteArray())
                }
                assertAll(
                    { assertEquals(prefix, failure.prefix) },
                    { assertTrue(failure.message.orEmpty().contains(prefix.name)) },
                    { assertEquals(before, sender.wire.sentFrameCount(), "refusal must precede every wire send") },
                )
            }
        }
    }

    @Test
    fun ordinaryAndConditionallyUnreservedPayloadsSurviveBothRoutes() = runTest(StandardTestDispatcher()) {
        val star = relayStar()
        val payloads = listOf("", "plain", "c", "c\u0001", "keepalive", "kuilt.heartbeat.pin")
        for (sender in listOf(star.host, star.joinerA)) {
            for (payload in payloads) {
                val sent = runCatchingCancellable {
                    sender.room.broadcast(payload.encodeToByteArray())
                    sender.room.sendTo(star.joinerBId, payload.encodeToByteArray())
                }
                assertTrue(sent.isSuccess, "valid raw payload refused: ${sent.exceptionOrNull()}")
            }
            testScheduler.runCurrent()
            assertEquals(payloads.flatMap { listOf(it, it) }, star.joinerB.appFramesFrom(sender.id))
        }
    }

    @Test
    fun channelsCarryEveryReservedPayloadThroughBothRoutes() = runTest(StandardTestDispatcher()) {
        val star = relayStar()
        val received = mutableListOf<String>()
        val channel = star.joinerB.room.channel("data")
        backgroundScope.launch { channel.incoming.collect { received += it.decodeToString() } }
        testScheduler.runCurrent()
        val payloads = listOf(
            "a", "c\u0000\u0001", "e", "r",
            HeartbeatPartitionDetector.PING_PREFIX, HeartbeatPartitionDetector.PONG_PREFIX,
        )
        for (sender in listOf(star.host, star.joinerA)) {
            val outgoing = sender.room.channel("data")
            for (payload in payloads) {
                val sent = runCatchingCancellable {
                    outgoing.broadcast(payload.encodeToByteArray())
                    arbitraryRoomPayloadSample(sender.room, star.joinerBId, payload.encodeToByteArray())
                }
                assertTrue(sent.isSuccess, "channel payload refused: ${sent.exceptionOrNull()}")
            }
            testScheduler.runCurrent()
        }
        assertEquals(List(2) { payloads.flatMap { listOf(it, it) } }.flatten(), received)
    }
}
