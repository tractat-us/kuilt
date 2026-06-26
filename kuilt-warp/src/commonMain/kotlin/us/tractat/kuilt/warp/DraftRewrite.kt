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
 * ## The three rules
 *
 * - [deferEmbroidery] — floats the single [DraftStage.Embroider] to the end of the pipeline
 *   so coordination happens on the smallest, most-filtered dataset possible.
 * - [pushdownFilters] — moves [DraftStage.Filter] stages ahead of [DraftStage.Map] stages so
 *   less data flows through the (potentially costlier) map operations. Safe by CALM: filter and
 *   map are both monotone and commute.
 * - [fuseAdjacent] — collapses runs of adjacent same-kind monotone stages into a single
 *   [DraftStage.FusedMap] or [DraftStage.FusedFilter]. The E-5 runtime can apply a fused stage
 *   in one pass; the E-3 cost model sees fewer stage boundaries.
 *
 * ## Applying all three
 *
 * [optimize] composes the three rules and drives them to a fixpoint.
 *
 * ## Structural equivalence
 *
 * [isEquivalentTo] provides a semantic-equivalence predicate that verifies two [Draft]s would
 * compute the same convergent result under CALM: same source, same embroider (or both absent),
 * and the same multiset of free-stage operation names (flattened through any fused stages).
 * This is the proof vehicle used in tests; execution-based proofs arrive in E-5.
 */

// ── Rewrite 1: defer-embroidery ───────────────────────────────────────────────

/**
 * Returns a [Draft] where the single [DraftStage.Embroider] stage (if any) is positioned
 * last — after all [CoordinationKind.Free] stages.
 *
 * The rewrite is a no-op if the embroider is already last or if there is no embroider.
 *
 * **Why it's safe:** the [DraftStage.Embroider] is the only [CoordinationKind.Coordinated]
 * stage. Pushing it past a Free stage defers coordination without changing the convergent
 * result — coordination applies to a smaller (more filtered, more mapped) input, which is
 * strictly preferable from the E-3 cost model's perspective.
 */
public fun <T> Draft<T>.deferEmbroidery(): Draft<T> {
    val embroider = stages.filterIsInstance<DraftStage.Embroider>().singleOrNull()
        ?: return this
    val embroiderIndex = stages.indexOf(embroider)
    val stagesAfterEmbroider = stages.drop(embroiderIndex + 1)
    if (stagesAfterEmbroider.isEmpty()) return this
    val stagesBeforeEmbroider = stages.take(embroiderIndex)
    return Draft(stagesBeforeEmbroider + stagesAfterEmbroider + embroider)
}

// ── Rewrite 2: pushdown-filters ───────────────────────────────────────────────

/**
 * Returns a [Draft] where all [DraftStage.Filter] (and [DraftStage.FusedFilter]) stages are
 * moved to precede all [DraftStage.Map] (and [DraftStage.FusedMap]) stages in the middle
 * section — after the [DraftStage.Source] and before the [DraftStage.Embroider] (if any).
 *
 * The rewrite is a no-op if filters already precede all maps.
 *
 * **Why it's safe:** filter and map are both [CoordinationKind.Free] — they are monotone
 * functions. Per the CALM theorem, adjacent monotone stages commute: `filter(map(x))` and
 * `map(filter(x))` produce the same convergent result. Moving filters earlier reduces the
 * volume of data flowing into map stages, which is the pushdown-predicate optimisation
 * familiar from relational query planning — here it's free, not a correctness gamble.
 *
 * **Modelled assumption — filter independence:** this rewrite moves a filter before a map
 * without checking whether the filter's predicate depends on the map's output. No
 * op-dependency metadata exists in the current model: stages carry only symbolic [OpId]s.
 * The contract is that a [DraftStage.Filter]'s predicate operates on the *source* element,
 * not on the map's derived value. A future metadata layer could relax this by attaching
 * input/output type annotations and verifying independence before reordering.
 */
