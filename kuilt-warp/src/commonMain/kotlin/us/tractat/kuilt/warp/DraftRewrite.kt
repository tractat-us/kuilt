package us.tractat.kuilt.warp

/**
 * Pure rewrite rules for [Draft] pipelines — each transforms a [Draft] without changing
 * what the pipeline would compute.
 *
 * ## Safety: CALM commutativity
 *
 * Every rewrite in this file exploits the **CALM theorem**: a monotone function (one that
 * preserves the lattice ordering) commutes with any other monotone function applied to the
 * same element. Because every [DraftStage] with [CoordinationKind.Free] is monotone by
 * contract, adjacent Free stages can be freely reordered or fused. The single
 * [CoordinationKind.Coordinated] stage ([DraftStage.Embroider]) cannot commute past Free
 * stages without potentially changing the result, so the rewrites only move it *later*, never
 * earlier.
 *
 * ## Graph-local logic
 *
 * All three rewrites operate on the [Draft.nodes] DAG structure — checking
 * [DraftNode.predecessors] and successor maps rather than list indices. On a path
 * (the degenerate DAG produced by the path-preserving builder), the results are
 * identical to the previous list-based logic; the graph-local formulation generalises
 * to the multi-branch DAGs that G2 [Draft.combine] will introduce.
 *
 * ## The three rules
 *
 * - [deferEmbroidery] — floats each [DraftStage.Embroider] past its Free successors so
 *   coordination happens on the smallest, most-filtered dataset possible.
 * - [pushdownFilters] — moves [DraftStage.Filter] stages ahead of [DraftStage.Map] stages
 *   (within the Free section between source and embroider), reducing the volume flowing into
 *   costlier map operations.
 * - [fuseAdjacent] — collapses adjacent same-kind monotone nodes in a linear chain
 *   (one predecessor / one successor of the same kind) into a single [DraftStage.FusedMap]
 *   or [DraftStage.FusedFilter].
 *
 * ## Applying all three
 *
 * [optimize] composes the three rules and drives them to a fixpoint.
 *
 * ## Structural equivalence
 *
 * [isEquivalentTo] provides a semantic-equivalence predicate that verifies two [Draft]s
 * would compute the same convergent result under CALM: same source, same embroider set
 * (or both absent), and the same multiset of free-stage operation names (flattened
 * through any fused stages). This is the proof vehicle used in tests.
 */

// ── Rewrite 1: defer-embroidery ───────────────────────────────────────────────

/**
 * Returns a [Draft] where each [DraftStage.Embroider] node is positioned after all
 * of its [CoordinationKind.Free] successor nodes.
 *
 * **Graph-local criterion:** an Embroider node is deferred when the successor map shows
 * it has at least one Free direct successor. On a path, this is equivalent to the
 * Embroider not being last in topological order. The rewrite is a no-op if the
 * Embroider is already last or if there is no Embroider.
 *
 * **Why it's safe:** the [DraftStage.Embroider] is the only [CoordinationKind.Coordinated]
 * stage. Pushing it past a Free successor defers coordination without changing the
 * convergent result — coordination applies to a smaller (more filtered, more mapped)
 * input, which is strictly preferable from the E-3 cost model's perspective.
 */
public fun <T> Draft<T>.deferEmbroidery(): Draft<T> {
    val successorMap = nodes.successors()
    val embroiderNode = nodes.singleOrNull { it.stage is DraftStage.Embroider } ?: return this
    val hasFreeSuccessor = successorMap[embroiderNode.id].orEmpty()
        .any { id -> nodes.nodeById(id)?.stage?.coordinationKind == CoordinationKind.Free }
    if (!hasFreeSuccessor) return this

    // Rebuild stage list with Embroider relocated to end (past all Free successors).
    val embroiderIdx = nodes.indexOfFirst { it.stage is DraftStage.Embroider }
    val stagesBefore = nodes.take(embroiderIdx).map { it.stage }
    val stagesAfter = nodes.drop(embroiderIdx + 1).map { it.stage }
    return Draft((stagesBefore + stagesAfter + embroiderNode.stage).toPathNodes())
}

// ── Rewrite 2: pushdown-filters ───────────────────────────────────────────────

