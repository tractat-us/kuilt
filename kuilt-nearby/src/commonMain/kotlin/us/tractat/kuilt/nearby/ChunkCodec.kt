package us.tractat.kuilt.nearby

/**
 * Splits an arbitrarily-sized payload into 1..N BYTES chunks and reassembles them.
 *
 * ## Wire header (8 bytes, big-endian)
 * ```
 * [msgId: Int (4 bytes)] [chunkIndex: UShort (2 bytes)] [chunkCount: UShort (2 bytes)] [payload bytes...]
 * ```
 * A ≤[MAX_CHUNK_PAYLOAD]-byte message is a single chunk (chunkIndex=0, chunkCount=1).
 * An empty payload is encoded as one chunk with zero payload bytes.
 *
 * Thread-safety: [encode] and [decodeChunk] are stateless and safe to call concurrently.
 * [Reassembler] is NOT thread-safe — callers must synchronise (or use one instance per
 * coroutine / per-endpoint).
 */
public object ChunkCodec {

    /** Byte size of the fixed chunk header. */
    public const val HEADER_SIZE: Int = 8 // 4 (msgId) + 2 (chunkIndex) + 2 (chunkCount)

    /**
     * Default maximum bytes of message payload per chunk (header excluded).
     * Approximates `ConnectionsClient.MAX_BYTES_DATA_SIZE` (≈32 768) minus the header.
     * The real cap is injected by the Android binding at construction time.
     */
    public const val MAX_CHUNK_PAYLOAD: Int = 32 * 1024 - HEADER_SIZE

    /**
     * Encode [payload] into one or more chunks.
     *
     * @param payload        the message bytes (may be empty)
     * @param msgId          per-message identifier shared by all its chunks
     * @param maxChunkPayload cap on payload bytes per chunk (default [MAX_CHUNK_PAYLOAD])
     */
    public fun encode(
        payload: ByteArray,
        msgId: Int,
        maxChunkPayload: Int = MAX_CHUNK_PAYLOAD,
    ): List<ByteArray> {
        require(maxChunkPayload > 0) { "maxChunkPayload must be > 0" }
        val chunkCount = chunkCountFor(payload.size, maxChunkPayload)
        return (0 until chunkCount).map { index ->
            encodeChunk(payload, msgId, index, chunkCount, maxChunkPayload)
        }
    }

    /**
     * Decode the header fields and payload slice from a raw received chunk.
     * Returns null if [bytes] is shorter than [HEADER_SIZE] or the header is
     * internally inconsistent (e.g. chunkIndex ≥ chunkCount, chunkCount = 0).
     */
    public fun decodeChunk(bytes: ByteArray): DecodedChunk? {
        if (bytes.size < HEADER_SIZE) return null
        val msgId = readInt(bytes, 0)
        val chunkIndex = readShort(bytes, 4).toInt() and 0xFFFF
        val chunkCount = readShort(bytes, 6).toInt() and 0xFFFF
        if (chunkCount == 0 || chunkIndex >= chunkCount) return null
        val chunkPayload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        return DecodedChunk(msgId, chunkIndex, chunkCount, chunkPayload)
    }

    // ── internal encode helpers ───────────────────────────────────────────────

    private fun chunkCountFor(payloadSize: Int, maxChunkPayload: Int): Int =
        if (payloadSize == 0) 1 else (payloadSize + maxChunkPayload - 1) / maxChunkPayload

    private fun encodeChunk(
        payload: ByteArray,
        msgId: Int,
        index: Int,
        chunkCount: Int,
        maxChunkPayload: Int,
    ): ByteArray {
        val start = index * maxChunkPayload
        val end = minOf(start + maxChunkPayload, payload.size)
        val out = ByteArray(HEADER_SIZE + (end - start))
        writeInt(out, 0, msgId)
        writeShort(out, 4, index.toShort())
        writeShort(out, 6, chunkCount.toShort())
        payload.copyInto(out, destinationOffset = HEADER_SIZE, startIndex = start, endIndex = end)
        return out
    }

    // ── byte helpers (big-endian) ─────────────────────────────────────────────

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Short) {
        buf[offset] = (value.toInt() ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    private fun readShort(buf: ByteArray, offset: Int): Short =
        (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)).toShort()

    // ── Reassembler ───────────────────────────────────────────────────────────

    /**
     * Per-endpoint reassembler. Feed decoded chunks in arrival order; [feed]
     * returns the complete payload once all chunks for a message have arrived.
     *
     * Not thread-safe — use one instance per endpoint, accessed from one coroutine.
     */
    public class Reassembler {
        private val pending = mutableMapOf<Int, Assembly>()

        /**
         * Feed one decoded chunk. Returns the complete reassembled payload when
         * all [DecodedChunk.chunkCount] chunks have arrived, or null while waiting.
         */
        public fun feed(chunk: DecodedChunk): ByteArray? {
            val assembly = pending.getOrPut(chunk.msgId) { Assembly(chunk.chunkCount) }
            assembly.receive(chunk.chunkIndex, chunk.chunkPayload)
            return if (assembly.isComplete()) {
                pending.remove(chunk.msgId)
                assembly.assemble()
            } else {
                null
            }
        }

        /** Discard all in-progress state (e.g. on endpoint disconnect). */
        public fun reset() { pending.clear() }

        private class Assembly(val chunkCount: Int) {
            private val slots = arrayOfNulls<ByteArray>(chunkCount)
            private var received = 0

            fun receive(index: Int, payload: ByteArray) {
                if (slots[index] == null) {
                    slots[index] = payload
                    received++
                }
            }

            fun isComplete(): Boolean = received == chunkCount

            fun assemble(): ByteArray {
                val totalSize = slots.sumOf { it?.size ?: 0 }
                val out = ByteArray(totalSize)
                var offset = 0
                for (slot in slots) {
                    val s = slot ?: continue
                    s.copyInto(out, offset)
                    offset += s.size
                }
                return out
            }
        }
    }
}

/** A decoded (but not yet reassembled) chunk header + payload slice. */
public data class DecodedChunk(
    val msgId: Int,
    val chunkIndex: Int,
    val chunkCount: Int,
    val chunkPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedChunk) return false
        return msgId == other.msgId &&
            chunkIndex == other.chunkIndex &&
            chunkCount == other.chunkCount &&
            chunkPayload.contentEquals(other.chunkPayload)
    }

    override fun hashCode(): Int {
        var result = msgId
        result = 31 * result + chunkIndex
        result = 31 * result + chunkCount
        result = 31 * result + chunkPayload.contentHashCode()
        return result
    }
}
