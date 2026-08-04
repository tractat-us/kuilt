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

    /**
     * Each entry's [RoomFramePrefix.classifies] is the family's **real** predicate — the one
     * `SeamRoom.dispatchIncoming` dispatches on — not the single-byte `matches`.
     *
     * A caller folding `matches` over the registry to ask "is this payload spoken for?" gets a
     * different answer from the dispatcher, silently, for the two families below. #1994's relay
     * allow-list did exactly that and dropped a spoke's `"keepalive"` broadcast.
     */
    @Test
    fun `classifies delegates to each family's real predicate`() {
        val genuineChannelFrame = byteArrayOf(RoomChannel.CHANNEL_PREFIX, 0x00, 0x01)
        val genuinePing = HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()
        assertAll(
            { assertTrue(RoomFramePrefix.Channel.classifies(genuineChannelFrame)) },
            { assertTrue(RoomFramePrefix.Heartbeat.classifies(genuinePing)) },
            { assertTrue(RoomFramePrefix.Relay.classifies(byteArrayOf(0x72, 0x01))) },
            { assertFalse(RoomFramePrefix.Channel.classifies(ByteArray(0))) },
            { assertFalse(RoomFramePrefix.Heartbeat.classifies(ByteArray(0))) },
        )
    }

    /**
     * The two families whose real predicate is **strictly narrower** than their reserved byte, and
     * the whole reason the registry has to carry a predicate at all.
     *
     * Both payloads here claim a reserved byte and are nonetheless ordinary application data on
     * `dispatchIncoming`'s direct path, so both must survive a relay. Asserted as the *pair*
     * `matches && !classifies`, which is what makes this a statement about the gap rather than
     * about either predicate alone — an implementation that made `classifies` an alias of
     * `matches` fails here even though every assertion in the test above still passes.
     */
    @Test
    fun `classifies is strictly narrower than matches for Channel and Heartbeat`() {
        val shortChannelClaim = byteArrayOf(RoomChannel.CHANNEL_PREFIX, 0x01)
        val plainKeepalive = "keepalive".encodeToByteArray()
        assertAll(
            { assertTrue(RoomFramePrefix.Channel.matches(shortChannelClaim), "it claims 0x63…") },
            {
                assertFalse(
                    RoomFramePrefix.Channel.classifies(shortChannelClaim),
                    "…but two bytes is too short to be a channel frame, which needs a 3-byte header",
                )
            },
            { assertTrue(RoomFramePrefix.Heartbeat.matches(plainKeepalive), "it claims 0x6b…") },
            {
                assertFalse(
                    RoomFramePrefix.Heartbeat.classifies(plainKeepalive),
                    "…but a heartbeat is the whole \"kuilt.heartbeat.ping\"/\"…pong\" string",
                )
            },
            // The control: the three families for which the two predicates DO coincide, so this
            // test says "narrower here specifically" rather than "narrower everywhere".
            {
                val admitFrame = byteArrayOf(AdmitMessage.PREFIX_BYTE, 0x01)
                assertTrue(
                    RoomFramePrefix.Admit.matches(admitFrame) &&
                        RoomFramePrefix.Admit.classifies(admitFrame),
                    "Admit's real predicate IS the single-byte test — no gap to close",
                )
            },
        )
    }
}
