package us.tractat.kuilt.heddle

/**
 * What [FairSharePool.acquire] answered: a slot, or the reason none can be granted to that leaf
 * as the tree stands now.
 *
 * @sample us.tractat.kuilt.heddle.sampleAcquireSlot
 */
public sealed interface SlotOutcome {
    /**
     * The caller holds [lease], one of the pool's slots, and must [FairSharePool.complete] or
     * [FairSharePool.cancel] it.
     */
    public data class Granted(public val lease: Lease) : SlotOutcome

    /**
     * No lease can be granted to the leaf: [reason] is the engine's own explanation,
     * [WaitExplanation.NoLiveLineage] for a name no lease can end at, or
     * [WaitExplanation.LifetimeExhausted] for a lane whose lifetime counter is full.
     *
     * @throws IllegalArgumentException when [reason] is any other explanation: those describe a
     *   wait, not a refusal.
     */
    public data class Refused(public val reason: WaitExplanation) : SlotOutcome {
        init {
            require(reason is WaitExplanation.NoLiveLineage || reason is WaitExplanation.LifetimeExhausted) {
                "a refusal's reason is NoLiveLineage or LifetimeExhausted, was $reason"
            }
        }
    }
}
