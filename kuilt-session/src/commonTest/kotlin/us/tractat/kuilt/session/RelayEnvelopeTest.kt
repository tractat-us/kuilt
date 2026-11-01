package us.tractat.kuilt.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
@OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)
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

    /**
     * The wire break the release note declares, pinned on frozen bytes (#2958). A build on either side
     * of `alwaysUseByteString` drops the other's relayed frames: the two framings are different CBOR
     * major types, so the decode fails rather than misreading a payload. [wirePayload] straddles CBOR's
     * short range, or the two framings would agree more closely and pin less.
     *
     * [preChangeCodec] restates the old config on purpose. It exists to prove [PRE_CHANGE_FRAME] really
     * is a well-formed old frame, so its refusal below is about the framing and not about bad bytes.
     */
    @Test
    fun `this build and a build before byte strings refuse each other's frames`() {
        val preChange = PRE_CHANGE_FRAME.hexToByteArray()
        val current = CURRENT_FRAME.hexToByteArray()
        fun oldDecode(frame: ByteArray): RelayEnvelope =
            preChangeCodec.decodeFromByteArray(frame.copyOfRange(1, frame.size))

        assertAll(
            { assertTrue(wirePayload.any { it in -24..23 } && wirePayload.any { it !in -24..23 }, "rig: straddles the short range") },
            { assertEquals(wireEnvelope, oldDecode(preChange), "rig: the frozen frame is a real pre-change frame") },
            { assertNull(RelayEnvelope.decode(preChange), "this build must refuse a pre-change frame") },
            { assertEquals(CURRENT_FRAME, RelayEnvelope.encode(wireEnvelope).toHexString(), "this build's own bytes") },
            { assertEquals(wireEnvelope, RelayEnvelope.decode(current), "and it reads them back") },
            { assertFailsWith<SerializationException>("a pre-change build must refuse them") { oldDecode(current) } },
        )
    }

    private val wirePayload = byteArrayOf(0x00, 0x17, 0x18, 0x62, -1, -24, -25)
    private val wireEnvelope = RelayEnvelope(PeerId("a"), RelayDest.Everyone, wirePayload)
    private val preChangeCodec = Cbor { ignoreUnknownKeys = true }

    private companion object {
        const val PRE_CHANGE_FRAME = "72bf666f726967696e616164646573749f63616c6cbfffff677061796c6f61649f00171818186220373818ffff"
        const val CURRENT_FRAME = "72bf666f726967696e616164646573749f63616c6cbfffff677061796c6f61644700171862ffe8e7ff"
    }
}
