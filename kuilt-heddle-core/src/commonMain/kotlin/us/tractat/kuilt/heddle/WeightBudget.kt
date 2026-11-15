package us.tractat.kuilt.heddle

/**
 * Whether [weight], as the only child of its parent, fits the weight budget: every product a pick
 * forms stays in `Long`, and one full lease of [LocalHeddle.MAX_RESERVATION] fits its window. A
 * denominator above 30,719 fails it at any lease size, since the rule is sized for the largest
 * lease and no grant is larger (#3353).
 *
 * A weight that fails this alone fails beside any siblings, so a replicated `Prepare` of it can
 * be refused before the edge exists. The rule is the one [LocalHeddle.apply] enforces; this is a
 * window onto it for `:kuilt-heddle`, not a second copy (#3353).
 */
@HeddleInternalApi
public fun weightFitsTheBudget(weight: Weight): Boolean = weightsFitTheBudget(listOf(weight))

/**
 * Whether activating [edge] keeps its parent's active children inside the weight budget: the
 * children already active there, other than [edge], with [edge]'s own weight beside them. The
 * whole set is checked, not [edge] alone: a new child can refine every sibling's grid step, so
 * activating a `1/3` can refuse on behalf of a heavy sibling (#3353).
 *
 * `true` for an edge with no single record, unknown or divergent, since [EntitlementLedger.activate]
 * refuses that one on its own. A sibling whose record is divergent is left out, as
 * [LocalHeddle.apply] leaves it out: the policy drops such an edge, so it never enters a pick.
 */
@HeddleInternalApi
public fun EntitlementLedger.activationFitsTheBudget(edge: AttachmentId): Boolean {
    val record = record(edge) ?: return true
    val siblings = activeChildren(record.parent).filter { it.attachment != edge }.mapNotNull { record(it.attachment)?.weight }
    return weightsFitTheBudget(siblings + record.weight)
}
