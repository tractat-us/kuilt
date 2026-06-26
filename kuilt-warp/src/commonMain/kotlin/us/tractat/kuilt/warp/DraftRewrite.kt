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
 * to the multi-branch DAGs that G2 [Draft.combine] introduces.
 *
 * ## Branch-preserving via per-component application
 *
 * [deferEmbroidery] and [pushdownFilters] apply **per weakly-connected component**: each
 * independent branch (produced by [Draft.combine]) is extracted, rewritten in isolation,
 * then reassembled. This guarantees that a rewrite never collapses two independent branches
 * into a single path or introduces cross-branch predecessor edges.
 *
 * [fuseAdjacent] rebuilds [DraftNode]s directly (tracking predecessor remapping through
 * fusion collapses) rather than rebuilding via [List.toPathNodes]. This preserves the full
 * graph structure — including branch-point nodes whose predecessor count exceeds one — and
 * only fuses nodes that are in a strict linear chain.
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
 * would compute the same convergent result under CALM: same multiset of sources, same
 * embroider set (or both absent), and the same multiset of free-stage operation names
 * (flattened through any fused stages). This is the proof vehicle used in tests.
 */

// ── Rewrite 1: defer-embroidery ───────────────────────────────────────────────

/**
 * Returns a [Draft] where each [DraftStage.Embroider] node is positioned after all
 * of its [CoordinationKind.Free] successor nodes.
 *
 * **Branch-aware:** applied per weakly-connected component. Each independent branch from
 * a G2 [Draft.combine] is deferred independently; the two branches are never merged.
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
public fun <T> Draft<T>.deferEmbroidery(): Draft<T> =
    applyPerComponent { it.deferEmbroideryOnPath() }

private fun <T> Draft<T>.deferEmbroideryOnPath(): Draft<T> {
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
 * **Branch-aware:** applied per weakly-connected component. Each independent branch from
 * a G2 [Draft.combine] is reordered independently; the two branches are never merged.
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
public fun <T> Draft<T>.pushdownFilters(): Draft<T> =
    applyPerComponent { it.pushdownFiltersOnPath() }

private fun <T> Draft<T>.pushdownFiltersOnPath(): Draft<T> {
    val sourceNode = nodes.firstOrNull { it.stage is DraftStage.Source } ?: return this
    // Filter-pushdown applies only to a single-embroider path (one embroider, or none for a
    // fully-monotone path). A component with multiple embroiders (a sequential chain) cannot
    // be safely reordered here — return it unchanged; consolidateEmbroideries handles chains.
    val embroiderNodes = nodes.filter { it.stage is DraftStage.Embroider }
    if (embroiderNodes.size > 1) return this
    val embroiderNode = embroiderNodes.firstOrNull()

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
 * **Branch-preserving:** the algorithm rebuilds [DraftNode]s directly, tracking
 * predecessor remapping through fusion collapses. The graph structure — including
 * independent branches from [Draft.combine] — is fully preserved. [List.toPathNodes]
 * is never called on the combined result, avoiding the linearisation that would
 * otherwise destroy multi-branch structure.
 *
 * **Why it's safe:** monotone stages commute (CALM). Fusing them preserves the combined
 * function: the E-5 runtime applies fused stages in their original order, just in one pass
 * rather than N passes. The opId multiset is unchanged.
 */
public fun <T> Draft<T>.fuseAdjacent(): Draft<T> {
    val successorMap = nodes.successors()
    // Maps original NodeId -> new NodeId in the rebuilt graph.
    val oldToNew = mutableMapOf<NodeId, NodeId>()
    // Maps new NodeId -> index in newNodes for O(1) lookup.
    val newIdToIdx = mutableMapOf<NodeId, Int>()
    val newNodes = mutableListOf<DraftNode>()

    for (node in nodes) {
        val solePredOldId = node.predecessors.singleOrNull()
        val predIsLinear = solePredOldId != null &&
            successorMap[solePredOldId].orEmpty().size == 1
        val predNewId = solePredOldId?.let { oldToNew[it] }
        val predNewIdx = predNewId?.let { newIdToIdx[it] }
        val predNewNode = predNewIdx?.let { newNodes[it] }

        when {
            predNewNode != null && predIsLinear &&
                predNewNode.stage.isMapKind() && node.stage.isMapKind() -> {
                // Fuse: extend the predecessor's FusedMap with this node's op ids.
                val fusedStage = DraftStage.FusedMap(
                    predNewNode.stage.allOpIds() + node.stage.allOpIds(),
                )
                // Smart-cast: predNewNode != null → predNewIdx != null → predNewId != null
                newNodes[predNewIdx] = predNewNode.copy(stage = fusedStage)
                // This node's id maps to the same new id as its predecessor (consumed into it).
                oldToNew[node.id] = predNewId
            }
            predNewNode != null && predIsLinear &&
                predNewNode.stage.isFilterKind() && node.stage.isFilterKind() -> {
                val fusedStage = DraftStage.FusedFilter(
                    predNewNode.stage.allOpIds() + node.stage.allOpIds(),
                )
                newNodes[predNewIdx] = predNewNode.copy(stage = fusedStage)
                oldToNew[node.id] = predNewId
            }
            else -> {
                // No fusion: create a new node with remapped predecessors.
                val newPreds = node.predecessors.mapNotNull { oldToNew[it] }.toSet()
                val newId = NodeId(newNodes.size)
                oldToNew[node.id] = newId
                newIdToIdx[newId] = newNodes.size
                newNodes.add(DraftNode(id = newId, stage = node.stage, predecessors = newPreds))
            }
        }
    }

    return if (newNodes.map { it.stage } == stages) this else Draft(newNodes)
}

