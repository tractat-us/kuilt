package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [NwHello] round-trips, and rejects the malformed preambles a remote can send (#1788).
 *
 * The preamble is the **first bytes a remote sends** — `[4-byte big-endian id length][id UTF-8][nonce]` —
 * so a malformed one is reachable input, not a local mistake. Before the fix there was no length check at
 * all: a frame shorter than the prefix index-faulted inside `readInt`, and both a negative declared length
 * (the prefix is read as a signed `Int`) and one that overflows `4 + idLen` would pass any additive bounds
 * test. `NwSeam.processFrame` does guard the call site, so the consequence there was a disconnected
 * connection rather than a dead loop — but the check belongs in the decoder, which is the only place that
 * can say *what* was wrong.
 */
class NwHelloTest {

    @Test
    fun roundTripsIdAndNonce() {
        val id = PeerId("peer-alice")
        val nonce = ByteArray(NONCE_BYTES) { it.toByte() }
        val decoded = NwHello.decode(NwHello.encode(id, nonce))
        assertAll(
            { assertEquals(id, decoded.peerId) },
            { assertContentEquals(nonce, decoded.nonce) },
        )
    }

    @Test
    fun aFrameTooShortForTheLengthPrefixIsRejected() {
        (0 until Int.SIZE_BYTES).forEach { size ->
            assertFailsWith<IllegalArgumentException>("a $size-byte preamble must be rejected, not index-fault") {
                NwHello.decode(ByteArray(size))
            }
        }
    }

    @Test
    fun aNegativeOrOverflowingDeclaredIdLengthIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(-1)) } },
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(Int.MIN_VALUE)) } },
            // 0x7fffffff — the exact garbage length NwSeamTest feeds the receive loop.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(Int.MAX_VALUE)) } },
            // The plain truncation case too: larger than the frame but small enough not to wrap.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(1024)) } },
        )
    }

    // --- the nonce is a FIXED-WIDTH field, not a variable-length tail (#1812) ---
    //
    // The decoded nonce reaches [canonicalLinkNonce], which hex-encodes both endpoints' nonces, sorts
    // the two strings and joins them — that string IS the link identity both ends dedup on. A
    // wrong-width nonce is proof of a malformed or forged preamble, and the frame is REJECTED rather
    // than reshaped: truncating or padding it to the declared width would launder the forgery into a
    // valid-looking identity. A zero-length nonce is the concrete collapse — every peer that sends one
    // contributes the empty string, so two distinct misbehaving peers derive the SAME link identity.

    @Test
    fun aNonceOfTheWrongWidthIsRejected() {
        val id = PeerId("peer-1")
        listOf(0, 1, NONCE_BYTES - 1, NONCE_BYTES + 1, 2 * NONCE_BYTES).forEach { width ->
            assertFailsWith<IllegalArgumentException>("a $width-byte nonce must be rejected, not accepted as identity") {
                NwHello.decode(frameWithNonce(id, ByteArray(width) { 7 }))
            }
        }
    }

    @Test
    fun anExactWidthNonceIsStillAccepted() {
        val id = PeerId("peer-1")
        val nonce = ByteArray(NONCE_BYTES) { (it * 3).toByte() }
        val decoded = NwHello.decode(frameWithNonce(id, nonce))
        assertAll(
            { assertEquals(id, decoded.peerId) },
            { assertContentEquals(nonce, decoded.nonce, "an exact-width nonce must survive the round trip") },
        )
    }

    /** The encoder carries the same invariant, so a conforming peer can never emit a short preamble. */
    @Test
    fun encodingAWrongWidthNonceIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { NwHello.encode(PeerId("peer-1"), ByteArray(0)) } },
            { assertFailsWith<IllegalArgumentException> { NwHello.encode(PeerId("peer-1"), byteArrayOf(1, 2, 3)) } },
        )
    }

    /** A well-formed 4-byte length prefix declaring [declaredIdLength], plus a nonce-sized body. */
    private fun frameDeclaring(declaredIdLength: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES + NONCE_BYTES).also { frame ->
            frame[0] = (declaredIdLength ushr 24).toByte()
            frame[1] = (declaredIdLength ushr 16).toByte()
            frame[2] = (declaredIdLength ushr 8).toByte()
            frame[3] = declaredIdLength.toByte()
        }

    /**
     * Hand-assemble a preamble carrying [nonce] verbatim.
     *
     * Not [NwHello.encode] — that enforces the same width invariant, so a wrong-width nonce cannot be
     * produced through it. This is what a hostile or buggy remote puts on the wire.
     */
    private fun frameWithNonce(id: PeerId, nonce: ByteArray): ByteArray {
        val idBytes = id.value.encodeToByteArray()
        return ByteArray(Int.SIZE_BYTES + idBytes.size + nonce.size).also { frame ->
            frame[0] = (idBytes.size ushr 24).toByte()
            frame[1] = (idBytes.size ushr 16).toByte()
            frame[2] = (idBytes.size ushr 8).toByte()
            frame[3] = idBytes.size.toByte()
            idBytes.copyInto(frame, destinationOffset = Int.SIZE_BYTES)
            nonce.copyInto(frame, destinationOffset = Int.SIZE_BYTES + idBytes.size)
        }
    }
}
