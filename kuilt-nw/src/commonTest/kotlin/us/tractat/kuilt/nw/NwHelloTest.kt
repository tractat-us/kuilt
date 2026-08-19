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
 * The preamble body is the **first bytes a remote sends** —
 * `[version][4-byte big-endian id length][id UTF-8][nonce]` — so a malformed one is reachable input,
 * not a local mistake. Before the fix there was no length check at all: a body shorter than the
 * prefix index-faulted inside `readInt`, and both a negative declared length (the prefix is read as a
 * signed `Int`) and one that overflows `4 + idLen` would pass any additive bounds test.
 * `NwSeam.processFrame` does guard the call site, so the consequence there was a disconnected
 * connection rather than a dead loop — but the check belongs in the decoder, which is the only place
 * that can say *what* was wrong.
 *
 * The leading **version** byte arrived with the #2425 flag day and is read before anything else,
 * because every field after it is laid out by it. Everything here therefore works on a BODY: the
 * [NwFrameType] byte that says *this is a hello* lives one layer out, in [NwWire], and is covered by
 * `NwWireTest`.
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

    // --- the VERSION is the first field, and it gates every read after it (#2425) ---

    @Test
    fun theEncoderStampsTheVersionThisBuildSpeaks() {
        val encoded = NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 })
        assertEquals(
            NW_WIRE_VERSION,
            encoded[0].toInt(),
            "the version leads the body — a peer must learn what it is reading before it reads it",
        )
    }

    @Test
    fun aBodyWithNoVersionByteIsRejectedAsTruncated() {
        assertFailsWith<NwTruncatedFrameException>("an empty body has no version to read") {
            NwHello.decode(ByteArray(0))
        }
    }

    @Test
    fun aVersionThisBuildDoesNotSpeakIsRejectedByNameRatherThanAsMalformed() {
        listOf(0, NW_WIRE_VERSION + 1, 0xFF).forEach { version ->
            val failure = assertFailsWith<NwUnsupportedWireVersionException>("version $version must be refused") {
                NwHello.decode(bodyAtVersion(version, PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))
            }
            assertAll(
                { assertEquals(version, failure.remoteVersion) },
                { assertEquals(NW_WIRE_VERSION, failure.localVersion) },
            )
        }
    }

    @Test
    fun theVersionIsCheckedBeforeTheIdLength() {
        // A body that is BOTH wrongly-versioned AND structurally garbage must report the version, not
        // the garbage: the layout of everything after the version byte is defined BY that version, so
        // "malformed" is a claim this build is in no position to make.
        val failure = assertFailsWith<NwUnsupportedWireVersionException> {
            NwHello.decode(byteArrayOf((NW_WIRE_VERSION + 1).toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
        assertEquals(NW_WIRE_VERSION + 1, failure.remoteVersion)
    }

    @Test
    fun aBodyTooShortForTheLengthPrefixIsRejected() {
        (0 until Int.SIZE_BYTES).forEach { size ->
            assertFailsWith<IllegalArgumentException>("a $size-byte length prefix must be rejected, not index-fault") {
                NwHello.decode(byteArrayOf(NW_WIRE_VERSION.toByte()) + ByteArray(size))
            }
        }
    }

    @Test
    fun aNegativeOrOverflowingDeclaredIdLengthIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(bodyDeclaring(-1)) } },
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(bodyDeclaring(Int.MIN_VALUE)) } },
            // 0x7fffffff — the exact garbage length NwSeamTest feeds the receive loop.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(bodyDeclaring(Int.MAX_VALUE)) } },
            // The plain truncation case too: larger than the body but small enough not to wrap.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(bodyDeclaring(1024)) } },
        )
    }

    // --- the nonce is a FIXED-WIDTH field, not a variable-length tail (#1812) ---
    //
    // The decoded nonce reaches [canonicalLinkNonce], which hex-encodes both endpoints' nonces, sorts
    // the two strings and joins them — that string IS the link identity both ends dedup on. A
    // wrong-width nonce is proof of a malformed or forged preamble, and the body is REJECTED rather
    // than reshaped: truncating or padding it to the declared width would launder the forgery into a
    // valid-looking identity. A zero-length nonce is the concrete collapse — every peer that sends one
    // contributes the empty string, so two distinct misbehaving peers derive the SAME link identity.

    @Test
    fun aNonceOfTheWrongWidthIsRejected() {
        val id = PeerId("peer-1")
        listOf(0, 1, NONCE_BYTES - 1, NONCE_BYTES + 1, 2 * NONCE_BYTES).forEach { width ->
            assertFailsWith<IllegalArgumentException>("a $width-byte nonce must be rejected, not accepted as identity") {
                NwHello.decode(bodyWithNonce(id, ByteArray(width) { 7 }))
            }
        }
    }

    @Test
    fun anExactWidthNonceIsStillAccepted() {
        val id = PeerId("peer-1")
        val nonce = ByteArray(NONCE_BYTES) { (it * 3).toByte() }
        val decoded = NwHello.decode(bodyWithNonce(id, nonce))
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

    /** A well-formed body at the current version declaring [declaredIdLength], plus a nonce-sized tail. */
    private fun bodyDeclaring(declaredIdLength: Int): ByteArray =
        byteArrayOf(NW_WIRE_VERSION.toByte()) + beInt(declaredIdLength) + ByteArray(NONCE_BYTES)

    /**
     * Hand-assemble a body carrying [nonce] verbatim.
     *
     * Not [NwHello.encode] — that enforces the same width invariant, so a wrong-width nonce cannot be
     * produced through it. This is what a hostile or buggy remote puts on the wire.
     */
    private fun bodyWithNonce(id: PeerId, nonce: ByteArray): ByteArray =
        bodyAtVersion(NW_WIRE_VERSION, id, nonce)

    /** A body declaring [version]; [NwHello.encode] only ever writes [NW_WIRE_VERSION]. */
    private fun bodyAtVersion(version: Int, id: PeerId, nonce: ByteArray): ByteArray {
        val idBytes = id.value.encodeToByteArray()
        return byteArrayOf(version.toByte()) + beInt(idBytes.size) + idBytes + nonce
    }

    private fun beInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