// ── Rewrite 4: consolidate-embroideries ──────────────────────────────────────

/**
 * Returns a [Draft] where independent [DraftStage.Embroider] nodes at the same dependency
 * level are fused into a single [DraftStage.BatchedEmbroider].
 *
 * **Theory:** the minimum number of coordination rounds equals the depth of the coordination
 * dependency DAG, not the count of individual embroideries. Two embroidery nodes are
 * *independent* when neither is a transitive ancestor of the other — they can be committed
 * in a single consensus round (one `BatchedEmbroider`) rather than two sequential rounds.
 *
 * **Dependency level:** computed bottom-up in topological order. A coordinated node has
 * level `0` when it has no coordinated ancestors; otherwise level
 * `1 + max(levels of its coordinated ancestors)`. Two nodes at the same level are guaranteed
 * independent (no mutual ancestor path), so fusion is safe.
 *
 * **Graph contraction:** for each level with two or more coordinated nodes, the fused set is
 * replaced by a single new [DraftStage.BatchedEmbroider] node whose predecessors are the
 * union of the fused nodes' predecessors outside the fused set. Successors of the fused
 * nodes are rewired to the new node. All non-coordinated structure and all edges are
 * preserved.
 *
 * **Idempotent:** a second call produces the same result as the first — a lone
 * [DraftStage.BatchedEmbroider] at some level is the only coordinated node at that level
 * and is not fused further.
 *
 * **Result-preserving:** the returned [Draft] is structurally equivalent to the receiver
 * under [isEquivalentTo] (same sources, same multiset of embroider opIds, same free-op
 * multiset).
 */
public fun <T> Draft<T>.consolidateEmbroideries(): Draft<T> {
    val levels = coordinationLevels()
    val byLevel = levels.entries
        .groupBy({ it.value }, { it.key })
        .filterValues { it.size >= 2 }

    if (byLevel.isEmpty()) return this

    var current = nodes
    var nextId = nodes.maxOf { it.id.value } + 1

    for (level in byLevel.keys.sorted()) {
        val fusedIds = byLevel.getValue(level).toSet()
        val opIds = current.filter { it.id in fusedIds }.flatMap { it.stage.coordinatedOpIds() }
        val newId = NodeId(nextId++)
        val mergedPreds = fusedIds
            .flatMap { id -> current.first { it.id == id }.predecessors }
            .filter { it !in fusedIds }
            .toSet()
        val batchedNode = DraftNode(
            id = newId,
            stage = DraftStage.BatchedEmbroider(opIds),
            predecessors = mergedPreds,
        )
        current = contractFusedLevel(current, fusedIds, newId, batchedNode)
    }

    return Draft(current)
}

/**
 * Contracts [fusedIds] into [newId]/[batchedNode] in the node list.
 *
 * Replaces the last fused node (in topological order) with [batchedNode], removes the
 * remaining fused nodes, and rewires every non-fused node whose predecessor set contained
 * a fused id to point at [newId] instead.
 */
private fun contractFusedLevel(
    nodes: List<DraftNode>,
    fusedIds: Set<NodeId>,
    newId: NodeId,
    batchedNode: DraftNode,
): List<DraftNode> {
    val result = mutableListOf<DraftNode>()
    for (i in nodes.indices) {
        val node = nodes[i]
        when {
            node.id in fusedIds -> {
                val isLastFused = nodes.drop(i + 1).none { it.id in fusedIds }
                if (isLastFused) result.add(batchedNode)
            }
            else -> result.add(rewirePredecessors(node, fusedIds, newId))
        }
    }
    return result
}

