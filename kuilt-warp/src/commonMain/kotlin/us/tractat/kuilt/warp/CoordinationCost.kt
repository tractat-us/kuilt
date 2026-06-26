package us.tractat.kuilt.warp

/**
 * The E-3 coordination-cost score for a [Draft] pipeline.
 *
 * A planner minimises this cost; an executor pays it. Two dimensions are tracked:
 *
 * **[rounds]** — the number of genuine consensus rounds this plan requires. A
 * fully-monotone [Draft] has 0 rounds; a draft with exactly one [DraftStage.Embroider]
 * has 1. Multiple embroider stages are valid (though uncommon per CALM) and count
 * accordingly. This is the dimension a monotonicity-aware executor pays vs. the
 * pessimistic bound of `stages.size - 1` rounds an unplanned executor would spend
 * (one coordination check per stage boundary, knowing nothing about monotonicity).
 *
 * **[coordinatedVolume]** — the estimated number of elements that enter the
 * coordinated stage(s). When [rounds] == 0, this is 0. When [rounds] == 1 this is
 * the cardinality at the [DraftStage.Embroider]'s position in the pipeline —
 * meaning filters that precede the embroider reduce this number (the planner's
 * primary lever on volume). The estimate is produced by [Draft.coordinationCost]
 * from [WarpStats] HyperLogLog sketches; unknown filter selectivities are treated
 * conservatively (no reduction assumed).
 *
 * **Ordering.** Costs are compared lexicographically: fewer rounds first, then lower
 * volume. A draft with 0 rounds (fully monotone) always beats one with 1 round,
 * regardless of volume.
 *
 * **Rounds vs. volume — what the planner actually optimises.** In the current model
 * a well-formed [Draft] has at most one [DraftStage.Embroider], so [rounds] is
 * structurally ≤ 1. The planner's real lever is [coordinatedVolume]: deferring
 * the embroider past selective filters means the consensus step commits over a
 * *smaller* set of elements, not over *fewer* rounds. "Minimise coordination"
 * means "shrink the set that crosses the consensus boundary" — not "reduce round
 * count", which stays at 1 in the single-embroider case. Reducing round count
 * (multi-stage coordination pipelining) is future work.
 *
 * @sample us.tractat.kuilt.warp.sampleCoordinationCost
 * @see Draft.coordinationCost
 * @see Draft.plan
 */
public data class CoordinationCost(
    /** Number of [DraftStage.Embroider] stages — 0 for fully-monotone drafts, 1 or more otherwise. */
    public val rounds: Int,
    /** Estimated elements entering coordinated stages; 0 when [rounds] == 0. */
    public val coordinatedVolume: Long,
) : Comparable<CoordinationCost> {

    /**
     * Lexicographic order: fewer [rounds] first; [coordinatedVolume] as tiebreaker.
     */
    override fun compareTo(other: CoordinationCost): Int {
        val roundsCmp = rounds.compareTo(other.rounds)
        return if (roundsCmp != 0) roundsCmp else coordinatedVolume.compareTo(other.coordinatedVolume)
    }
}
