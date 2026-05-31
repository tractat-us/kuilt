package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId

/**
 * Per-origin inbound gate for a composite fabric. Collapses duplicate [PlyFrame.Data]
 * (same `(originId, originSeq)` arriving over multiple plies) and releases per-origin
 * frames in sequence order with a bounded buffer. Not thread-safe — the composite
 * calls it from a single inbound coroutine.
 *
 * @param maxBuffered Maximum out-of-order frames held per origin before a gap-skip
 * is forced to preserve liveness.
 */
internal class PlyInboundGate(private val maxBuffered: Int = 16) {
    // Per origin: the next sequence we expect to deliver.
    private val nextExpected = mutableMapOf<PeerId, Long>()
    // Per origin: out-of-order frames not yet deliverable.
    private val buffers = mutableMapOf<PeerId, MutableMap<Long, ByteArray>>()

    /** Returns the payloads to deliver now, in order. Empty for a duplicate. */
    fun accept(frame: PlyFrame.Data): List<ByteArray> {
        val origin = frame.originId
        if (origin !in nextExpected) {
            // First sight of this origin: adopt its sequence as the baseline.
            nextExpected[origin] = frame.originSeq + 1
            return listOf(frame.payload)
        }
        val expected = nextExpected.getValue(origin)
        if (frame.originSeq < expected) return emptyList() // duplicate / already delivered / skipped

        val buffer = buffers.getOrPut(origin) { LinkedHashMap() }
        if (frame.originSeq == expected) {
            buffer[expected] = frame.payload
        } else {
            buffer[frame.originSeq] = frame.payload
            // Overflow: buffer has reached the cap → skip the gap to the lowest buffered.
            if (buffer.size >= maxBuffered) {
                nextExpected[origin] = buffer.firstKey()
            }
        }
        return drain(origin, buffer)
    }

    private fun drain(origin: PeerId, buffer: MutableMap<Long, ByteArray>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var expect = nextExpected.getValue(origin)
        while (true) {
            val payload = buffer.remove(expect) ?: break
            out.add(payload)
            expect += 1
        }
        nextExpected[origin] = expect
        return out
    }

    // commonMain has no TreeMap; keep a sorted-by-key map via min-of-keys lookup.
    private fun MutableMap<Long, ByteArray>.firstKey(): Long = keys.min()
}
