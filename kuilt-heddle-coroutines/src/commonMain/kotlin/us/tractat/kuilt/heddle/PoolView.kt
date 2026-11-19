package us.tractat.kuilt.heddle

/**
 * What a [FairSharePool] holds at one moment: who is queued for which lane, how many slots are in
 * use, and how many there are. A status page that shows "who goes next" reads it rather than
 * keeping its own count beside the pool's, which would race it.
 *
 * Get one from [FairSharePool.view], or as the second argument of a [FairSharePool.withEngine]
 * block. Either way it is copied under the pool's lock, so it shares nothing with the pool and
 * does not change as the pool does: read it from any thread, keep it as long as you like.
 *
 * [queues] has the shape [LocalHeddle.previewPicks] takes, so inside a `withEngine` block
 * `engine.previewPicks(view.queues, view.waiting)` forecasts the order the pool grants in. The
 * forecast is exact when the slots are raised with [FairSharePool.resize] and nothing else happens
 * before the grants. Anything else can change the order: a lease completed at a cost other than its
 * reservation, which refunds or overruns against the waiting set of that moment, or a waiter
 * arriving, leaving or being refused. The preview also stops where the pool goes on: at a waiter
 * whose lease the engine would not extend to its reservation, which the pool grants at the default,
 * and at a leaf the engine reports as [WaitExplanation.NoLiveLineage] or
 * [WaitExplanation.LifetimeExhausted], whose waiters the pool refuses.
 *
 * @property queues each leaf with a waiter queued, in the order its first waiter arrived, mapped to
 *   its waiters' reservations, head first. A leaf with no waiter is absent, never mapped to an
 *   empty list.
 * @property open how many leases the pool counts against [slots], including any it adopted from
 *   the engine. It can be above [slots] after [FairSharePool.resize] lowered the count.
 * @property slots how many leases may be open at once.
 * @throws IllegalArgumentException when [open] or [slots] is negative, a queue is empty, or a
 *   reservation is not in `1..`[LocalHeddle.MAX_RESERVATION].
 * @sample us.tractat.kuilt.heddle.samplePreviewThePool
 */
public data class PoolView(
    public val queues: Map<GroupId, List<Long>>,
    public val open: Int,
    public val slots: Int,
) {
    init {
        require(open >= 0) { "open must be non-negative, was $open" }
        require(slots >= 0) { "slots must be non-negative, was $slots" }
        for ((leaf, queue) in queues) {
            require(queue.isNotEmpty()) { "${leaf.value} is mapped to an empty queue" }
            for (reservation in queue) {
                // Checked inline, so the message is built only for the reservation that fails.
                require(reservation in 1L..LocalHeddle.MAX_RESERVATION) {
                    "a queued reservation of ${leaf.value} must be in 1..${LocalHeddle.MAX_RESERVATION}, was $reservation"
                }
            }
        }
    }

    /** Every leaf with a waiter queued: the set the engine's calls take as `waiting` and `eligible`. */
    public val waiting: Set<GroupId> get() = queues.keys
}
