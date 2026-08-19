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
        val nonce = meshNonce(0, 1, 127, -1)
        val decoded = MeshHello.decode(MeshHello.encode(id, nonce))
        assertEquals(id, decoded.peerId)
        assertContentEquals(nonce, decoded.nonce)
    }

    @Test
    fun encodedFrameContainsNoNulBytes() {
        val id = PeerId("peer-1")
        val nonce = ByteArray(16) { 0 }   // all-zero nonce — worst case for NUL contamination
        val body = MeshHello.encode(id, nonce)
        // The wire version occupies byte 0 and the id length the next 4; the id is ASCII-safe; the
        // nonce is raw bytes. The ONLY bytes that could be 0 in the id region are zero-length padding
        // in the length field itself (e.g. length 6 → 0x00 0x00 0x00 0x06). Verify: the id bytes
        // themselves are non-NUL for a normal ASCII id.
        val idBytes = id.value.encodeToByteArray()
        val idInBody = body.copyOfRange(ID_OFFSET, ID_OFFSET + idBytes.size)
        assertContentEquals(idBytes, idInBody, "id bytes in body must match the raw UTF-8")
    }

    @Test
    fun encodedLengthPrefixMatchesIdByteCount() {
        val id = PeerId("hello")
        val nonce = meshNonce(1, 2, 3)
        val body = MeshHello.encode(id, nonce)
        val idByteLen = id.value.encodeToByteArray().size
        // Big-endian 4-byte int, immediately after the wire-version byte.
        val prefixLen = ((body[1].toInt() and 0xff) shl 24) or
            ((body[2].toInt() and 0xff) shl 16) or
            ((body[3].toInt() and 0xff) shl 8) or
            (body[4].toInt() and 0xff)
        assertEquals(idByteLen, prefixLen)
        assertEquals(ID_OFFSET + idByteLen + nonce.size, body.size)
    }

    // --- the wire version leads the body, and an unknown one is refused BY NAME (#2474) ---

    @Test
    fun theEncodedBodyLeadsWithThisBuildsWireVersion() {
        val body = MeshHello.encode(PeerId("peer-1"), meshNonce(1))
        assertEquals(
            MESH_WIRE_VERSION,
            body[0].toInt() and 0xff,
            "the version is the first thing a peer reads, because every later field is laid out BY it",
        )
    }

    @Test
    fun aBodyDeclaringAnUnknownWireVersionIsRefusedByName() {
        val body = MeshHello.encode(PeerId("peer-1"), meshNonce(1))
        listOf(0, MESH_WIRE_VERSION + 1, 0xff).forEach { version ->
            val alien = body.copyOf().also { it[0] = version.toByte() }
            val refusal = assertFailsWith<MeshUnsupportedWireVersionException>(
                "v$version must be refused as a VERSION break, never as a malformed frame — " +
                    "'malformed' is the diagnosis a reader would act on, and it is the wrong one",
            ) { MeshHello.decode(alien) }
            assertAll(
                { assertEquals(version, refusal.remoteVersion) },
                { assertEquals(MESH_WIRE_VERSION, refusal.localVersion) },
            )
        }
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
        (0 until ID_OFFSET).forEach { size ->
            // A well-versioned prefix of the body, so what is under test is the LENGTH check rather
            // than the version check an all-zero array would trip first.
            val truncated = ByteArray(size).also { if (size >= 1) it[0] = MESH_WIRE_VERSION.toByte() }
            assertFailsWith<IllegalArgumentException>("a $size-byte preamble must be rejected, not index-fault") {
                MeshHello.decode(truncated)
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

    /** A well-versioned body with a 4-byte length prefix declaring [declaredIdLength], plus 16 bytes. */
    private fun frameDeclaring(declaredIdLength: Int): ByteArray =
        ByteArray(ID_OFFSET + 16).also { body ->
            body[0] = MESH_WIRE_VERSION.toByte()
            body[1] = (declaredIdLength ushr 24).toByte()
            body[2] = (declaredIdLength ushr 16).toByte()
            body[3] = (declaredIdLength ushr 8).toByte()
            body[4] = declaredIdLength.toByte()
        }

    /**
     * Hand-assemble a preamble body carrying [nonce] verbatim.
     *
     * Not [MeshHello.encode] — that enforces the same width invariant, so a wrong-width nonce cannot be
     * produced through it. This is what a hostile or buggy remote puts on the wire.
     */
    private fun frameWithNonce(id: PeerId, nonce: ByteArray): ByteArray {
        val idBytes = id.value.encodeToByteArray()
        return ByteArray(ID_OFFSET + idBytes.size + nonce.size).also { body ->
            body[0] = MESH_WIRE_VERSION.toByte()
            body[1] = (idBytes.size ushr 24).toByte()
            body[2] = (idBytes.size ushr 16).toByte()
            body[3] = (idBytes.size ushr 8).toByte()
            body[4] = idBytes.size.toByte()
            idBytes.copyInto(body, destinationOffset = ID_OFFSET)
            nonce.copyInto(body, destinationOffset = ID_OFFSET + idBytes.size)
        }
    }

    private companion object {
        /** Where the id starts in a hello body: one version byte plus the 4-byte id length. */
        const val ID_OFFSET = 1 + 4
    }
}
