package us.tractat.kuilt.crdt

/**
 * Builds the per-replica clock an [EphemeralMap] slot is stamped with, packed so that a
 * restarted replica always out-clocks its own dead incarnation.
 *
 * ## Why a plain counter is not enough
 *
 * [EphemeralMap.put] keeps the higher clock per slot. A replica that restarts begins counting
 * from zero again, so its fresh heartbeats sort *below* the entries its previous boot left
 * behind, and peers keep showing the dead incarnation. TTL eviction recovers it (see the
 * "Reconnect and clock-reset recovery" section of [EphemeralMap]), but only after a full
 * window, and only for observers whose slot has actually aged out.
 *
 * ## The packing
 *
 * The clock is one `Long` split in two: a per-boot **incarnation epoch** in the high bits, and
 * a monotonic per-boot counter in the low [COUNTER_BITS]. Anything a boot can reach is bounded
 * below the next epoch's [base], so a restart at a strictly greater epoch strictly dominates —
 * by arithmetic, not by TTL timing.
 *
 * The epoch must be non-decreasing across restarts of the *same* replica and must strictly
 * increase on each restart. Persisted boot counters and monotonically-sourced timestamps both
 * work; a fresh random number does not.
 *
 * ## Serialisation
 *
 * Neither function keeps state: callers hold the current clock and thread it through [next].
 * That mutation must be serialised by the caller (both shipped consumers advance the clock
 * inside the lock that guards their board).
 */
public object IncarnationClock {

    /** Low bits reserved for the per-boot counter; the incarnation epoch occupies the bits above. */
    public const val COUNTER_BITS: Int = 32

    /** The largest epoch that fits above [COUNTER_BITS] while keeping the clock non-negative. */
    private const val MAX_EPOCH: Long = 1L shl 31

    /**
     * The starting clock for a replica booted at [epoch] — the epoch shifted into the high bits,
     * with the per-boot counter at zero.
     *
     * @throws IllegalArgumentException if [epoch] is negative or does not fit above [COUNTER_BITS].
     */
    public fun base(epoch: Long): Long {
        require(epoch in 0 until MAX_EPOCH) { "epoch must be in [0, 2^31), was $epoch" }
        return epoch shl COUNTER_BITS
    }

    /**
     * The clock after [current], enforcing the invariant [base] rests on: the per-boot counter
     * must never carry into the epoch bits.
     *
     * A carry would silently borrow the *next* boot's epoch, so the next restart would no longer
     * strictly out-clock this incarnation and would quietly fall back to TTL-bounded recovery.
     * Reaching it takes 2^[COUNTER_BITS] publishes within a single boot — so this fails loudly
     * rather than degrading in silence.
     *
     * @throws IllegalStateException if the per-boot counter is exhausted.
     */
    public fun next(current: Long): Long {
        val next = current + 1
        check((next shr COUNTER_BITS) == (current shr COUNTER_BITS)) {
            "per-boot clock counter exhausted after 2^$COUNTER_BITS publishes in one boot; " +
                "the next restart would no longer out-clock this incarnation"
        }
        return next
    }
}
