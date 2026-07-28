package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId

/**
 * Per-origin inbound gate for a composite fabric. Collapses duplicate [PlyFrame.Data]
 * (same `(originId, originSeq)` arriving over multiple plies) and releases per-origin
 * frames in sequence order with a bounded buffer. Not thread-safe — the composite
 * calls it from a single inbound coroutine.
 *
 * ### The origin key is peer-chosen, so the table is capped (#1814)
 * `originId` is a field the **sending** peer picks, read straight off the wire by
 * `PlyFrame.decode`. [maxBuffered] bounds the buffer *within* one origin; nothing bounded
 * the **number** of origins, and no path ever removes one — so a peer varying `originId`
 * per frame grew [nextExpected] (and, once it sent a second, out-of-order frame under an
 * id, [buffers]) without limit, for as long as the ply stayed attached. Remote memory
 * exhaustion with no malformed frame required: every frame is well-formed.
 *
 * [MAX_ORIGINS] closes that. Note exactly what it bounds and what it does not:
 *  - It bounds the **count** of origins this gate holds state for, over the gate's whole
 *    lifetime — origin state is never pruned, so a departed peer keeps its slot until the
 *    composite seam that owns this gate is gone. It is a lifetime budget, not a concurrent
 *    -origin one.
 *  - It does **not** bound bytes. Each admitted origin may still hold up to [maxBuffered]
 *    payloads of whatever size its transport allows, so the retained-payload ceiling is
 *    `MAX_ORIGINS * maxBuffered` frames.
 *  - It does **not** check that an origin is an admitted member. Gating on composite roster
 *    state is the stronger property and is deliberately not done here: this gate knows
 *    nothing of the roster, and a `Data` frame can legitimately arrive before the `Announce`
 *    that registers its origin. See #1814 for that follow-on.
 *
 * ### Refuse the new origin; never evict an admitted one
 * Evicting (LRU or otherwise) would hand the attacker the displacement: origin ids are
 * remote-chosen and can be emitted at any rate, so a rotating-id flood would evict every
 * honest origin within one cap-sized window, and re-admitting an evicted origin takes the
 * first-sight branch — re-baselining its sequence, which re-opens the cross-ply duplicate
 * this gate exists to collapse and discards whatever it had buffered. That trades a bounded
 * memory problem for an unbounded *duplicate-delivery* one against honest peers. Refusal is
 * contained instead: an already-admitted origin is never disturbed, and the residual damage
 * is that no *new* origin is admitted while the table is full.
 *
 * The refusal throws rather than returning empty because an empty return already means
 * "duplicate" — a normal, expected outcome — while a refusal is an anomaly, and `kuilt-core`
 * is logger-free by contract. `CompositeSeam.onPlyFrame` calls this inside its inbound
 * guard, so the throw drops that one frame, leaves the ply live, and surfaces to the
 * consumer as `PlyReconcileException(plyId, INBOUND, …)` — precisely the semantics that
 * phase already documents.
 *
 * @param maxBuffered Maximum out-of-order frames held per origin before a gap-skip
 * is forced to preserve liveness.
 */
internal class PlyInboundGate(private val maxBuffered: Int = 16) {
    // Per origin: the next sequence we expect to deliver. Capped at MAX_ORIGINS entries.
    private val nextExpected = mutableMapOf<PeerId, Long>()
    // Per origin: out-of-order frames not yet deliverable. Only ever populated for an origin
    // already in `nextExpected` (the first-sight branch below returns before reaching it), so
    // capping that map caps this one too.
    private val buffers = mutableMapOf<PeerId, MutableMap<Long, ByteArray>>()

    /**
     * Returns the payloads to deliver now, in order. Empty for a duplicate.
     *
     * Throws [IllegalStateException] for a frame from an origin this gate has not seen when its
     * origin table is already at [MAX_ORIGINS] — the frame is refused and nothing is recorded, so
     * the same id is refused again rather than half-admitted.
     */
    fun accept(frame: PlyFrame.Data): List<ByteArray> {
        val origin = frame.originId
        if (origin !in nextExpected) {
            // Peer-chosen key: admit a new one only while there is room. See the class KDoc for why
            // this refuses rather than evicting, and for what the cap does and does not bound.
            check(nextExpected.size < MAX_ORIGINS) {
                "composite inbound gate is at its cap of $MAX_ORIGINS origins; " +
                    "refusing the frame from unseen origin '${origin.value}'"
            }
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
                nextExpected[origin] = buffer.lowestBufferedSeq()
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

    // commonMain has no sorted map; scan for the min buffered key.
    private fun MutableMap<Long, ByteArray>.lowestBufferedSeq(): Long = keys.min()

    private companion object {
        /**
         * How many distinct origins one gate will ever hold state for.
         *
         * A `const`, not a constructor knob: this is not tuning. The honest working set is one
         * origin per remote composite peer, so any real composite sits three orders of magnitude
         * below this and the only caller that could raise it is the attacker's — a bigger number
         * just buys a bigger table. 256 leaves absurd headroom over any plausible roster while
         * keeping the retained-payload ceiling (`MAX_ORIGINS * maxBuffered`, 4096 frames at the
         * default) finite, which is the whole point.
         */
        const val MAX_ORIGINS = 256
    }
}
