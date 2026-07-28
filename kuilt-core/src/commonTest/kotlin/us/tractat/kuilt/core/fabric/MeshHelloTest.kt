package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-trip tests for [MeshHello] length-prefix encoding (fixes #427), plus rejection of the malformed
 * preambles a remote can send (#1788).
 *
 * The wire format is `[4-byte big-endian id length][id UTF-8 bytes][nonce bytes]` — no delimiter,
 * no hex encoding, no NUL bytes in the source or on the wire.
 */
class MeshHelloTest {

    @Test
    fun roundTripAsciiIdAndNonce() {
        val id = PeerId("peer-alice")
        val nonce = ByteArray(16) { it.toByte() }
        val decoded = MeshHello.decode(MeshHello.encode(id, nonce))
        assertEquals(id, decoded.peerId)
        assertContentEquals(nonce, decoded.nonce)
    }

    @Test
    fun roundTripMultiByteUtf8Id() {
        // Id with multi-byte UTF-8 code points — id length in the prefix is byte count, not char count.
        val id = PeerId("中文-αβ")
        val nonce = ByteArray(16) { (it + 100).toByte() }
        val decoded = MeshHello.decode(MeshHello.encode(id, nonce))
        assertEquals(id, decoded.peerId)
        assertContentEquals(nonce, decoded.nonce)
    }

    @Test
    fun roundTripSingleCharId() {
        val id = PeerId("X")
        val nonce = byteArrayOf(0, 1, 127, -1)
        val decoded = MeshHello.decode(MeshHello.encode(id, nonce))
        assertEquals(id, decoded.peerId)
        assertContentEquals(nonce, decoded.nonce)
    }

    @Test
    fun encodedFrameContainsNoNulBytes() {
        val id = PeerId("peer-1")
        val nonce = ByteArray(16) { 0 }   // all-zero nonce — worst case for NUL contamination
        val frame = MeshHello.encode(id, nonce)
        // The id length occupies the first 4 bytes; the id is ASCII-safe; the nonce is raw bytes.
        // The ONLY bytes that could be 0 in the id region are zero-length padding in the length
        // field itself (e.g. length 6 → 0x00 0x00 0x00 0x06). Verify: the id bytes themselves
        // are non-NUL for a normal ASCII id.
        val idBytes = id.value.encodeToByteArray()
        val idInFrame = frame.copyOfRange(4, 4 + idBytes.size)
        assertContentEquals(idBytes, idInFrame, "id bytes in frame must match the raw UTF-8")
    }

    @Test
    fun encodedLengthPrefixMatchesIdByteCount() {
        val id = PeerId("hello")
        val nonce = byteArrayOf(1, 2, 3)
        val frame = MeshHello.encode(id, nonce)
        val idByteLen = id.value.encodeToByteArray().size
        // Big-endian 4-byte int at offset 0
        val prefixLen = ((frame[0].toInt() and 0xff) shl 24) or
            ((frame[1].toInt() and 0xff) shl 16) or
            ((frame[2].toInt() and 0xff) shl 8) or
            (frame[3].toInt() and 0xff)
        assertEquals(idByteLen, prefixLen)
        assertEquals(4 + idByteLen + nonce.size, frame.size)
    }

    // --- malformed input a REMOTE can send (#1788) ---
    //
    // The preamble is the first bytes a remote sends, so these are reachable inputs, not local mistakes.
    // Each defeated the decoder before the fix: a short frame index-faulted inside `readInt` (there was no
    // check at all), and both a negative and an overflowing declared length pass any additive
    // `size >= 4 + idLen` test. `MeshSeam` reached the decoder from `buildMesh`'s concurrent handshakes and
    // from a hub's accept pump, so relying on a caller's guard was the wrong place for the check.

    @Test
    fun aFrameTooShortForTheLengthPrefixIsRejected() {
        (0 until 4).forEach { size ->
            assertFailsWith<IllegalArgumentException>("a $size-byte preamble must be rejected, not index-fault") {
                MeshHello.decode(ByteArray(size))
            }
        }
    }

    @Test
    fun aNegativeOrOverflowingDeclaredIdLengthIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { MeshHello.decode(frameDeclaring(-1)) } },
            { assertFailsWith<IllegalArgumentException> { MeshHello.decode(frameDeclaring(Int.MIN_VALUE)) } },
            { assertFailsWith<IllegalArgumentException> { MeshHello.decode(frameDeclaring(Int.MAX_VALUE)) } },
            // The plain truncation case too: a length larger than the frame but small enough not to wrap.
            { assertFailsWith<IllegalArgumentException> { MeshHello.decode(frameDeclaring(1024)) } },
        )
    }

    // --- the nonce is a FIXED-WIDTH field, not a variable-length tail (#1812) ---
    //
    // The decoded nonce reaches `canonicalLinkNonce`, which hex-encodes both endpoints' nonces, sorts
    // the two strings and joins them — that string IS the link identity both ends of a mesh dedup on.
    // A wrong-width nonce is therefore proof of a malformed or forged preamble, and the frame is
    // REJECTED rather than reshaped: truncating or padding it to the declared width would launder the
    // forgery into a valid-looking identity. A zero-length nonce is the concrete collapse — every peer
    // that sends one contributes the empty string, so two distinct misbehaving peers derive the SAME
    // canonical link identity and dedup can drop a link that is genuinely distinct.

    @Test
    fun aNonceOfTheWrongWidthIsRejected() {
        val id = PeerId("peer-1")
        listOf(0, 1, MESH_NONCE_BYTES - 1, MESH_NONCE_BYTES + 1, 2 * MESH_NONCE_BYTES).forEach { width ->
            assertFailsWith<IllegalArgumentException>("a $width-byte nonce must be rejected, not accepted as identity") {
                MeshHello.decode(frameWithNonce(id, ByteArray(width) { 7 }))
            }
        }
    }

    @Test
    fun anExactWidthNonceIsStillAccepted() {
        val id = PeerId("peer-1")
        val nonce = ByteArray(MESH_NONCE_BYTES) { (it * 3).toByte() }
        val decoded = MeshHello.decode(frameWithNonce(id, nonce))
        assertAll(
            { assertEquals(id, decoded.peerId) },
            { assertContentEquals(nonce, decoded.nonce, "an exact-width nonce must survive the round trip") },
        )
    }

    /** The encoder carries the same invariant, so a conforming peer can never emit a short preamble. */
    @Test
    fun encodingAWrongWidthNonceIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { MeshHello.encode(PeerId("peer-1"), ByteArray(0)) } },
            { assertFailsWith<IllegalArgumentException> { MeshHello.encode(PeerId("peer-1"), byteArrayOf(1, 2, 3)) } },
        )
    }

    /** A well-formed 4-byte length prefix declaring [declaredIdLength], plus a 16-byte body. */
    private fun frameDeclaring(declaredIdLength: Int): ByteArray =
        ByteArray(4 + 16).also { frame ->
            frame[0] = (declaredIdLength ushr 24).toByte()
            frame[1] = (declaredIdLength ushr 16).toByte()
            frame[2] = (declaredIdLength ushr 8).toByte()
            frame[3] = declaredIdLength.toByte()
        }

    /**
     * Hand-assemble a preamble carrying [nonce] verbatim.
     *
     * Not [MeshHello.encode] — that enforces the same width invariant, so a wrong-width nonce cannot be
     * produced through it. This is what a hostile or buggy remote puts on the wire.
     */
    private fun frameWithNonce(id: PeerId, nonce: ByteArray): ByteArray {
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
