package us.tractat.kuilt.heddle

/**
 * Whether [weight] could be activated at all: `true` when it fits the weight budget as the only
 * active child of its parent, `false` when no parent could ever take it.
 *
 * A heddle refuses an activation whose weights would push a product a pick forms out of `Long`, or
 * make the grid so fine that one full lease of [LocalHeddle.MAX_RESERVATION] no longer fits a
 * child's window. A weight that fails alone fails beside any siblings, so this settles the question
 * before the edge exists. A denominator above 30,719 fails it at any lease size, since the rule is
 * sized for the largest lease and no grant is larger (#3353). A `true` here is necessary, not
 * sufficient: the siblings already active can still refuse it, which [activationFitsTheBudget] asks.
 *
 * The rule is the one [LocalHeddle.apply] enforces, asked without applying anything. Use it to vet
 * a weight before proposing the edge, rather than re-deriving the arithmetic.
 *
 * @sample us.tractat.kuilt.heddle.sampleWeightBudget
 */
public fun weightFitsTheBudget(weight: Weight): Boolean = weightsFitTheBudget(listOf(weight))

/**
 * Whether activating [edge] on this ledger keeps its parent's active children inside the weight
 * budget: `true` when [LocalHeddle.apply] would let the activation through on budget grounds,
 * `false` when it would refuse it as over budget.
 *
 * It weighs the whole set the parent would have, the children already active there other than
 * [edge] with [edge]'s own weight beside them, not [edge] alone: a new child can refine every
 * sibling's grid step, so activating a `1/3` can refuse on behalf of a heavy sibling (#3353).
 *
 * `true` for an edge with no single record, unknown or divergent, since [EntitlementLedger.activate]
 * refuses that one on its own. A sibling whose record is divergent is left out, as
 * [LocalHeddle.apply] leaves it out: the policy drops such an edge, so it never enters a pick.
 * Budget only: an activation that passes here can still be refused for the position limit.
 *
 * @sample us.tractat.kuilt.heddle.sampleWeightBudget
 */
public fun EntitlementLedger.activationFitsTheBudget(edge: AttachmentId): Boolean {
    val record = record(edge) ?: return true
    val siblings = activeChildren(record.parent).filter { it.attachment != edge }.mapNotNull { record(it.attachment)?.weight }
    return weightsFitTheBudget(siblings + record.weight)
}
