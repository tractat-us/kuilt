package us.tractat.kuilt.heddle

/**
 * What [FairSharePool.withSlot] throws where [FairSharePool.acquire] would answer
 * [SlotOutcome.Refused]: no lease can be granted to the leaf as the tree stands now, so the block
 * never runs.
 *
 * @property reason the engine's own explanation, [WaitExplanation.NoLiveLineage] for a name no lease
 *   can end at, or [WaitExplanation.LifetimeExhausted] for a lane whose lifetime counter is full.
 */
public class SlotRefusedException(public val reason: WaitExplanation) :
    IllegalStateException("no lease can be granted for this leaf: $reason")
