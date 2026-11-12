package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * How much **more** service a child could usefully take right now — the child's
 * advertised appetite, and nothing about its authority.
 *
 * A busy child says "I could keep another [targetOutstanding] units of unspent
 * entitlement usefully occupied, and don't hand me more than [maximumUsefulGrant]
 * in one go." A child with no work advertises [NONE]. The parent folds every live
 * child's demand when it decides who to serve next (design §6, §7.3).
 *
 * `Demand` is **advisory, never authority**. It can be stale, duplicated, or lost
 * and the worst outcome is entitlement briefly parked in the wrong pocket — it can
 * never authorize a spend, because spends check the ledger's *holdings*, which are
 * blind to demand. That separation is the whole reason demand rides a non-durable
 * presence channel while entitlement rides the ledger (design §6): here in the pure
 * policy it is simply an immutable value the caller supplies per edge.
 *
 * @property targetOutstanding total unspent entitlement the child would find useful
 *   to hold; the parent grants only enough to reach it. Non-negative.
 * @property maximumUsefulGrant the largest single grant the child can absorb at once;
 *   caps one allocation quantum. Non-negative.
 */
@Serializable
public data class Demand(
    public val targetOutstanding: Long,
    public val maximumUsefulGrant: Long,
) {
    init {
        require(targetOutstanding >= 0L) { "targetOutstanding must be non-negative, was $targetOutstanding" }
        require(maximumUsefulGrant >= 0L) { "maximumUsefulGrant must be non-negative, was $maximumUsefulGrant" }
    }

    public companion object {
        /** A child that wants nothing — advertises no appetite this round. */
        public val NONE: Demand = Demand(targetOutstanding = 0L, maximumUsefulGrant = 0L)
    }
}
