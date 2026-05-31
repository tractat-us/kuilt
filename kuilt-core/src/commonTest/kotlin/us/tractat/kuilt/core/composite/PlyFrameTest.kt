package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
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
}
