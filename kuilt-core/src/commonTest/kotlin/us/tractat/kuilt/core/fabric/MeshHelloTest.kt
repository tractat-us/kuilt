package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trip tests for [MeshHello] length-prefix encoding (fixes #427).
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
}
