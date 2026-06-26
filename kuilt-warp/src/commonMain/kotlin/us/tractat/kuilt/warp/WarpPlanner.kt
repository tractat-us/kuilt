package us.tractat.kuilt.warp

/**
 * G4 coordination-cost model and planner for [Draft] pipelines.
 *
 * ## Cost model
 *
 * [coordinationCost] scores a [Draft] by the [CoordinationCost] a monotonicity-aware executor
 * would pay:
 * - [CoordinationCost.rounds] = count of coordinated nodes (each [DraftStage.Embroider] or
 *   [DraftStage.BatchedEmbroider] is 1 round — a batched proposal uses one Raft round-trip
 *   regardless of how many agreements it carries).
 * - [CoordinationCost.coupling] = maximum batch size across all coordinated nodes (1 for a
 *   lone [DraftStage.Embroider], `opIds.size` for a [DraftStage.BatchedEmbroider], 0 when
 *   fully monotone). This is the blast-radius term: larger batches couple more failure domains.
 * - [CoordinationCost.coordinatedVolume] = total estimated elements entering all coordinated
 *   stages, summed per coordinated node via ancestor predecessor walks and [WarpStats]
 *   HyperLogLog selectivities.
 *
 * ## Graph-local volume computation
 *
 * For each coordinated node, the volume estimate walks its predecessor graph to collect all
 * filter stages between the source and the coordinated step. Only ancestors reachable from the
 * coordinated node contribute. This generalises to multi-branch DAGs: filters on one branch
 * only reduce the volume on that branch's path to its embroider.
 *
 * ## Planner
 *
 * [plan] applies the three E-2 rewrite rules — [deferEmbroidery], [pushdownFilters],
 * [fuseAdjacent] — to a fixpoint (via [optimize]), then [consolidateEmbroideries].
 * Consolidation fuses independent [DraftStage.Embroider] nodes at the same dependency level
 * into a single [DraftStage.BatchedEmbroider], reducing the round count from K (one per node)
 * to the coordination DAG depth (one per level).
 *
 * **Active lever (G4):** on a multi-coordination [Draft], `coordinationCost(plan(draft)).rounds
 * < coordinationCost(draft).rounds` whenever independent agreements exist at the same DAG level.
 * This is the round-count cut that E-3 could not show (E-3 was structurally pinned at ≤1 rounds).
 *
 * @see CoordinationCost
 * @see Draft.coordinationCost
 * @see Draft.plan
 */

// ── Cost function ─────────────────────────────────────────────────────────────

/**
 * Scores this [Draft] by the [CoordinationCost] a monotonicity-aware executor would pay.
 *
 * - [CoordinationCost.rounds] = count of coordinated nodes (0 for fully-monotone drafts;
 *   each [DraftStage.Embroider] or [DraftStage.BatchedEmbroider] contributes 1).
 * - [CoordinationCost.coupling] = maximum batch size across all coordinated nodes (0 when
 *   fully monotone, 1 for a lone [DraftStage.Embroider], `opIds.size` for a batched node).
 * - [CoordinationCost.coordinatedVolume] = sum of volume estimates across all coordinated
 *   nodes. Each estimate is derived by walking the predecessor graph and applying per-filter
 *   selectivities from [stats]. Unknown filters (zero cardinality in [stats]) contribute no
 *   reduction — a conservative choice.
 *
 * Call [plan] first to minimise this cost before scoring.
 *
 * @sample us.tractat.kuilt.warp.sampleCoordinationCost
 * @see plan
 * @see CoordinationCost
 */
public fun <T> Draft<T>.coordinationCost(stats: WarpStats): CoordinationCost {
    val coordinatedNodes = nodes.filter { it.stage.coordinationKind == CoordinationKind.Coordinated }
    if (coordinatedNodes.isEmpty()) return CoordinationCost(rounds = 0, coupling = 0, coordinatedVolume = 0L)

    val rounds = coordinatedNodes.size
    val coupling = coordinatedNodes.maxOf { it.stage.batchSize() }
    val coordinatedVolume = coordinatedNodes.sumOf { node -> nodes.volumeForCoordinatedNode(node.id, stats) }

    return CoordinationCost(rounds = rounds, coupling = coupling, coordinatedVolume = coordinatedVolume)
}

// ── Planner ───────────────────────────────────────────────────────────────────

/**
 * Returns a [Draft] that minimises [coordinationCost] by applying the E-2 rewrite rules
 * to a fixpoint and then consolidating independent embroideries.
 *
 * **Round-count reduction (G4):** [consolidateEmbroideries] (included in [optimize]) fuses
 * independent [DraftStage.Embroider] nodes at the same dependency level into a single
 * [DraftStage.BatchedEmbroider]. On a multi-coordination [Draft], this drives
 * `coordinationCost(plan(draft)).rounds` to the coordination DAG depth — a measurable cut
 * vs. the unplanned `rounds = K` (one per Embroider node).
 *
 * The returned [Draft] is always semantically equivalent to the receiver under
 * [isEquivalentTo] — the rewrite never changes the convergent result.
 *
 * @param stats reserved for future stats-aware reordering (e.g. ordering filters by
 *   ascending selectivity). Currently unused — all rewrites are structurally determined by
 *   [CoordinationKind] tags. Use [coordinationCost] to measure the plan's quality after
 *   calling [plan].
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
 * Computes the coordinated volume for a single coordinated node identified by [nodeId].
 *
 * Walks the predecessor graph to find the nearest ancestor [DraftStage.Source] and all
 * [DraftStage.Filter] stages between them. Applies per-filter selectivities from [stats]
 * conservatively (unknown filters contribute no volume reduction).
 *
 * For multi-source branches (e.g. a [DraftStage.BatchedEmbroider] whose ancestors span
 * several independent branches), the first source found in topological order is used as
 * the baseline cardinality. This is an approximation; G5 can refine per-branch accounting.
 */
private fun List<DraftNode>.volumeForCoordinatedNode(nodeId: NodeId, stats: WarpStats): Long {
    val ancestorNodes = ancestorsOf(nodeId)
    val sourceNode = ancestorNodes.firstOrNull { it.stage is DraftStage.Source } ?: return 0L
    val sourceCardinality = stats.estimatedCardinality((sourceNode.stage as DraftStage.Source).opId)
    return volumeAfterFilters(ancestorNodes, sourceCardinality, stats)
}

/**
 * Returns the number of coordination proposals this stage represents.
 *
 * A [DraftStage.BatchedEmbroider] batches several agreements into one Raft round-trip, so
 * its batch size equals [DraftStage.BatchedEmbroider.opIds].size. All other coordinated stages
 * (only [DraftStage.Embroider] in practice) contribute a batch size of 1.
 *
 * Used to compute [CoordinationCost.coupling].
 */
private fun DraftStage.batchSize(): Int = when (this) {
    is DraftStage.BatchedEmbroider -> opIds.size
    else -> 1
}

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