/** Replaces any predecessor id in [fusedIds] with [newId]. */
private fun rewirePredecessors(node: DraftNode, fusedIds: Set<NodeId>, newId: NodeId): DraftNode {
    val updated = node.predecessors.map { if (it in fusedIds) newId else it }.toSet()
    return if (updated == node.predecessors) node else node.copy(predecessors = updated)
}

/**
 * Computes the dependency level for every coordinated node in this [Draft].
 *
 * A coordinated node has level `0` when it has no coordinated ancestors; otherwise
 * `1 + max(levels of its coordinated ancestors)`. Processed bottom-up in topological
 * order — each node's predecessors are guaranteed to have been assigned a propagated
 * level before the node itself is visited.
 *
 * Non-coordinated nodes are not included in the result but propagate the highest level
 * seen in their predecessor chain forward.
 */
private fun <T> Draft<T>.coordinationLevels(): Map<NodeId, Int> {
    // propagatedLevel[id] = the highest coordination level visible from node `id`
    // (−1 when no coordinated node has been seen yet on any path to this node).
    val propagatedLevel = mutableMapOf<NodeId, Int>()
    val result = mutableMapOf<NodeId, Int>()

    for (node in nodes) {
        val highestFromPreds = node.predecessors
            .mapNotNull { propagatedLevel[it] }
            .maxOrNull() ?: -1

        if (node.stage.coordinationKind == CoordinationKind.Coordinated) {
            val level = highestFromPreds + 1
            result[node.id] = level
            propagatedLevel[node.id] = level
        } else {
            propagatedLevel[node.id] = highestFromPreds
        }
    }

    return result
}

// ── Compose: optimize ─────────────────────────────────────────────────────────

/**
 * Applies all rewrite rules in sequence, then consolidates independent embroideries.
 *
 * The E-2 fixpoint — [deferEmbroidery], [pushdownFilters], [fuseAdjacent] — runs first
 * until no rule changes the pipeline. [consolidateEmbroideries] is then applied once as
 * a final step: it operates on the fully-deferred, filtered, and fused graph, fusing
 * independent [DraftStage.Embroider] nodes at the same dependency level into a single
 * [DraftStage.BatchedEmbroider].
 *
 * Applying consolidation after the fixpoint (not inside it) is deliberate:
 * - [deferEmbroidery] and [pushdownFilters] do not move [DraftStage.BatchedEmbroider]
 *   nodes, so no new fixpoint iterations arise after consolidation.
 * - [fuseAdjacent] never fuses coordinated stages, so the structure is stable.
 * - Consolidation is idempotent, so calling [optimize] twice is safe.
 *
 * **Branch-aware:** each constituent rule applies per branch (see [deferEmbroidery],
 * [pushdownFilters], [fuseAdjacent]). A combined draft from [Draft.combine] converges to
 * the per-branch optimal form without ever collapsing independent branches.
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
        if (next.stages == current.stages) break
        current = next
    }
    return current.consolidateEmbroideries()
}

// ── Structural equivalence predicate ─────────────────────────────────────────

/**
 * Returns `true` when this [Draft] is structurally equivalent to [other] under the CALM
 * commutativity theorem.
 *
 * Two drafts are **equivalent** when they would produce the same convergent result:
 * - They share the same **multiset** of [DraftStage.Source] opIds (handles multi-branch
 *   DAGs produced by [Draft.combine] — branch order is irrelevant).
 * - They share the same multiset of embroider opIds (order-insensitive; a
 *   [DraftStage.BatchedEmbroider] is treated as the multiset of its constituent opIds —
 *   so a consolidated draft is equivalent to the un-consolidated draft it was derived from).
 * - The **multiset** of free-stage operation names is identical after flattening fused
 *   stages — so reordering monotone stages or fusing adjacent ones never breaks equivalence.
 *
 * This predicate is the proof vehicle for E-2 and G3. Execution-based proof arrives with E-5.
 */
public fun <T> Draft<T>.isEquivalentTo(other: Draft<T>): Boolean {
    // Compare sorted multisets of source opIds (handles single and multi-branch DAGs).
    if (sourceOpIds().sorted() != other.sourceOpIds().sorted()) return false

    // Flatten BatchedEmbroider into its constituent opIds for comparison so that a
    // consolidated draft is equivalent to the pre-consolidation draft.
    if (allEmbroiderOpIds().sorted() != other.allEmbroiderOpIds().sorted()) return false

    return freeNonSourceOpIds().sorted() == other.freeNonSourceOpIds().sorted()
}

// ── Per-component helper ──────────────────────────────────────────────────────

