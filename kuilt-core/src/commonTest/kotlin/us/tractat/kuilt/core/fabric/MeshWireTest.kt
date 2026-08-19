package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The self-describing mesh frame body (#2474): a leading type byte, then the body.
 *
 * Ported from `:kuilt-nw`'s `NwWireTest` alongside the wire it covers — the two fabrics keep
 * independent wires that happen to have the same shape today, exactly as they keep independent
 * canonical-nonce tiebreaks.
 */
class MeshWireTest {

    // --- the codes are EXPLICIT, and that is a wire property rather than a style one -------------

    @Test
    fun everyFrameTypeCodeIsStableAndDistinct() {
        val codes = MeshFrameType.entries.map { it.code }
        assertAll(
            { assertEquals(codes.size, codes.toSet().size, "two types sharing a code would be unroutable") },
            {
                assertEquals(
                    listOf<Byte>(0x01, 0x02, 0x03),
                    codes,
                    "these bytes ARE the wire; changing one is a flag day, not a refactor",
                )
            },
        )
    }

    /**
     * An encoder that derived the wire value from `ordinal` would produce 0/1/2 and pass every
     * round-trip test in this file, then silently renumber the wire the first time a case is
     * inserted. No code coinciding with its ordinal is what makes that impersonation impossible.
     */
    @Test
    fun noFrameTypeCodeCoincidesWithItsOrdinal() {
        MeshFrameType.entries.forEach { type ->
            assertTrue(
                type.code.toInt() != type.ordinal,
                "${type.name}'s code 0x${type.code} equals its ordinal ${type.ordinal}, so an " +
                    "ordinal-derived encoder would be indistinguishable from this one",
            )
        }
    }

    // --- round trips ---------------------------------------------------------------------------

    @Test
    fun aHelloRoundTripsThroughItsTypeByte() {
        val id = PeerId("peer-1")
        val nonce = meshNonce(9, 9)
        val decoded = MeshWire.decode(MeshWire.encodeHello(id, nonce))
        val hello = assertIs<MeshWireFrame.Hello>(decoded).hello
        assertAll(
            { assertEquals(id, hello.peerId) },
            { assertContentEquals(nonce, hello.nonce) },
        )
    }

    @Test
    fun aDataFrameIsItsPayloadBehindOneTypeByte() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = MeshWire.encodeData(payload)
        assertAll(
            { assertEquals(MeshFrameType.Data.code, frame[0], "the type byte leads") },
            { assertEquals(MeshWire.TYPE_BYTES + payload.size, frame.size, "and costs exactly one byte") },
            { assertContentEquals(payload, frame.copyOfRange(MeshWire.TYPE_BYTES, frame.size)) },
            { assertEquals(MeshWireFrame.Data, MeshWire.decode(frame)) },
        )
    }

    /**
     * An empty payload is a frame, not a fault. `Seam.broadcast(ByteArray(0))` is legal, and a
     * decoder that treated a bodyless DATA as truncated would turn a legal send into a torn link.
     */
    @Test
    fun anEmptyDataPayloadIsAFrameNotAFault() {
        assertEquals(MeshWireFrame.Data, MeshWire.decode(MeshWire.encodeData(ByteArray(0))))
    }

    @Test
    fun aGoodbyeIsExactlyOneByte() {
        val frame = MeshWire.encodeGoodbye()
        assertAll(
            { assertContentEquals(byteArrayOf(MeshFrameType.Goodbye.code), frame) },
            { assertEquals(MeshWireFrame.Goodbye, MeshWire.decode(frame)) },
        )
    }

    /**
     * The goodbye's body is RESERVED: a future build may add a drain reason, and a build that does
     * not know a reserved field must skip it rather than tear the link over it. Only the type byte
     * is never reserved.
     */
    @Test
    fun aGoodbyeCarryingAReservedBodyIsAcceptedAndItsBodyIgnored() {
        val withTail = MeshWire.encodeGoodbye() + byteArrayOf(0x7f, 0x00, 0x11)
        assertEquals(MeshWireFrame.Goodbye, MeshWire.decode(withTail))
    }

    // --- refusals name WHAT was wrong ------------------------------------------------------------

    @Test
    fun anEmptyFrameCannotEvenBeClassified() {
        assertFailsWith<MeshTruncatedFrameException> { MeshWire.decode(ByteArray(0)) }
    }

    @Test
    fun anUnknownTypeByteIsRefusedByName() {
        val refusal = assertFailsWith<MeshUnknownFrameTypeException> { MeshWire.decode(byteArrayOf(0x5a, 1, 2)) }
        assertEquals(0x5a.toByte(), refusal.code, "the refusal names the byte it could not classify")
    }

    /**
     * The flag-day hint. A pre-#2474 body was `[idLen_be32][id][nonce]`, and the top byte of a
     * big-endian id length is `0x00` for every id shorter than 16 MiB — so a leading `0x00` is, in
     * practice, the signature of a peer on the older wire. Saying so is the difference between a
     * reader diagnosing a version break and a reader chasing data corruption.
     */
    @Test
    fun aLeadingZeroByteGetsTheOlderWireDiagnosis() {
        val refusal = assertFailsWith<MeshUnknownFrameTypeException> {
            MeshWire.decode(MeshHelloOnTheOldWire.of(PeerId("peer-1"), meshNonce(1)))
        }
        assertAll(
            { assertEquals(0x00.toByte(), refusal.code) },
            { assertContains(refusal.message.orEmpty(), "older build") },
            { assertContains(refusal.message.orEmpty(), "cannot form a session") },
        )
    }

    /** A hello whose body declares a wire version this build does not implement is refused as such. */
    @Test
    fun anUnknownWireVersionInsideAHelloIsRefusedAsAVersionBreak() {
        val frame = MeshWire.encodeHello(PeerId("peer-1"), meshNonce(1))
        frame[MeshWire.TYPE_BYTES] = (MESH_WIRE_VERSION + 1).toByte()
        val refusal = assertFailsWith<MeshUnsupportedWireVersionException> { MeshWire.decode(frame) }
        assertEquals(MESH_WIRE_VERSION + 1, refusal.remoteVersion)
    }

    /** The pre-#2474 preamble, hand-assembled: `[idLen_be32][id][nonce]`, with no type byte at all. */
    private object MeshHelloOnTheOldWire {
        fun of(id: PeerId, nonce: ByteArray): ByteArray {
            val idBytes = id.value.encodeToByteArray()
            return ByteArray(4 + idBytes.size + nonce.size).also { frame ->
                frame[0] = (idBytes.size ushr 24).toByte()
                frame[1] = (idBytes.size ushr 16).toByte()
                frame[2] = (idBytes.size ushr 8).toByte()
                frame[3] = idBytes.size.toByte()
                idBytes.copyInto(frame, destinationOffset = 4)
                nonce.copyInto(frame, destinationOffset = 4 + idBytes.size)
            }
        }
    }
}
