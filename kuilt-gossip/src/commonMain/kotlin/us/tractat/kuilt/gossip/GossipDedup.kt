package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId

/**
 * Bounded relay dedup for [GossipSeam] dissemination (#675).
 *
 * The flood needs to recognise a broadcast it has already delivered + relayed so it can
 * terminate (a node relays each message at most once). The obvious structure — a flat set
 * of every `(origin, seq)` ever seen — is correct but grows without bound with the number
 * of distinct broadcasts. This replaces it with **O(origins)** state:
 *
 * - a per-origin **contiguous high-water mark** ([Origin.high]) — the highest seq `h` such
 *   that every seq `1..h` from that origin has been seen. A frame with `seq <= high` is a
 *   duplicate. Per-origin seqs are 1-based and monotonic (one counter per origin), so in the
 *   common in-order case the high-water is the *only* state kept.
 * - a small per-origin **reorder window** ([Origin.reorder]) — seqs seen *above* the
 *   high-water out of order (a relay can reorder frames). A frame already in the window is a
 *   duplicate; a fresh one is added, and the high-water advances over any now-contiguous run.
 *
 * **Persistent gaps stay bounded.** A flood can drop a frame permanently (recovered later by
 * anti-entropy as CRDT state, never re-broadcast), so the high-water can stall at a gap while
 * later seqs pile into the reorder window. The window is capped at [maxReorder]: on overflow
 * the high-water is forced forward to the lowest outstanding seq, abandoning the missing frames
 * below it (treated as seen — anti-entropy backstops them). Memory is therefore bounded to
 * `O(origins × maxReorder)` regardless of throughput.
 *
 * Not thread-safe: like the flat set it replaces, it is mutated only on [GossipSeam]'s single
 * `base.incoming` collector (ADR-034 single-collection), so it needs no lock.
 */
internal class GossipDedup(private val maxReorder: Int = DEFAULT_MAX_REORDER) {
    private class Origin {
        var high: Long = 0L
        val reorder = mutableSetOf<Long>()
    }

    private val origins = mutableMapOf<PeerId, Origin>()

    /**
     * Records `(origin, seq)` as seen, returning `true` if it was *new* (deliver + relay it)
     * or `false` if it is a duplicate that should be dropped.
     */
    fun markSeenIfNew(
        origin: PeerId,
        seq: Long,
    ): Boolean {
        val state = origins.getOrPut(origin) { Origin() }
        if (seq <= state.high) return false
        if (!state.reorder.add(seq)) return false

        advanceContiguous(state)
        if (state.reorder.size > maxReorder) forceForwardPastGap(state)
        return true
    }

    /** Slides the high-water mark up over the contiguous run sitting in the reorder window. */
    private fun advanceContiguous(state: Origin) {
        while (state.reorder.remove(state.high + 1)) state.high++
    }

    /**
     * The reorder window overflowed: a gap below it never filled. Jump the high-water to the
     * lowest outstanding seq (abandoning the missing frames beneath it), then re-drain any run
     * that becomes contiguous. Leaves the window at `<= maxReorder` entries.
     */
    private fun forceForwardPastGap(state: Origin) {
        val lowest = state.reorder.min()
        state.reorder.remove(lowest)
        state.high = lowest
        advanceContiguous(state)
    }

    /** Total tracked entries — `O(origins)`; the bound this structure guarantees. */
    val trackedEntryCount: Int get() = origins.size + origins.values.sumOf { it.reorder.size }

    companion object {
        /**
         * Per-origin reorder window cap. Comfortably above the reordering a k-regular overlay
         * relay introduces at the tens–low-hundreds target scale; the bound only bites under a
         * persistent gap, which anti-entropy backstops anyway.
         */
        const val DEFAULT_MAX_REORDER = 64
    }
}
