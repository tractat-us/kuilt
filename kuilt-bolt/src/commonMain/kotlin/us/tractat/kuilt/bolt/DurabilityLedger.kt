package us.tractat.kuilt.bolt

/**
 * The outstanding durability doubt of one archive — what [Bolt.durability] reports.
 *
 * Shared by both memory-mapped backends deliberately. The stickiness rule below is short to state
 * and easy to get subtly different twice, and a consumer comparing the JVM and Apple archives must
 * find **one** answer rather than two that nearly agree. The backends differ only in what they can
 * flush and how they learn it failed; what a failure *means* lives here.
 *
 * ### The rule
 *
 * A failure **widens**: the range grows to cover every frame whose durability is in doubt, and the
 * reason is the most recent one. A success **clears only if it covers the whole outstanding range** —
 * a flush of a later frame says nothing about an earlier frame's pages, so a partial success leaves
 * the doubt standing. That asymmetry is deliberate and points the safe way: this may report doubt
 * that has since been resolved, and must never report confidence it has not earned.
 *
 * ### Not thread-safe, on purpose
 *
 * Every caller already holds the lock guarding its archive's cursor, and the offsets recorded here
 * are that cursor's. A second lock here could only let the two disagree.
 */
internal class DurabilityLedger {

    private var outstanding: DurabilityState.Degraded? = null

    /**
     * Record that a flush covering `[fromOffset, toOffset)` failed.
     *
     * An empty range is meaningful rather than ignorable — it is a segment header's flush, which
     * carries no frame of its own but whose loss puts the segment behind it in doubt.
     */
    fun flushFailed(fromOffset: Long, toOffset: Long, reason: String, cause: Throwable? = null) {
        val open = outstanding
        outstanding = if (open == null) {
            DurabilityState.Degraded(fromOffset, toOffset, reason, cause)
        } else {
            DurabilityState.Degraded(
                fromOffset = minOf(open.fromOffset, fromOffset),
                toOffset = maxOf(open.toOffset, toOffset),
                reason = reason,
                cause = cause,
            )
        }
    }

    /**
     * Record that a flush covering `[fromOffset, toOffset)` succeeded, clearing the doubt if it
     * covers all of it.
     *
     * Callers pass the range the flush **actually** covered, not the frame that asked for it: both
     * backends flush at page or segment granularity, so a frame's flush routinely re-flushes
     * everything before it in the same region, and that is what makes recovery reachable at all.
     */
    fun flushSucceeded(fromOffset: Long, toOffset: Long) {
        val open = outstanding ?: return
        if (fromOffset <= open.fromOffset && toOffset >= open.toOffset) outstanding = null
    }

    fun state(): DurabilityState = outstanding ?: DurabilityState.AsPromised
}
