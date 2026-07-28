package us.tractat.kuilt.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ── #1819: chunkCount is bound by the FIRST chunk but validated per-chunk ──────

    /** Hand-build a raw chunk with arbitrary (possibly hostile) header fields. */
    private fun rawChunk(msgId: Int, chunkIndex: Int, chunkCount: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(ChunkCodec.HEADER_SIZE + payload.size)
        out[0] = (msgId ushr 24).toByte()
        out[1] = (msgId ushr 16).toByte()
        out[2] = (msgId ushr 8).toByte()
        out[3] = msgId.toByte()
        out[4] = (chunkIndex ushr 8).toByte()
        out[5] = chunkIndex.toByte()
        out[6] = (chunkCount ushr 8).toByte()
        out[7] = chunkCount.toByte()
        payload.copyInto(out, ChunkCodec.HEADER_SIZE)
        return out
    }

    @Test
    fun chunkCountDisagreeingWithAnAssemblyInProgressIsRefused() {
        val reassembler = ChunkCodec.Reassembler()
        // Chunk 0 of 2 for msgId 7 binds the assembly at chunkCount = 2.
        val head = ChunkCodec.decodeChunk(rawChunk(msgId = 7, 0, 2, byteArrayOf(0xA)))!!
        assertNull(reassembler.feed(head), "one of two chunks — still incomplete")

        // A second chunk claims the SAME msgId but chunkCount = 6, index = 5. It passes
        // decodeChunk (5 < 6) yet indexes past the bound size-2 assembly.
        val forged = ChunkCodec.decodeChunk(rawChunk(msgId = 7, 5, 6, byteArrayOf(0xB)))!!
        assertNull(reassembler.feed(forged), "a chunkCount that contradicts the assembly is refused")

        // The genuine remainder still completes the ORIGINAL message, unmodified.
        val tail = ChunkCodec.decodeChunk(rawChunk(msgId = 7, 1, 2, byteArrayOf(0xC)))!!
        val done = reassembler.feed(tail)
        assertTrue(
            done != null && done.contentEquals(byteArrayOf(0xA, 0xC)),
            "the in-progress assembly survives the forged chunk; got ${done?.toList()}",
        )
    }

    @Test
    fun aRefusedChunkLeavesTheReassemblerUsableForLaterMessages() {
        val reassembler = ChunkCodec.Reassembler()
        reassembler.feed(ChunkCodec.decodeChunk(rawChunk(msgId = 7, 0, 2, byteArrayOf(0xA)))!!)
        reassembler.feed(ChunkCodec.decodeChunk(rawChunk(msgId = 7, 5, 6, byteArrayOf(0xB)))!!)

        // A completely unrelated, well-formed message must still round-trip.
        val payload = ByteArray(10) { it.toByte() }
        val chunks = ChunkCodec.encode(payload, msgId = 42, maxChunkPayload = 4)
        var result: ByteArray? = null
        for (raw in chunks) {
            reassembler.feed(ChunkCodec.decodeChunk(raw)!!)?.let { result = it }
        }
        assertTrue(result != null && result.contentEquals(payload), "codec is not left deaf")
    }

    @Test
    fun feedRejectsAHandBuiltChunkWhoseIndexIsOutOfRange() {
        // `feed` is public, so a DecodedChunk can reach it without passing decodeChunk's checks.
        val reassembler = ChunkCodec.Reassembler()
        assertNull(reassembler.feed(DecodedChunk(msgId = 1, chunkIndex = 5, chunkCount = 2, byteArrayOf(1))))
        assertNull(reassembler.feed(DecodedChunk(msgId = 1, chunkIndex = 0, chunkCount = 0, byteArrayOf(1))))
        assertNull(reassembler.feed(DecodedChunk(msgId = 1, chunkIndex = -1, chunkCount = 2, byteArrayOf(1))))
    }

    @Test
    fun pendingIncompleteMessagesAreBoundedAndEvictTheEldest() {
        val reassembler = ChunkCodec.Reassembler()
        fun head(msgId: Int) = ChunkCodec.decodeChunk(rawChunk(msgId, 0, 2, byteArrayOf(0xA)))!!
        fun tail(msgId: Int) = ChunkCodec.decodeChunk(rawChunk(msgId, 1, 2, byteArrayOf(0xB)))!!

        // A peer that opens messages and never finishes them must not grow `pending` without
        // bound. 32 is comfortably past any sane in-flight working set for one endpoint.
        val opened = 32
        for (msgId in 1..opened) assertNull(reassembler.feed(head(msgId)))

        // The eldest was evicted, so its tail no longer completes anything.
        assertNull(reassembler.feed(tail(1)), "eldest incomplete message was evicted")
        // The newest is untouched and still completes.
        val done = reassembler.feed(tail(opened))
        assertTrue(done != null && done.contentEquals(byteArrayOf(0xA, 0xB)), "newest message survives")
    }

    @Test
    fun evictionCostsTheEldestMessageOnlyAndDoesNotCascade() {
        // N concurrent senders round-robin their chunks — the natural arrival order when several
        // `broadcast`/`sendTo` callers interleave on one endpoint. At exactly one message over the
        // cap, the straggling chunks of the EVICTED message must not evict the next live message
        // (and so on down the table): crossing the cap by one costs one message, not all of them.
        val reassembler = ChunkCodec.Reassembler()
        val senders = ChunkCodec.DEFAULT_MAX_PENDING_MESSAGES + 1
        val delivered = mutableListOf<Int>()
        for (chunkIndex in 0 until 2) {
            for (msgId in 1..senders) {
                val raw = rawChunk(msgId, chunkIndex, 2, byteArrayOf(msgId.toByte()))
                reassembler.feed(ChunkCodec.decodeChunk(raw)!!)?.let { delivered += msgId }
            }
        }
        assertEquals(
            (2..senders).toList(),
            delivered,
            "only the eldest message is lost; got ${delivered.size}/$senders",
        )
    }

    @Test
    fun aMessageLargerThanAByteArrayIsRefusedRatherThanOverflowing() {
        // `assemble` used to size its output with an unchecked Int sum, so a message whose chunks
        // total more than Int.MAX_VALUE bytes wrapped negative and threw NegativeArraySizeException
        // out of `feed`. Reached here with ONE 32 MiB array referenced from 64 slots — 64 × 32 MiB
        // is Int.MAX_VALUE + 1 — so the test costs 32 MiB, not 2 GiB.
        val chunkBytes = 32 * 1024 * 1024
        val shared = ByteArray(chunkBytes)
        val chunkCount = Int.MAX_VALUE / chunkBytes + 1
        val reassembler = ChunkCodec.Reassembler()

        for (index in 0 until chunkCount - 1) {
            assertNull(reassembler.feed(DecodedChunk(1, index, chunkCount, shared)), "still filling")
        }
        // The chunk that would tip the total past Int.MAX_VALUE is refused, not fatal.
        assertNull(
            reassembler.feed(DecodedChunk(1, chunkCount - 1, chunkCount, shared)),
            "the overflowing chunk is refused",
        )
        // And the codec is still usable.
        val payload = byteArrayOf(1, 2, 3)
        val done = reassembler.feed(ChunkCodec.decodeChunk(ChunkCodec.encode(payload, msgId = 2).single())!!)
        assertTrue(done != null && done.contentEquals(payload), "codec still works afterwards")
    }

    @Test
    fun aNonPositivePendingCapIsRejected() {
        assertFailsWith<IllegalArgumentException> { ChunkCodec.Reassembler(maxPendingMessages = 0) }
        assertFailsWith<IllegalArgumentException> { ChunkCodec.Reassembler(maxPendingMessages = -1) }
    }
}
