package us.tractat.kuilt.warp

/**
 * The G4 coordination-cost score for a [Draft] pipeline.
 *
 * A planner minimises this cost; an executor pays it. Three dimensions are tracked:
 *
 * **[rounds]** — the count of coordinated nodes in the planned DAG. Each [DraftStage.Embroider]
 * contributes 1 round; each [DraftStage.BatchedEmbroider] also contributes **1 round** —
 * a batched proposal carries an entire level's worth of agreements in one Raft round-trip,
 * regardless of how many ops it batches. So:
 * - A fully-monotone [Draft] has 0 rounds.
 * - K independent [DraftStage.Embroider] nodes (not consolidated) → [rounds] = K.
 * - Those same K nodes consolidated into one [DraftStage.BatchedEmbroider] (one dependency
 *   level) → [rounds] = 1.
 * - A dependency chain with L levels → [rounds] = L (the DAG depth), one node per level.
 *
 * [rounds] is therefore an active lever: calling [Draft.plan] (which includes consolidation)
 * drives `coordinationCost(plan(draft)).rounds ≤ coordinationCost(draft).rounds`, with strict
 * `<` whenever independent agreements exist to batch. This is the improvement E-3 could not
 * show (E-3 was pinned at `rounds ≤ 1` on a single-embroider linear pipeline).
 *
 * **[coupling]** — the blast-radius term: the maximum batch size ([DraftStage.BatchedEmbroider.opIds]
 * size) across all coordinated nodes. A [DraftStage.Embroider] contributes 1; a fully-monotone
 * draft contributes 0. Batching K agreements into one round is efficient, but it couples their
 * failure domains: a single rejection forces a retry of all K. The planner minimises [rounds]
 * first; [coupling] is the secondary objective, reflecting the honest tradeoff — fewer rounds
 * at the cost of a larger retry unit. A future planner might cap batch size explicitly.
 *
 * **[coordinatedVolume]** — the estimated number of elements entering all coordinated stages
 * combined. When [rounds] == 0, this is 0. For each coordinated node the estimate derives from
 * [WarpStats] HyperLogLog sketches via a predecessor walk; unknown filter selectivities are
 * treated conservatively. The estimate is produced by [Draft.coordinationCost].
 *
 * **Ordering.** Costs are compared lexicographically — minimise [rounds] first, then [coupling],
 * then [coordinatedVolume]. A draft with fewer rounds always beats one with more, regardless of
 * the other two dimensions. When rounds are tied, the smaller-batch option wins (less blast
 * radius); volume is the final tiebreaker.
 *
 * @sample us.tractat.kuilt.warp.sampleCoordinationCost
 * @sample us.tractat.kuilt.warp.sampleCoordinationCostDepth
 * @see Draft.coordinationCost
 * @see Draft.plan
 */
public data class CoordinationCost(
    /** Count of coordinated nodes in the planned DAG — 0 for fully-monotone; 1 per round otherwise. */
    public val rounds: Int,
    /**
     * Maximum batch size across all coordinated nodes — 0 when [rounds] is 0, 1 for a lone
     * [DraftStage.Embroider], and [DraftStage.BatchedEmbroider.opIds].size for a batched node.
     *
     * Represents the blast-radius of the largest consensus proposal: a rejected batch forces
     * a retry of all [coupling] agreements. Minimised as a secondary objective after [rounds].
     */
    public val coupling: Int,
    /** Estimated elements entering all coordinated stage(s); 0 when [rounds] == 0. */
    public val coordinatedVolume: Long,
) : Comparable<CoordinationCost> {

    /**
     * Lexicographic order: fewer [rounds] first; smaller [coupling] as secondary; lower
     * [coordinatedVolume] as tertiary tiebreaker.
     */
    override fun compareTo(other: CoordinationCost): Int {
        val roundsCmp = rounds.compareTo(other.rounds)
        if (roundsCmp != 0) return roundsCmp
        val couplingCmp = coupling.compareTo(other.coupling)
        return if (couplingCmp != 0) couplingCmp else coordinatedVolume.compareTo(other.coordinatedVolume)
    }
}