public fun <T> Draft<T>.pushdownFilters(): Draft<T> {
    val source = stages.firstOrNull() as? DraftStage.Source ?: return this
    val embroider = stages.filterIsInstance<DraftStage.Embroider>().singleOrNull()
    val middle = stages.drop(1).filter { it !is DraftStage.Embroider }

    val filters = middle.filter { it.isFilterKind() }
    val maps = middle.filter { it.isMapKind() }

    val reordered = buildList {
        add(source)
        addAll(filters)
        addAll(maps)
        if (embroider != null) add(embroider)
    }
    return if (reordered == stages) this else Draft(reordered)
}

// ── Rewrite 3: fuse-adjacent ──────────────────────────────────────────────────

/**
 * Returns a [Draft] where runs of adjacent same-kind [CoordinationKind.Free] stages are
 * collapsed into a single [DraftStage.FusedMap] or [DraftStage.FusedFilter].
 *
 * [DraftStage.Source] and [DraftStage.Embroider] are never fused.
 * A [DraftStage.FusedMap] and a [DraftStage.Map] (or two [DraftStage.FusedMap]s) that
 * are adjacent are merged into one [DraftStage.FusedMap] carrying all their [OpId]s in order.
 * Likewise for [DraftStage.FusedFilter].
 *
 * **Why it's safe:** monotone stages commute (CALM). Fusing them preserves the combined
 * function: the E-5 runtime applies fused stages in their original order, just in one pass
 * rather than N passes. The opId multiset is unchanged.
 */
public fun <T> Draft<T>.fuseAdjacent(): Draft<T> {
    val fused = mutableListOf<DraftStage>()
    for (stage in stages) {
        val last = fused.lastOrNull()
        when {
            last != null && last.isMapKind() && stage.isMapKind() ->
                fused[fused.size - 1] = DraftStage.FusedMap(last.allOpIds() + stage.allOpIds())
            last != null && last.isFilterKind() && stage.isFilterKind() ->
                fused[fused.size - 1] = DraftStage.FusedFilter(last.allOpIds() + stage.allOpIds())
            else -> fused.add(stage)
        }
    }
    return if (fused == stages) this else Draft(fused)
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
 * - They share the same [DraftStage.Embroider] opId, or neither has one.
 * - The **multiset** of free-stage operation names is identical after flattening fused stages —
 *   so reordering monotone stages or fusing adjacent ones never breaks equivalence.
 *
 * This predicate is the proof vehicle for E-2. Execution-based proof arrives with E-5.
 */
public fun <T> Draft<T>.isEquivalentTo(other: Draft<T>): Boolean {
    val thisSource = stages.filterIsInstance<DraftStage.Source>().singleOrNull()?.opId
    val otherSource = other.stages.filterIsInstance<DraftStage.Source>().singleOrNull()?.opId
    if (thisSource != otherSource) return false

    if (embroidery?.opId != other.embroidery?.opId) return false

    val thisFreeOpIds = freeNonSourceOpIds().sorted()
    val otherFreeOpIds = other.freeNonSourceOpIds().sorted()
    return thisFreeOpIds == otherFreeOpIds
}

// ── Private helpers ───────────────────────────────────────────────────────────

/** Returns all [OpId]s contributed by this stage, flattening fused stages. */
private fun DraftStage.allOpIds(): List<OpId> = when (this) {
    is DraftStage.FusedMap -> opIds
    is DraftStage.FusedFilter -> opIds
    else -> listOf(opId)
}

/** `true` when this stage is a [Map]-kind (unfused or fused). */
private fun DraftStage.isMapKind(): Boolean =
    this is DraftStage.Map || this is DraftStage.FusedMap

/** `true` when this stage is a [Filter]-kind (unfused or fused). */
private fun DraftStage.isFilterKind(): Boolean =
    this is DraftStage.Filter || this is DraftStage.FusedFilter

/** Collects the sorted list of OpId strings for ordering in [isEquivalentTo]. */
private fun List<OpId>.sorted(): List<String> = map { it.value }.sorted()

/**
 * All [OpId]s from [CoordinationKind.Free] non-source stages, flattening fused stages.
 * Used by [isEquivalentTo] to form the multiset of free operations.
 */
private fun <T> Draft<T>.freeNonSourceOpIds(): List<OpId> =
    stages
        .filter { it.coordinationKind == CoordinationKind.Free && it !is DraftStage.Source }
        .flatMap { it.allOpIds() }
