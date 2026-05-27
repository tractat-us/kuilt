package us.tractat.kuilt.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkCodecTest {

    private fun reassemble(chunks: List<ByteArray>): ByteArray? {
        val reassembler = ChunkCodec.Reassembler()
        var result: ByteArray? = null
        for (raw in chunks) {
            val decoded = ChunkCodec.decodeChunk(raw) ?: error("decode failed for chunk")
            reassembler.feed(decoded)?.let { result = it }
        }
        return result
    }

    @Test
    fun singleChunkRoundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val chunks = ChunkCodec.encode(payload, msgId = 7)
        assertEquals(1, chunks.size, "small payload is one chunk")
        assertTrue(reassemble(chunks)!!.contentEquals(payload))
    }

    @Test
    fun emptyPayloadIsSingleChunkAndReassembles() {
        val chunks = ChunkCodec.encode(ByteArray(0), msgId = 1)
        assertEquals(1, chunks.size)
        assertTrue(reassemble(chunks)!!.contentEquals(ByteArray(0)))
    }

    @Test
    fun multiChunkRoundTripReassembles() {
        // 10 bytes with a 4-byte cap → 3 chunks.
        val payload = ByteArray(10) { it.toByte() }
        val chunks = ChunkCodec.encode(payload, msgId = 42, maxChunkPayload = 4)
        assertEquals(3, chunks.size)
        assertTrue(reassemble(chunks)!!.contentEquals(payload))
    }

    @Test
    fun largePayloadOverDefaultCapSplits() {
        val payload = ByteArray(ChunkCodec.MAX_CHUNK_PAYLOAD * 2 + 17) { (it % 251).toByte() }
        val chunks = ChunkCodec.encode(payload, msgId = 99)
        assertEquals(3, chunks.size, "two full chunks plus a remainder")
        assertTrue(reassemble(chunks)!!.contentEquals(payload))
    }

    @Test
    fun exactlyAtCapIsSingleChunkAndOneOverSplits() {
        val atCap = ByteArray(10) { it.toByte() }
        assertEquals(1, ChunkCodec.encode(atCap, msgId = 1, maxChunkPayload = 10).size)

        val overCap = ByteArray(11) { it.toByte() }
        assertEquals(2, ChunkCodec.encode(overCap, msgId = 1, maxChunkPayload = 10).size)
    }

    @Test
    fun outOfOrderChunksStillReassemble() {
        val payload = ByteArray(10) { it.toByte() }
        val chunks = ChunkCodec.encode(payload, msgId = 5, maxChunkPayload = 4)
        val reassembler = ChunkCodec.Reassembler()
        // Feed in reverse order; only the final chunk should complete it.
        val decoded = chunks.map { ChunkCodec.decodeChunk(it)!! }
        assertNull(reassembler.feed(decoded[2]))
        assertNull(reassembler.feed(decoded[1]))
        val done = reassembler.feed(decoded[0])
        assertTrue(done != null && done.contentEquals(payload))
    }

    @Test
    fun interleavedMessagesReassembleIndependently() {
        val a = ByteArray(8) { it.toByte() }
        val b = ByteArray(8) { (100 + it).toByte() }
        val chunksA = ChunkCodec.encode(a, msgId = 1, maxChunkPayload = 4).map { ChunkCodec.decodeChunk(it)!! }
        val chunksB = ChunkCodec.encode(b, msgId = 2, maxChunkPayload = 4).map { ChunkCodec.decodeChunk(it)!! }

        val reassembler = ChunkCodec.Reassembler()
        assertNull(reassembler.feed(chunksA[0]))
        assertNull(reassembler.feed(chunksB[0]))
        val doneA = reassembler.feed(chunksA[1])
        val doneB = reassembler.feed(chunksB[1])
        assertTrue(doneA != null && doneA.contentEquals(a), "message A reassembles")
        assertTrue(doneB != null && doneB.contentEquals(b), "message B reassembles")
    }

    @Test
    fun decodeRejectsTruncatedAndInconsistentHeaders() {
        assertNull(ChunkCodec.decodeChunk(ByteArray(3)), "shorter than header")
        // A header claiming chunkCount=0 is inconsistent.
        val bogus = ByteArray(ChunkCodec.HEADER_SIZE) // msgId=0, index=0, count=0
        assertNull(ChunkCodec.decodeChunk(bogus), "chunkCount=0 is rejected")
    }
}
