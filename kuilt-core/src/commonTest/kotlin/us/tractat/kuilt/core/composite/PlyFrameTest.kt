package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlyFrameTest {
    @Test
    fun announceRoundTrips() {
        val bytes = PlyFrame.encode(PlyFrame.Announce(PeerId("composite-7")))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Announce>(decoded)
        assertEquals(PeerId("composite-7"), decoded.compositeId)
    }

    @Test
    fun dataRoundTripsPreservingPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = PlyFrame.encode(PlyFrame.Data(PeerId("c"), originSeq = 42L, payload = payload))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Data>(decoded)
        assertEquals(PeerId("c"), decoded.originId)
        assertEquals(42L, decoded.originSeq)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val bytes = PlyFrame.encode(PlyFrame.Data(PeerId("c"), 0L, ByteArray(0)))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Data>(decoded)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun unknownTagThrows() {
        assertFailsWith<IllegalArgumentException> { PlyFrame.decode(byteArrayOf(99)) }
    }

    // --- originSeq edge values ---

    @Test
    fun dataRoundTripsOriginSeqMaxValue() {
        val frame = PlyFrame.Data(PeerId("p"), originSeq = Long.MAX_VALUE, payload = byteArrayOf())
        val decoded = PlyFrame.decode(PlyFrame.encode(frame)) as PlyFrame.Data
        assertEquals(Long.MAX_VALUE, decoded.originSeq)
    }

    @Test
    fun dataRoundTripsOriginSeqMinValue() {
        val frame = PlyFrame.Data(PeerId("p"), originSeq = Long.MIN_VALUE, payload = byteArrayOf())
        val decoded = PlyFrame.decode(PlyFrame.encode(frame)) as PlyFrame.Data
        assertEquals(Long.MIN_VALUE, decoded.originSeq)
    }

    @Test
    fun dataRoundTripsOriginSeqNegativeOne() {
        val frame = PlyFrame.Data(PeerId("p"), originSeq = -1L, payload = byteArrayOf())
        val decoded = PlyFrame.decode(PlyFrame.encode(frame)) as PlyFrame.Data
        assertEquals(-1L, decoded.originSeq)
    }

    @Test
    fun dataRoundTripsOriginSeqHighBytesSet() {
        // 0x0102030405060708 — exercises all 8 bytes of big-endian encoding
        val seq = 0x0102030405060708L
        val frame = PlyFrame.Data(PeerId("p"), originSeq = seq, payload = byteArrayOf())
        val decoded = PlyFrame.decode(PlyFrame.encode(frame)) as PlyFrame.Data
        assertEquals(seq, decoded.originSeq)
    }

    // --- truncated input ---

    @Test
    fun truncatedDataFrameThrowsIllegalArgumentException() {
        // Build a valid Data frame then truncate it so idLen > remaining bytes
        val valid = PlyFrame.encode(PlyFrame.Data(PeerId("alice"), 1L, byteArrayOf(0, 0)))
        // Lop off the last 4 bytes so the buffer is too short for the declared idLen
        val truncated = valid.copyOf(valid.size - 4)
        assertFailsWith<IllegalArgumentException> { PlyFrame.decode(truncated) }
    }

    @Test
    fun truncatedAnnounceFrameThrowsIllegalArgumentException() {
        val valid = PlyFrame.encode(PlyFrame.Announce(PeerId("bob")))
        val truncated = valid.copyOf(valid.size - 2)
        assertFailsWith<IllegalArgumentException> { PlyFrame.decode(truncated) }
    }

    // --- malformed input a PEER can send (#1788) ---
    //
    // These bytes are reachable from any peer in the session, so each of the three shapes below is a
    // remote input and not a local programming error. Before the fix all three defeated the `require`s:
    // the short frame index-faulted inside `readInt` BEFORE the check ran, and the negative and
    // overflowing declared lengths both made `bytes.size >= 5 + len (+ 8)` pass. Whatever escaped went
    // out of the composite's per-ply inbound pump — which on Kotlin/Native aborts the process, see
    // `CompositeMalformedFrameProcessSurvivalTest`.

    @Test
    fun aTwoByteFrameIsRejectedRatherThanIndexFaulting() {
        // THE crash frame: a valid tag and one byte, so `readInt(bytes, 1)` reads bytes[1..4] — three of
        // which do not exist. The bounds check has to precede the read, not follow it.
        val announceTag = PlyFrame.encode(PlyFrame.Announce(PeerId("a"))).copyOf(2)
        val dataTag = PlyFrame.encode(PlyFrame.Data(PeerId("a"), 0L, byteArrayOf())).copyOf(2)
        assertAll(
            { assertFailsWith<IllegalArgumentException> { PlyFrame.decode(announceTag) } },
            { assertFailsWith<IllegalArgumentException> { PlyFrame.decode(dataTag) } },
        )
    }

    @Test
    fun aFrameTruncatedInsideTheLengthPrefixIsRejected() {
        val valid = PlyFrame.encode(PlyFrame.Data(PeerId("alice"), 1L, byteArrayOf(9)))
        // Every buffer that stops short of the 5-byte header, including the tag-only case.
        (1 until 5).forEach { size ->
            assertFailsWith<IllegalArgumentException>("a $size-byte frame must be rejected, not index-fault") {
                PlyFrame.decode(valid.copyOf(size))
            }
        }
    }

    @Test
    fun aNegativeDeclaredIdLengthIsRejected() {
        // The 4-byte length is read as a SIGNED Int, so a peer only has to set its high bit. Then
        // `bytes.size >= 5 + len` compares against something SMALLER than the header and passes.
        assertAll(
            { assertFailsWith<IllegalArgumentException> { PlyFrame.decode(frameWithDeclaredLength(ANNOUNCE_TAG, -1)) } },
            { assertFailsWith<IllegalArgumentException> { PlyFrame.decode(frameWithDeclaredLength(DATA_TAG, -1)) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    PlyFrame.decode(frameWithDeclaredLength(DATA_TAG, Int.MIN_VALUE))
                }
            },
        )
    }

    @Test
    fun aDeclaredIdLengthThatOverflowsTheOffsetIsRejected() {
        // `5 + Int.MAX_VALUE` wraps NEGATIVE, so an ADDITIVE bounds check passes for a length no buffer
        // could ever satisfy. The check must subtract from the buffer size instead.
        assertAll(
            {
                assertFailsWith<IllegalArgumentException> {
                    PlyFrame.decode(frameWithDeclaredLength(ANNOUNCE_TAG, Int.MAX_VALUE))
                }
            },
            {
                assertFailsWith<IllegalArgumentException> {
                    PlyFrame.decode(frameWithDeclaredLength(DATA_TAG, Int.MAX_VALUE))
                }
            },
            // `5 + len + 8` overflows for this one while `5 + len` alone does not — the Data frame's
            // 8-byte sequence widens the hole, so the trailing bytes must be part of the check.
            {
                assertFailsWith<IllegalArgumentException> {
                    PlyFrame.decode(frameWithDeclaredLength(DATA_TAG, Int.MAX_VALUE - 8))
                }
            },
        )
    }

    /** A syntactically well-formed header for [tag] declaring [declaredIdLength], and 32 bytes of body. */
    private fun frameWithDeclaredLength(tag: Byte, declaredIdLength: Int): ByteArray =
        ByteArray(5 + 32).also { frame ->
            frame[0] = tag
            frame[1] = (declaredIdLength ushr 24).toByte()
            frame[2] = (declaredIdLength ushr 16).toByte()
            frame[3] = (declaredIdLength ushr 8).toByte()
            frame[4] = declaredIdLength.toByte()
        }

    private companion object {
        /** Read off the encoder rather than duplicated, so a tag renumbering cannot silently pass. */
        val ANNOUNCE_TAG: Byte = PlyFrame.encode(PlyFrame.Announce(PeerId("t")))[0]
        val DATA_TAG: Byte = PlyFrame.encode(PlyFrame.Data(PeerId("t"), 0L, byteArrayOf()))[0]
    }
}