/**
 * Returns a [Draft] where all [DraftStage.Filter] (and [DraftStage.FusedFilter]) nodes
 * in the Free section precede all [DraftStage.Map] (and [DraftStage.FusedMap]) nodes.
 *
 * **Graph-local criterion:** the Free section is the sub-path between the Source node
 * and the Embroider node (both excluded). Within this section, a filter node whose
 * predecessor is a map node can be pushed earlier (predecessor-local swap). On a path,
 * pushing all filters before all maps in one pass is equivalent to repeatedly applying
 * the predecessor-local swap.
 *
 * **Why it's safe:** filter and map are both [CoordinationKind.Free] — they are monotone
 * functions. Per the CALM theorem, adjacent monotone stages commute: `filter(map(x))` and
 * `map(filter(x))` produce the same convergent result. Moving filters earlier reduces the
 * volume of data flowing into map stages.
 *
 * **Modelled assumption — filter independence:** this rewrite moves a filter before a map
 * without checking whether the filter's predicate depends on the map's output. Stages carry
 * only symbolic [OpId]s. The contract is that a [DraftStage.Filter]'s predicate operates
 * on the *source* element. A future metadata layer could verify independence before reordering.
 */
public fun <T> Draft<T>.pushdownFilters(): Draft<T> {
    val sourceNode = nodes.firstOrNull { it.stage is DraftStage.Source } ?: return this
    val embroiderNode = nodes.singleOrNull { it.stage is DraftStage.Embroider }

    // Free section: nodes that are neither the source nor the embroider
    val freeNodes = nodes
        .filter { it.id != sourceNode.id && (embroiderNode == null || it.id != embroiderNode.id) }

    val filters = freeNodes.filter { it.stage.isFilterKind() }.map { it.stage }
    val maps = freeNodes.filter { it.stage.isMapKind() }.map { it.stage }

    val reorderedStages = buildList {
        add(sourceNode.stage)
        addAll(filters)
        addAll(maps)
        if (embroiderNode != null) add(embroiderNode.stage)
    }
    return if (reorderedStages == stages) this else Draft(reorderedStages.toPathNodes())
}

// ── Rewrite 3: fuse-adjacent ──────────────────────────────────────────────────

/**
 * Returns a [Draft] where adjacent same-kind [CoordinationKind.Free] nodes in a linear
 * chain are collapsed into a single [DraftStage.FusedMap] or [DraftStage.FusedFilter].
 *
 * **Graph-local criterion:** two nodes A and B can be fused when:
 * - B's sole predecessor is A (`B.predecessors == {A.id}`).
 * - A has only one successor in the graph (so we are in a linear chain, not a branch point).
 * - A and B are the same kind (both map-kind or both filter-kind).
 *
 * [DraftStage.Source] and [DraftStage.Embroider] are never fused.
 *
 * **Why it's safe:** monotone stages commute (CALM). Fusing them preserves the combined
 * function: the E-5 runtime applies fused stages in their original order, just in one pass
 * rather than N passes. The opId multiset is unchanged.
 */
public fun <T> Draft<T>.fuseAdjacent(): Draft<T> {
    val successorMap = nodes.successors()
    val fusedStages = mutableListOf<DraftStage>()
    // Maps each original NodeId to its index in fusedStages after any fusion collapse
    val idToFusedIdx = mutableMapOf<NodeId, Int>()

    for (node in nodes) {
        val solePredId = node.predecessors.singleOrNull()
        val predFusedIdx = solePredId?.let { idToFusedIdx[it] }
        val predStage = predFusedIdx?.let { fusedStages[it] }

        // Fuse only in a strict linear chain: predecessor must have exactly one successor.
        val predIsLinear = solePredId != null && successorMap[solePredId].orEmpty().size == 1

        when {
            predStage != null && predIsLinear &&
                predStage.isMapKind() && node.stage.isMapKind() -> {
                fusedStages[predFusedIdx!!] =
                    DraftStage.FusedMap(predStage.allOpIds() + node.stage.allOpIds())
                idToFusedIdx[node.id] = predFusedIdx
            }
            predStage != null && predIsLinear &&
                predStage.isFilterKind() && node.stage.isFilterKind() -> {
                fusedStages[predFusedIdx!!] =
                    DraftStage.FusedFilter(predStage.allOpIds() + node.stage.allOpIds())
                idToFusedIdx[node.id] = predFusedIdx
            }
            else -> {
                idToFusedIdx[node.id] = fusedStages.size
                fusedStages.add(node.stage)
            }
        }
    }

    return if (fusedStages == stages) this else Draft(fusedStages.toPathNodes())
}

