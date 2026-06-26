package us.tractat.kuilt.warp

/**
 * E-3 coordination-cost model and planner for [Draft] pipelines.
 *
 * ## Cost model
 *
 * [coordinationCost] scores a [Draft] by the [CoordinationCost] a monotonicity-aware
 * executor would pay: [CoordinationCost.rounds] = the number of [DraftStage.Embroider]
 * nodes; [CoordinationCost.coordinatedVolume] = estimated items entering the embroider
 * stage(s), derived from [WarpStats] HyperLogLog sketches.
 *
 * ## Graph-local volume computation
 *
 * The volume estimate walks the predecessor graph: for a given Embroider node, the
 * algorithm traverses its ancestor nodes to collect all Filter stages that precede it on
 * the path from the Source. Only ancestors that are predecessor-reachable from the
 * Embroider (i.e. between the Source and the Embroider in topological order) contribute
 * to the volume reduction. This generalises correctly to multi-branch DAGs (G4+): filters
 * on a branch only reduce the volume on that branch's path to the embroider.
 *
 * For a path (the degenerate G1 case), walking predecessors is equivalent to
 * `stages.take(embroiderIndex)` — same result, graph-structural expression.
 *
 * ## Planner
 *
 * [plan] applies the three E-2 rewrite rules — [deferEmbroidery], [pushdownFilters],
 * [fuseAdjacent] — to a fixpoint (via [optimize]), minimising [coordinationCost].
 *
 * Stats are used for cost **measurement** (see [coordinationCost]) but not for rewrite
 * **selection**: all rewrites are structurally determined by [CoordinationKind] tags and
 * are correct regardless of cardinality.
 *
 * **What "minimise coordination" means here.** A well-formed path [Draft] has at most one
 * [DraftStage.Embroider], so [CoordinationCost.rounds] is structurally ≤ 1. The
 * planner's demonstrated win is a cut in [CoordinationCost.coordinatedVolume] — the
 * consensus step commits over a smaller, more-filtered set. G4 will make round count an
 * active lever (DAG depth).
 *
 * @see CoordinationCost
 * @see Draft.coordinationCost
 * @see Draft.plan
 */

// ── Cost function ─────────────────────────────────────────────────────────────

/**
 * Scores this [Draft] by the [CoordinationCost] a monotonicity-aware executor would pay.
 *
 * - [CoordinationCost.rounds] = number of [DraftStage.Embroider] nodes (0 for fully-
 *   monotone drafts, 1 when there is exactly one embroider).
 * - [CoordinationCost.coordinatedVolume] = estimated elements entering the embroider
 *   stage, derived by walking the predecessor graph and applying per-filter selectivities
 *   from [stats]. For paths, this is equivalent to counting filters that appear before
 *   the embroider in topological order.
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
    val embroiderNode = nodes.firstOrNull { it.stage is DraftStage.Embroider }
        ?: return CoordinationCost(rounds = 0, coordinatedVolume = 0L)

    val sourceNode = nodes.firstOrNull { it.stage is DraftStage.Source }
        ?: return CoordinationCost(rounds = 1, coordinatedVolume = 0L)

    val sourceCardinality = stats.estimatedCardinality((sourceNode.stage as DraftStage.Source).opId)

    // Walk predecessors from the embroider to collect all ancestor nodes reachable from
    // the source. This generalises to DAG branches in G4+: only ancestors on the path
    // through this embroider contribute to its volume estimate.
    val ancestorNodes = nodes.ancestorsOf(embroiderNode.id)

    return CoordinationCost(
        rounds = 1,
        coordinatedVolume = volumeAfterFilters(ancestorNodes, sourceCardinality, stats),
    )
}

// ── Planner ───────────────────────────────────────────────────────────────────

/**
 * Returns a [Draft] that minimises [coordinationCost] by applying the E-2 rewrite rules
 * to a fixpoint.
 *
 * The primary lever is [deferEmbroidery]: moving the [DraftStage.Embroider] past all
 * [CoordinationKind.Free] filter stages maximises the volume reduction before the
 * coordinated step. In the single-embroider case, [CoordinationCost.rounds] stays at 1
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
 * Returns the set of [DraftNode]s that are transitively reachable by following predecessor
 * edges from [nodeId] (exclusive of [nodeId] itself).
 *
 * On a path, these are exactly the nodes that appear before [nodeId] in topological order.
 * For a DAG, this is the full ancestor sub-graph of [nodeId] — the set of nodes whose
 * outputs influence the computation at [nodeId].
 */
private fun List<DraftNode>.ancestorsOf(nodeId: NodeId): List<DraftNode> {
    val visited = mutableSetOf<NodeId>()
    val queue = ArrayDeque<NodeId>()
    queue.addAll(nodeById(nodeId)?.predecessors ?: emptySet())
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (visited.add(current)) {
            queue.addAll(nodeById(current)?.predecessors ?: emptySet())
        }
    }
    return filter { it.id in visited }
}

/**
 * Estimates the number of elements that survive all filter stages in [ancestorNodes].
 *
 * For each filter op id, if [stats] has a non-zero observed cardinality, its selectivity
 * relative to [sourceCardinality] is multiplied into the running volume. Zero-cardinality
 * filters (not yet observed in [stats]) contribute no reduction — a conservative choice
 * that avoids under-estimating the coordinated volume.
 */
private fun volumeAfterFilters(
    ancestorNodes: List<DraftNode>,
    sourceCardinality: Long,
    stats: WarpStats,
): Long {
    if (sourceCardinality == 0L) return 0L
    val filterIds = ancestorNodes.filterOpIds()
    if (filterIds.isEmpty()) return sourceCardinality
    val volume = filterIds.fold(sourceCardinality.toDouble()) { remaining, opId ->
        val observed = stats.estimatedCardinality(opId)
        if (observed > 0L) remaining * (observed.toDouble() / sourceCardinality)
        else remaining
    }
    return volume.toLong()
}

/**
 * Collects all filter [OpId]s from [CoordinationKind.Free] filter-kind nodes,
 * flattening [DraftStage.FusedFilter] into their constituent ids.
 * Source and Embroider stages are excluded.
 */
private fun List<DraftNode>.filterOpIds(): List<OpId> =
    filter { it.stage.coordinationKind == CoordinationKind.Free && it.stage !is DraftStage.Source }
        .flatMap { node ->
            when (val s = node.stage) {
                is DraftStage.Filter -> listOf(s.opId)
                is DraftStage.FusedFilter -> s.opIds
                else -> emptyList()
            }
        }
