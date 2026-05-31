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
}
