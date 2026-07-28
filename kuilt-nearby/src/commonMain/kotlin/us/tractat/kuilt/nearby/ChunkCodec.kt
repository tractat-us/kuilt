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
     * Maximum number of *incomplete* messages one [Reassembler] holds before it evicts the
     * eldest.
     *
     * kuilt's own sender emits a message's chunks contiguously for one endpoint, so the honest
     * working set is one incomplete message per concurrent `broadcast`/`sendTo` caller — a
     * handful. This is the same bound the composite fabric's per-origin inbound gate already
     * uses for the same shape (a peer-keyed map of partially-received frames); reusing it keeps
     * one number for one problem rather than inventing a second knob.
     */
    public const val DEFAULT_MAX_PENDING_MESSAGES: Int = 16

    /**
     * Per-endpoint reassembler. Feed decoded chunks in arrival order; [feed]
     * returns the complete payload once all chunks for a message have arrived.
     *
     * Every field in a [DecodedChunk] is peer-chosen, so [feed] treats each chunk as untrusted:
     * it re-checks the header invariants (a hand-built [DecodedChunk] never passed through
     * [decodeChunk]) and refuses any chunk that contradicts a message already in progress.
     * It never throws on malformed input — a fabric receive loop must survive a hostile frame.
     *
     * Not thread-safe — use one instance per endpoint, accessed from one coroutine.
     *
     * @param maxPendingMessages Cap on concurrently-incomplete messages; see
     * [DEFAULT_MAX_PENDING_MESSAGES].
     */
    public class Reassembler(
        private val maxPendingMessages: Int = DEFAULT_MAX_PENDING_MESSAGES,
    ) {
        // Insertion-ordered on every target (Kotlin's mutableMapOf is a LinkedHashMap), so the
        // eldest still-incomplete message is the first key.
        private val pending = mutableMapOf<Int, Assembly>()

        /**
         * Feed one decoded chunk. Returns the complete reassembled payload when
         * all [DecodedChunk.chunkCount] chunks have arrived, or null while waiting
         * — or null if the chunk is refused.
         */
        public fun feed(chunk: DecodedChunk): ByteArray? {
            // Self-consistency. `feed` is public, so a DecodedChunk may be hand-built and never
            // have gone through decodeChunk's header check — re-derive it rather than trust it.
            if (chunk.chunkCount <= 0 || chunk.chunkIndex < 0 || chunk.chunkIndex >= chunk.chunkCount) {
                return null
            }
            val existing = pending[chunk.msgId]
            // Cross-chunk consistency (#1819). Two chunks claiming one msgId with different
            // chunkCounts cannot both describe the same message, so the later one is proof of a
            // forged or corrupt frame. There is no favourable value to clamp it to: discard the
            // FRAME and leave the assembly alone. A late arrival must never be able to redefine
            // — or index past — a message already in progress.
            if (existing != null && chunk.chunkCount != existing.chunkCount) return null

            val assembly = existing ?: startAssembly(chunk.msgId, chunk.chunkCount)
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

        /**
         * Begin tracking [msgId], first making room if [pending] is at [maxPendingMessages].
         *
         * Eviction drops the *eldest* incomplete message rather than refusing the new one: a peer
         * that opens messages and never finishes them must not be able to lock out fresh traffic.
         * `msgId` is peer-chosen and unbounded, and nothing but an endpoint disconnect used to
         * prune this map.
         */
        private fun startAssembly(msgId: Int, chunkCount: Int): Assembly {
            if (pending.size >= maxPendingMessages) {
                pending.keys.firstOrNull()?.let { pending.remove(it) }
            }
            return Assembly(chunkCount).also { pending[msgId] = it }
        }

        private class Assembly(val chunkCount: Int) {
            // Sparse, so memory tracks bytes actually received rather than the peer's claim: a
            // 9-byte chunk declaring chunkCount = 65535 costs one entry, not a 65535-slot array.
            // That removes the allocation amplification without inventing a chunkCount cap.
            private val slots = mutableMapOf<Int, ByteArray>()

            /** Records [payload] at [index]; first write wins. [index] is validated by [feed]. */
            fun receive(index: Int, payload: ByteArray) {
                if (index !in slots) slots[index] = payload
            }

            // Indices are validated into 0 until chunkCount and every chunk agrees on
            // chunkCount, so a full slot map is exactly the full message.
            fun isComplete(): Boolean = slots.size == chunkCount

            fun assemble(): ByteArray {
                val out = ByteArray(slots.values.sumOf { it.size })
                var offset = 0
                for (index in 0 until chunkCount) {
                    val slot = slots[index] ?: continue
                    slot.copyInto(out, offset)
                    offset += slot.size
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