// ── Compose: optimize ─────────────────────────────────────────────────────────

/**
 * Applies all three rewrite rules — [deferEmbroidery], [pushdownFilters], [fuseAdjacent] —
 * in sequence and repeats until no rule changes the pipeline (fixpoint).
 *
 * The standard pipeline converges in one pass: defer first (embroider goes last), then push
 * filters down (free of embroider anchor), then fuse runs of the same kind. A second pass is
 * only needed when a reorder in one rule creates an adjacency that another rule can exploit.
 *
 * The returned [Draft] is structurally equivalent to the receiver under [isEquivalentTo].
 *
 * @sample us.tractat.kuilt.warp.sampleOptimize
 */
public fun <T> Draft<T>.optimize(): Draft<T> {
    var current: Draft<T> = this
    while (true) {
        val next = current
            .deferEmbroidery()
            .pushdownFilters()
            .fuseAdjacent()
        if (next.stages == current.stages) return current
        current = next
    }
}

// ── Structural equivalence predicate ─────────────────────────────────────────

/**
 * Returns `true` when this [Draft] is structurally equivalent to [other] under the CALM
 * commutativity theorem.
 *
 * Two drafts are **equivalent** when they would produce the same convergent result:
 * - They share the same [DraftStage.Source] opId.
 * - They share the same multiset of [DraftStage.Embroider] opIds (order-insensitive;
 *   G2+ drafts may have multiple independent embroiders).
 * - The **multiset** of free-stage operation names is identical after flattening fused
 *   stages — so reordering monotone stages or fusing adjacent ones never breaks equivalence.
 *
 * This predicate is the proof vehicle for E-2. Execution-based proof arrives with E-5.
 */
public fun <T> Draft<T>.isEquivalentTo(other: Draft<T>): Boolean {
    val thisSource = nodes.sourceOpId()
    val otherSource = other.nodes.sourceOpId()
    if (thisSource != otherSource) return false

    val thisEmbroideries = embroideries.map { it.opId.value }.sorted()
    val otherEmbroideries = other.embroideries.map { it.opId.value }.sorted()
    if (thisEmbroideries != otherEmbroideries) return false

    return freeNonSourceOpIds().sorted() == other.freeNonSourceOpIds().sorted()
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/** Returns all [OpId]s contributed by this stage, flattening fused stages. */
internal fun DraftStage.allOpIds(): List<OpId> = when (this) {
    is DraftStage.FusedMap -> opIds
    is DraftStage.FusedFilter -> opIds
    else -> listOf(opId)
}

/** `true` when this stage is a [Map]-kind (unfused or fused). */
internal fun DraftStage.isMapKind(): Boolean =
    this is DraftStage.Map || this is DraftStage.FusedMap

/** `true` when this stage is a [Filter]-kind (unfused or fused). */
internal fun DraftStage.isFilterKind(): Boolean =
    this is DraftStage.Filter || this is DraftStage.FusedFilter

/** Finds a [DraftNode] by its [NodeId], or null if not present. */
internal fun List<DraftNode>.nodeById(id: NodeId): DraftNode? =
    firstOrNull { it.id == id }

/** The [OpId] of the [DraftStage.Source] node, or null if none exists. */
private fun List<DraftNode>.sourceOpId(): OpId? =
    firstOrNull { it.stage is DraftStage.Source }?.stage?.opId

/**
 * All [OpId]s from [CoordinationKind.Free] non-source nodes, flattening fused stages.
 * Used by [isEquivalentTo] to form the multiset of free operations.
 */
private fun <T> Draft<T>.freeNonSourceOpIds(): List<String> =
    nodes
        .filter { it.stage.coordinationKind == CoordinationKind.Free && it.stage !is DraftStage.Source }
        .flatMap { it.stage.allOpIds() }
        .map { it.value }