/**
 * Applies [transform] independently to each weakly-connected component of this [Draft]
 * and reassembles the results with globally-unique [NodeId]s.
 *
 * Used by [deferEmbroidery] and [pushdownFilters] to achieve branch-local rewrites: each
 * independent branch from [Draft.combine] is extracted with local 0-based ids, transformed
 * in isolation, and re-offset when the components are joined back.
 *
 * A single-component [Draft] (a path or any other connected graph) is passed directly to
 * [transform] without extraction overhead.
 */
private fun <T> Draft<T>.applyPerComponent(transform: (Draft<T>) -> Draft<T>): Draft<T> {
    val components = nodes.weaklyConnectedComponents()
    if (components.size == 1) return transform(this)

    val transformedComponents = components.map { componentNodes ->
        val idRemap = componentNodes.mapIndexed { i, n -> n.id to NodeId(i) }.toMap()
        val localNodes = componentNodes.mapIndexed { i, n ->
            DraftNode(
                id = NodeId(i),
                stage = n.stage,
                predecessors = n.predecessors.mapNotNull { idRemap[it] }.toSet(),
            )
        }
        @Suppress("UNCHECKED_CAST")
        transform(Draft<Any>(localNodes) as Draft<T>)
    }

    // Reassemble with globally-unique ids by offsetting each component.
    var offset = 0
    val allNodes = mutableListOf<DraftNode>()
    for (result in transformedComponents) {
        for (node in result.nodes) {
            allNodes.add(
                DraftNode(
                    id = NodeId(node.id.value + offset),
                    stage = node.stage,
                    predecessors = node.predecessors.map { NodeId(it.value + offset) }.toSet(),
                )
            )
        }
        offset += result.nodes.size
    }
    return Draft(allNodes)
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

/**
 * All [OpId] values (as strings) of the [DraftStage.Source] nodes in this list.
 * Used by [isEquivalentTo] to compare the multiset of sources across branches.
 */
private fun <T> Draft<T>.sourceOpIds(): List<String> =
    nodes
        .filter { it.stage is DraftStage.Source }
        .map { it.stage.opId.value }

/**
 * All [OpId]s from [CoordinationKind.Free] non-source nodes, flattening fused stages.
 * Used by [isEquivalentTo] to form the multiset of free operations.
 */
private fun <T> Draft<T>.freeNonSourceOpIds(): List<String> =
    nodes
        .filter { it.stage.coordinationKind == CoordinationKind.Free && it.stage !is DraftStage.Source }
        .flatMap { it.stage.allOpIds() }
        .map { it.value }

/**
 * All embroider [OpId]s in this [Draft], flattening [DraftStage.BatchedEmbroider] into
 * its constituent opIds. Used by [isEquivalentTo] to compare the embroider multisets of
 * a pre-consolidation and post-consolidation draft.
 */
private fun <T> Draft<T>.allEmbroiderOpIds(): List<String> =
    nodes.flatMap { it.stage.coordinatedOpIds() }.map { it.value }

/**
 * Returns the embroider [OpId]s contributed by this stage: a single element for
 * [DraftStage.Embroider], the full list for [DraftStage.BatchedEmbroider], and empty for
 * all other stage kinds. Used by [consolidateEmbroideries] to collect opIds for batching
 * and by [isEquivalentTo] to compare the embroider multisets.
 */
internal fun DraftStage.coordinatedOpIds(): List<OpId> = when (this) {
    is DraftStage.Embroider -> listOf(opId)
    is DraftStage.BatchedEmbroider -> opIds
    else -> emptyList()
}

/**
 * Extracts the weakly-connected components of this node list, in the order their first
 * node appears in the original topological ordering.
 *
 * A weakly-connected component groups nodes reachable from each other when edges are
 * treated as undirected. Independent branches introduced by [Draft.combine] appear as
 * separate components.
 *
 * The nodes within each returned component are in their original topological order.
 */
internal fun List<DraftNode>.weaklyConnectedComponents(): List<List<DraftNode>> {
    val successorMap = successors()
    val idToNode = associateBy { it.id }
    val visited = mutableSetOf<NodeId>()
    val components = mutableListOf<List<DraftNode>>()

    for (startNode in this) {
        if (startNode.id in visited) continue

        val componentIds = mutableSetOf<NodeId>()
        val queue = ArrayDeque<NodeId>()
        queue.add(startNode.id)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!componentIds.add(id)) continue
            val node = idToNode[id] ?: continue
            node.predecessors.filterNot { it in componentIds }.forEach { queue.add(it) }
            successorMap[id].orEmpty().filterNot { it in componentIds }.forEach { queue.add(it) }
        }

        visited.addAll(componentIds)
        // Preserve original topological order within each component.
        components.add(filter { it.id in componentIds })
    }
    return components
}
