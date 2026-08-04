package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.election.LobbyMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry owns the room frame-prefix byte space (#2007).
 *
 * Before it existed, five discriminators were declared as loose `public const val`s in four
 * packages — one of them not even a byte (heartbeat declares a *String* whose first byte happens
 * to be `0x6b`) — with nothing but prose keeping them from colliding.
 */
class RoomFramePrefixTest {

    @Test
    fun `every reserved byte is distinct`() {
        val bytes = RoomFramePrefix.entries.map { it.byte }
        assertEquals(
            RoomFramePrefix.entries.size,
            bytes.toSet().size,
            "two frame families claim the same byte: $bytes",
        )
    }

    @Test
    fun `each family's own discriminator agrees with its reservation`() {
        assertAll(
            { assertEquals(RoomFramePrefix.Admit.byte, AdmitMessage.PREFIX_BYTE) },
            { assertEquals(RoomFramePrefix.Channel.byte, RoomChannel.CHANNEL_PREFIX) },
            { assertEquals(RoomFramePrefix.Lobby.byte, LobbyMessage.PREFIX_BYTE) },
            {
                // Heartbeat cannot derive from the registry — :kuilt-liveness does not (and must
                // not) depend on :kuilt-session. The registry reserves the byte; this pins the
                // correspondence in the only direction available.
                assertEquals(
                    RoomFramePrefix.Heartbeat.byte,
                    HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()[0],
                )
                assertEquals(
                    RoomFramePrefix.Heartbeat.byte,
                    HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()[0],
                )
            },
        )
    }

    @Test
    fun `matches keys on the first byte and tolerates an empty payload`() {
        assertAll(
            // Positive control: without this a `matches` that always returned false would pass
            // every negative below (spec correction C5).
            { assertTrue(RoomFramePrefix.Relay.matches(byteArrayOf(0x72, 0x01))) },
            { assertFalse(RoomFramePrefix.Relay.matches(byteArrayOf(0x71, 0x72))) },
            { assertFalse(RoomFramePrefix.Relay.matches(ByteArray(0))) },
        )
    }

    /**
     * `0x72` was legal application data before this track. Nothing in the registry can make an
     * application payload safe — this pins only that the *reservation* is what a relay frame is
     * recognised by, so the v2 release note ("a `Room.broadcast` payload starting `0x72` is now
     * swallowed as a relay frame") is truthful.
     */
    @Test
    fun `Relay reserves 0x72`() {
        assertEquals(0x72.toByte(), RoomFramePrefix.Relay.byte)
    }
}
