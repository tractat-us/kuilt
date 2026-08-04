package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire envelope a host forwards between two spokes of a star (#1994).
 *
 * [RelayEnvelope.origin] is the peer that minted the payload — the fabric stamps the *relaying*
 * host as the sender, so without this field the far end would credit the host. It is carried
 * inside a forgeable frame and is therefore first-hop-validated by the receiver, never trusted.
 */
class RelayEnvelopeTest {

    private val origin = PeerId("joiner-a")
    private val target = PeerId("joiner-b")
    private val payload = byteArrayOf(0x63, 0x00, 0x07, 0x2a, 0x2b)

    @Test
    fun `an Everyone envelope round-trips with origin and payload intact`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        val decoded = assertNotNull(RelayEnvelope.decode(encoded))
        assertAll(
            { assertEquals(origin, decoded.origin) },
            { assertEquals(RelayDest.Everyone, decoded.dest) },
            { assertContentEquals(payload, decoded.payload) },
        )
    }

    @Test
    fun `a One envelope round-trips with its target intact`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.One(target), payload))
        val decoded = assertNotNull(RelayEnvelope.decode(encoded))
        assertAll(
            { assertEquals(origin, decoded.origin) },
            { assertEquals(RelayDest.One(target), decoded.dest) },
            { assertContentEquals(payload, decoded.payload) },
        )
    }

    /**
     * The two destinations must not decode to each other. A codec that dropped `dest` entirely
     * would pass both round-trip tests above independently; this is what makes them meaningful.
     */
    @Test
    fun `Everyone and One are distinguishable on the wire`() {
        val everyone = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload)),
        )
        val one = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.One(target), payload)),
        )
        assertAll(
            { assertEquals(RelayDest.Everyone, assertNotNull(everyone).dest) },
            { assertEquals(RelayDest.One(target), assertNotNull(one).dest) },
        )
    }

    @Test
    fun `an encoded envelope claims the registry's relay prefix`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        assertAll(
            { assertEquals(RoomFramePrefix.Relay.byte, encoded[0]) },
            { assertTrue(RelayEnvelope.isRelayFrame(encoded)) },
        )
    }

    @Test
    fun `a garbled body decodes to null rather than throwing`() {
        val good = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        val garbled = byteArrayOf(RoomFramePrefix.Relay.byte, 0x01, 0x02, 0x03)
        assertAll(
            // Positive control: a decoder that returned null unconditionally would satisfy every
            // negative in this test (spec correction C5).
            { assertNotNull(RelayEnvelope.decode(good)) },
            { assertNull(RelayEnvelope.decode(garbled)) },
            { assertNull(RelayEnvelope.decode(ByteArray(0))) },
        )
    }

    /**
     * [RelayDest]'s subtypes must ride the wire under their explicit `@SerialName`s.
     *
     * Without the annotations kotlinx derives each discriminator from the fully-qualified class
     * name — and **every other test in this file still passes**, because a round-trip is symmetric
     * in whatever discriminator it chose. A package move or rename would then break cross-version
     * wire compatibility with no compile error and no red test. Verified by mutation: deleting both
     * `@SerialName`s leaves the other six green and fails only this one.
     */
    @Test
    fun `RelayDest rides the wire under its stable discriminator rather than a derived class name`() {
        val everyone = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        val one = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.One(target), payload))
        assertAll(
            { assertTrue(everyone.containsAscii("all"), "Everyone lost its @SerialName") },
            { assertTrue(one.containsAscii("one"), "One lost its @SerialName") },
            // The negative the positives above cannot cover: a compiler-derived discriminator
            // carries the class name, which is precisely what @SerialName keeps off the wire.
            { assertFalse(everyone.containsAscii("RelayDest"), "Everyone leaked a class name") },
            { assertFalse(one.containsAscii("RelayDest"), "One leaked a class name") },
        )
    }

    /**
     * Byte-level substring search. Deliberately not `decodeToString().contains(…)`: CBOR bodies are
     * not valid UTF-8, and a malformed lead byte can swallow the ASCII run that follows it.
     */
    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.encodeToByteArray()
        if (target.isEmpty() || target.size > size) return false
        outer@ for (start in 0..size - target.size) {
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun `a frame claiming another family's prefix is not a relay frame`() {
        val channelFrame = byteArrayOf(RoomFramePrefix.Channel.byte, 0x00, 0x01)
        assertAll(
            {
                assertTrue(
                    RelayEnvelope.isRelayFrame(
                        RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload)),
                    ),
                )
            },
            { assertFalse(RelayEnvelope.isRelayFrame(channelFrame)) },
            { assertNull(RelayEnvelope.decode(channelFrame)) },
        )
    }
}
