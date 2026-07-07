package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId

/**
 * Bounded relay dedup + per-origin reorder buffer for [GossipSeam] dissemination
 * (#675, #1272).
 *
 * The flood needs two properties from this structure:
 *
 * 1. **Dedup** — recognise a broadcast already seen so the flood terminates (a node
 *    relays each message at most once).
 * 2. **Per-origin send order** — `Seam.incoming` promises frames "in send order", and
 *    [GossipSeam] re-stamps delivered swatches with `sender = origin`, so same-origin
 *    frames must surface to the application in the origin's send order even when racing
 *    relay paths deliver them reordered (#1272).
 *
 * The obvious structure — a flat set of every `(origin, seq)` ever seen — is correct for
 * dedup but grows without bound. This keeps **O(origins)** steady-state entries instead:
 *
 * - a per-origin **contiguous high-water mark** ([Origin.high]) — the highest seq `h` such
 *   that every seq `1..h` from that origin has been *delivered*. A frame with `seq <= high`
 *   is a duplicate. Per-origin seqs are 1-based and monotonic (one counter per origin), so
 *   in the common in-order case the high-water is the *only* state kept.
 * - a small per-origin **reorder window** ([Origin.pending]) — frames seen *above* the
 *   high-water out of order, **held** (not delivered) until the gap below them fills. A
 *   frame already in the window is a duplicate; a fresh one is admitted (relay it), and any
 *   run that becomes contiguous with the high-water is released for delivery in seq order.
 *
 * **Abandoned gaps release in bounded space *and* bounded time.** A flood can drop a frame
 * permanently (recovered later by anti-entropy as CRDT state, never re-broadcast), and a peer
 * can first sight an origin mid-stream (a late joiner sees its first frame at `seq » 1`), so a
 * blocking gap may never fill. Two force-forward triggers bound the hold:
 *
 * - **Space** — the window is capped at [maxReorder]: on overflow the high-water is forced
 *   forward to the lowest held seq, abandoning the missing frames below it (treated as seen —
 *   anti-entropy backstops them) and releasing the held run in order. Entry count is therefore
 *   bounded to `O(origins × maxReorder)` regardless of throughput; each held entry retains its
 *   frame payload until release.
 * - **Time** — [releaseExpired] force-forwards past any gap that has been blocking for longer
 *   than the caller's grace period. Relay reordering resolves within a few hops' latency, so a
 *   gap older than the grace is a genuine drop or a pre-join seq — without this, a held frame
 *   would wait for [maxReorder] more same-origin frames that a quiet room never sends.
 *   [GossipSeam] sweeps this from its single inbound event loop.
 *
 * Not thread-safe: mutated only on [GossipSeam]'s single inbound event loop (ADR-034
 * single-collection), so it needs no lock.
 */
internal class GossipDedup(private val maxReorder: Int = DEFAULT_MAX_REORDER) {
    private class Origin {
        var high: Long = 0L
        val pending = mutableMapOf<Long, GossipFrame>()

        /**
         * Epoch-ms when the gap currently blocking [pending] became the blocker, or `null`
         * when nothing is held. Reset whenever the high-water advances while frames remain
         * held — each successive gap gets its own grace window.
         */
        var blockedSinceMs: Long? = null
    }

    /**
     * Outcome of [admit]: [isNew] is `true` the first time this `(origin, seq)` is seen
     * (re-flood it — relay never waits on the reorder window); [deliverable] is the run of
     * frames now releasable to the application, in per-origin send order (possibly empty
     * when the admitted frame is held for an earlier gap, possibly several when it fills one).
     */
    class Admission(val isNew: Boolean, val deliverable: List<GossipFrame>) {
        companion object {
            val DUPLICATE = Admission(isNew = false, deliverable = emptyList())
        }
    }

    private val origins = mutableMapOf<PeerId, Origin>()

    /**
     * Records [frame]'s `(origin, seq)` as seen at [nowMs] and reorders it for delivery. See
     * [Admission] for the two-part outcome; a duplicate yields `Admission.DUPLICATE` (drop
     * the frame).
     */
    fun admit(
        frame: GossipFrame,
        nowMs: Long,
    ): Admission {
        val state = origins.getOrPut(frame.origin) { Origin() }
        if (frame.seq <= state.high) return Admission.DUPLICATE
        if (frame.seq in state.pending) return Admission.DUPLICATE
        state.pending[frame.seq] = frame

        val highBefore = state.high
        val deliverable = mutableListOf<GossipFrame>()
        releaseContiguous(state, deliverable)
        if (state.pending.size > maxReorder) forceForwardPastGap(state, deliverable)
        restartGraceIfBlockerChanged(state, highBefore, nowMs)
        return Admission(isNew = true, deliverable = deliverable)
    }

    /**
     * Force-forwards past every gap that has been blocking for at least [graceMs] as of
     * [nowMs], returning the released frames in per-origin send order. Call periodically
     * (from the same single event loop that calls [admit]) so a held frame's delivery
     * latency is bounded by the grace even when no further same-origin traffic arrives.
     */
    fun releaseExpired(
        nowMs: Long,
        graceMs: Long,
    ): List<GossipFrame> {
        val released = mutableListOf<GossipFrame>()
        for (state in origins.values) {
            val blockedSince = state.blockedSinceMs ?: continue
            if (nowMs - blockedSince < graceMs) continue
            val highBefore = state.high
            forceForwardPastGap(state, released)
            restartGraceIfBlockerChanged(state, highBefore, nowMs)
        }
        return released
    }

    /**
     * Maintains [Origin.blockedSinceMs]: cleared when nothing is held; restarted at [nowMs]
     * when the blocking gap changed (the high-water moved past [highBefore], or frames just
     * became held) so each gap gets a full grace window of its own.
     */
    private fun restartGraceIfBlockerChanged(
        state: Origin,
        highBefore: Long,
        nowMs: Long,
    ) {
        state.blockedSinceMs =
            when {
                state.pending.isEmpty() -> null
                state.blockedSinceMs == null || state.high > highBefore -> nowMs
                else -> state.blockedSinceMs
            }
    }

    /** Releases the run contiguous with the high-water mark, sliding the mark over it. */
    private fun releaseContiguous(
        state: Origin,
        released: MutableList<GossipFrame>,
    ) {
        while (true) {
            val next = state.pending.remove(state.high + 1) ?: break
            state.high++
            released += next
        }
    }

    /**
     * The reorder window overflowed: a gap below it never filled. Jump the high-water to the
     * lowest held seq (abandoning the missing frames beneath it — anti-entropy backstops
     * them), release that frame, then release any run that becomes contiguous. Leaves the
     * window at `<= maxReorder` entries.
     */
    private fun forceForwardPastGap(
        state: Origin,
        released: MutableList<GossipFrame>,
    ) {
        val lowest = state.pending.keys.min()
        released += state.pending.remove(lowest)!!
        state.high = lowest
        releaseContiguous(state, released)
    }

    /** Total tracked entries — `O(origins)` steady-state; the bound this structure guarantees. */
    val trackedEntryCount: Int get() = origins.size + origins.values.sumOf { it.pending.size }

    companion object {
        /**
         * Per-origin reorder window cap. Comfortably above the reordering a k-regular overlay
         * relay introduces at the tens–low-hundreds target scale; the bound only bites under a
         * persistent gap, which anti-entropy backstops anyway.
         */
        const val DEFAULT_MAX_REORDER = 64
    }
}
