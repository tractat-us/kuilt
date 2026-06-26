package us.tractat.kuilt.warp

/**
 * E-3 coordination-cost model and planner for [Draft] pipelines.
 *
 * ## Cost model
 *
 * [coordinationCost] scores a [Draft] by the [CoordinationCost] a monotonicity-aware
 * executor would pay: [CoordinationCost.rounds] = the number of [DraftStage.Embroider]
 * stages; [CoordinationCost.coordinatedVolume] = estimated items entering each coordinated
 * stage, derived from [WarpStats] HyperLogLog sketches.
 *
 * The volume estimate exploits filter position. For every filter stage that **precedes** an
 * embroider, the filter's selectivity is read from [WarpStats] and multiplied in. Filters
 * with no observed output in [WarpStats] are conservative: they contribute no reduction
 * (selectivity = 1). This means:
 * - An embroider placed **before** filters sees the full source cardinality.
 * - An embroider placed **after** filters sees the reduced, filtered cardinality.
 * The E-2 rewrites exploit this: [plan] defers the embroider past all free stages so the
 * coordinated volume is as small as the data allows.
 *
 * ## Planner
 *
 * [plan] applies the three E-2 rewrite rules — [deferEmbroidery], [pushdownFilters],
 * [fuseAdjacent] — to a fixpoint (via [optimize]), minimising [coordinationCost].
 *
 * Stats are used for cost **measurement** (see [coordinationCost]) but not for rewrite
 * **selection**: all rewrites are structurally determined by [CoordinationKind] tags and
 * are correct regardless of cardinality. Stats-aware filter ordering (most-selective first)
 * reduces per-stage CPU cost but not coordination cost; it is a future enhancement.
 *
 * **What "minimise coordination" means here.** A well-formed [Draft] has at most one
 * [DraftStage.Embroider], so [CoordinationCost.rounds] is structurally ≤ 1. The
 * planner's demonstrated win is a cut in [CoordinationCost.coordinatedVolume] — the
 * consensus step commits over a smaller, more-filtered set — not in round count, which
 * stays at 1 in the single-embroider case. See [CoordinationCost] for the precise
 * definition. Reducing round count is future work.
 *
 * @see CoordinationCost
 * @see Draft.coordinationCost
 * @see Draft.plan
 */

// ── Cost function ─────────────────────────────────────────────────────────────

/**
 * Scores this [Draft] by the [CoordinationCost] a monotonicity-aware executor would pay.
 *
 * - [CoordinationCost.rounds] = number of [DraftStage.Embroider] stages (0 for fully-
 *   monotone drafts, 1 when there is exactly one embroider).
 * - [CoordinationCost.coordinatedVolume] = estimated elements entering the embroider
 *   stage, derived by applying per-filter selectivities from [stats].
 *
 * Selectivity of a filter: `stats.estimatedCardinality(filterOpId) / sourceCardinality`.
 * Unknown filters (zero cardinality in [stats]) are conservative — they contribute no
 * volume reduction. This ensures the estimate is never less than the true volume.
 *
 * Call [plan] first to minimise this cost before scoring.
 *
 * @sample us.tractat.kuilt.warp.sampleCoordinationCost
 * @see plan
 * @see CoordinationCost
 */
public fun <T> Draft<T>.coordinationCost(stats: WarpStats): CoordinationCost {
    val embroiderIndex = stages.indexOfFirst { it is DraftStage.Embroider }
    if (embroiderIndex < 0) return CoordinationCost(rounds = 0, coordinatedVolume = 0L)
    val source = stages.filterIsInstance<DraftStage.Source>().singleOrNull()
        ?: return CoordinationCost(rounds = 1, coordinatedVolume = 0L)
    val sourceCardinality = stats.estimatedCardinality(source.opId)
    val precedingStages = stages.take(embroiderIndex)
    return CoordinationCost(
        rounds = 1,
        coordinatedVolume = volumeAfterFilters(precedingStages, sourceCardinality, stats),
    )
}

// ── Planner ───────────────────────────────────────────────────────────────────

/**
 * Returns a [Draft] that minimises [coordinationCost] by applying the E-2 rewrite rules
 * to a fixpoint.
 *
 * The primary lever is [deferEmbroidery]: moving the [DraftStage.Embroider] past all
 * [CoordinationKind.Free] filter stages maximises the volume reduction before the
 * coordinated step — meaning the consensus commit covers a smaller, more-filtered
 * element set. In the single-embroider case, [CoordinationCost.rounds] stays at 1
 * regardless of rewriting; the demonstrated win is a cut in
 * [CoordinationCost.coordinatedVolume]. [pushdownFilters] and [fuseAdjacent] complete
 * the optimisation.
 *
 * The returned [Draft] is always semantically equivalent to the receiver under
 * [isEquivalentTo] — the rewrite never changes the convergent result.
 *
 * @param stats reserved for future stats-aware reordering (e.g. ordering filters by
 *   ascending selectivity to reduce per-stage CPU cost). Currently unused — all rewrites
 *   are structurally determined by [CoordinationKind] tags and do not require cardinality
 *   data. Use [coordinationCost] to measure the plan's quality after calling [plan].
 *
 * @sample us.tractat.kuilt.warp.sampleCoordinationCost
 * @see coordinationCost
 * @see optimize
 */
public fun <T> Draft<T>.plan(
    @Suppress("UnusedParameter") stats: WarpStats,
): Draft<T> = optimize()

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Estimates the number of elements that survive all filter stages in [stages].
 *
 * For each filter op id, if [stats] has a non-zero observed cardinality, its selectivity
 * relative to [sourceCardinality] is multiplied into the running volume. Zero-cardinality
 * filters (not yet observed in [stats]) contribute no reduction — a conservative choice
 * that avoids under-estimating the coordinated volume.
 */
private fun volumeAfterFilters(
    stages: List<DraftStage>,
    sourceCardinality: Long,
    stats: WarpStats,
): Long {
    if (sourceCardinality == 0L) return 0L
    val filterIds = stages.filterOpIds()
    if (filterIds.isEmpty()) return sourceCardinality
    val volume = filterIds.fold(sourceCardinality.toDouble()) { remaining, opId ->
        val observed = stats.estimatedCardinality(opId)
        if (observed > 0L) remaining * (observed.toDouble() / sourceCardinality)
        else remaining
    }
    return volume.toLong()
}

/**
 * Collects all filter [OpId]s from [CoordinationKind.Free] filter-kind stages,
 * flattening [DraftStage.FusedFilter] into their constituent ids.
 * Source and Embroider stages are excluded.
 */
private fun List<DraftStage>.filterOpIds(): List<OpId> =
    filter { it.coordinationKind == CoordinationKind.Free && it !is DraftStage.Source }
        .flatMap { stage ->
            when (stage) {
                is DraftStage.Filter -> listOf(stage.opId)
                is DraftStage.FusedFilter -> stage.opIds
                else -> emptyList()
            }
        }
