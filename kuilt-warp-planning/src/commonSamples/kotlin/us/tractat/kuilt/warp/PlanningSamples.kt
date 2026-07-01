package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.piece

/**
 * Samples for the warp CALM planner / cost-model API used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── coordinationCost / plan ───────────────────────────────────────────────────

/**
 * Score and plan a [Draft] pipeline using the E-3 coordination-cost model.
 *
 * An unplanned draft may place the [DraftStage.Embroider] before selective filters,
 * causing the coordinated step to see the full source cardinality. [Draft.plan] defers
 * the embroider past all free stages; [Draft.coordinationCost] measures the improvement.
 */
@Suppress("unused")
internal fun sampleCoordinationCost() {
    val src = OpId("source.docs")
    val mapScore = OpId("map.score")
    val filterThreshold = OpId("filter.above-threshold")
    val embroider = OpId("embroider.rank")

    // Programmer places embroider early (before the filter).
    val unplanned: Draft<ByteArray> = Warp.shuttle(src)
        .map(mapScore)
        .embroider(embroider)
        .filter(filterThreshold)

    // Build stats: 1 000 source docs, 50 pass the filter.
    var stats = WarpStats.empty()
    for (i in 1..1_000) stats = stats.piece(stats.observe(src, "doc_$i"))
    for (i in 1..50) stats = stats.piece(stats.observe(filterThreshold, "doc_${i * 20}"))

    // Unplanned: embroider before filter → full source cardinality.
    val unplannedCost = unplanned.coordinationCost(stats)
    check(unplannedCost.rounds == 1)
    check(unplannedCost.coordinatedVolume >= 900L) { "should see ~1000 docs" }

    // Planned: embroider deferred past filter → only ~50 docs reach consensus.
    val planned = unplanned.plan(stats)
    val plannedCost = planned.coordinationCost(stats)
    check(plannedCost.rounds == 1)
    check(plannedCost.coordinatedVolume < 100L) { "should see only ~50 docs after filter" }
    check(plannedCost < unplannedCost)
    check(unplanned.isEquivalentTo(planned)) { "plan must preserve equivalence" }
}

// ── optimize ──────────────────────────────────────────────────────────────────

/**
 * Apply all three rewrite rules ([deferEmbroidery], [pushdownFilters], [fuseAdjacent])
 * to a fixpoint, and verify structural equivalence is preserved.
 *
 * Note: [pushdownFilters] moves filters before maps assuming the filter predicate
 * does not depend on the map's output (no op-dependency metadata exists yet).
 */
@Suppress("unused")
internal fun sampleOptimize() {
    // A draft with embroider in the middle and filters after maps.
    val unoptimized: Draft<ByteArray> = Warp.shuttle(OpId("source.docs"))
        .map(OpId("map.enrich"))
        .embroider(OpId("embroider.rank"))
        .filter(OpId("filter.threshold"))
        .map(OpId("map.format"))

    val optimized = unoptimized.optimize()

    // Embroider deferred last; filters pushed before maps; adjacent same-kind fused.
    check(optimized.stages.last() is DraftStage.Embroider) { "embroider should be last" }
    // Structural equivalence: same source, embroider, and free-op multiset.
    check(unoptimized.isEquivalentTo(optimized)) { "optimize must preserve equivalence" }
}

// ── consolidateEmbroideries ────────────────────────────────────────────────────

/**
 * Fuse independent [DraftStage.Embroider] nodes at the same dependency level into a
 * single [DraftStage.BatchedEmbroider] — one Raft round-trip instead of two.
 *
 * Two embroideries that share no ancestor path (neither is the other's predecessor,
 * transitively) are at the same dependency level and can be committed together.
 * The result is semantically equivalent ([Draft.isEquivalentTo]).
 *
 * **Coupling tradeoff:** the batch carries both agreements in one Raft proposal.
 * If the proposal is rejected, both must retry. The [CoordinationCost.coupling]
 * term captures this blast-radius; the planner minimises rounds first, then coupling.
 */
@Suppress("unused")
internal fun sampleConsolidateEmbroideries() {
    val combined: Draft<Unit> = Warp.shuttle(OpId("source.docs")).embroider(OpId("embroider.rank"))
        .combine(Warp.shuttle(OpId("source.scores")).embroider(OpId("embroider.vote")))

    val consolidated = combined.consolidateEmbroideries()

    // Two independent embroideries become one BatchedEmbroider — one consensus round.
    check(consolidated.nodes.any { it.stage is DraftStage.BatchedEmbroider })
    check(consolidated.nodes.count { it.stage.coordinationKind == CoordinationKind.Coordinated } == 1)
    // Semantic equivalence: same sources, same embroider multiset, same free-op multiset.
    check(combined.isEquivalentTo(consolidated))
}

// ── CoordinationCost with DAG depth ───────────────────────────────────────────

/**
 * Demonstrate that [Draft.plan] cuts [CoordinationCost.rounds] to the coordination-DAG depth.
 *
 * The representative G4 query: three independent embroideries at level 0, one dependent
 * at level 1. Unplanned: 4 rounds (one per Embroider node). Planned: 2 rounds (the
 * DAG depth — BatchedEmbroider at level 0, Embroider at level 1).
 *
 * [CoordinationCost.rounds] is now an active lever, not pinned at ≤ 1 as in E-3. The
 * [CoordinationCost.coupling] term encodes the honest tradeoff: the level-0 batch
 * commits three agreements in one proposal, so a rejection retries all three.
 */
@Suppress("unused")
internal fun sampleCoordinationCostDepth() {
    // Branch C chains two embroideries: embroider(C) must commit before embroider(D).
    val branchA = Warp.shuttle(OpId("source.a")).embroider(OpId("embroider.a"))
    val branchB = Warp.shuttle(OpId("source.b")).embroider(OpId("embroider.b"))
    val branchC = Warp.shuttle(OpId("source.c"))
        .embroider(OpId("embroider.c"))
        .map(OpId("map.m"))
        .embroider(OpId("embroider.d"))

    val unplanned: Draft<Unit> = branchA.combine(branchB).combine(branchC)
    val planned: Draft<Unit> = unplanned.plan(WarpStats.empty())

    val stats = WarpStats.empty()

    // Unplanned: 4 separate Embroider nodes → 4 rounds (one per node).
    check(unplanned.coordinationCost(stats).rounds == 4)
    // Planned: BatchedEmbroider(A,B,C) at level 0 + Embroider(D) at level 1 → 2 rounds.
    check(planned.coordinationCost(stats).rounds == 2)
    // rounds is a real lever — the planner measurably cuts round count.
    check(planned.coordinationCost(stats) < unplanned.coordinationCost(stats))
    // Coupling = 3: the level-0 batch bundles three agreements (blast-radius = 3).
    check(planned.coordinationCost(stats).coupling == 3)
}

// ── executeCoordinated ─────────────────────────────────────────────────────────

/**
 * [Draft.executeCoordinated] issues one proposal per coordinated node — one per
 * dependency level on a planned draft, not one per original [DraftStage.Embroider].
 *
 * An unplanned draft with K independent embroideries issues K proposals. After
 * [Draft.plan], independent embroideries at the same level are fused into a single
 * [DraftStage.BatchedEmbroider] node — so the same K agreements cost only `depth`
 * proposals, where `depth` is the coordination-DAG depth.
 */
@Suppress("unused")
internal suspend fun sampleExecuteCoordinated() {
    val branchA = Warp.shuttle(OpId("source.a")).embroider(OpId("embroider.a"))
    val branchB = Warp.shuttle(OpId("source.b")).embroider(OpId("embroider.b"))
    val unplanned: Draft<Unit> = branchA.combine(branchB)
    val planned: Draft<Unit> = unplanned.plan(WarpStats.empty())

    val unplannedProposals = mutableListOf<String>()
    unplanned.executeCoordinated { payload ->
        unplannedProposals.add(payload.decodeToString())
    }

    val plannedProposals = mutableListOf<String>()
    planned.executeCoordinated { payload ->
        plannedProposals.add(payload.decodeToString())
    }

    // Unplanned: 2 separate Embroider nodes → 2 proposals (one Raft round each).
    check(unplannedProposals.size == 2)
    // Planned: BatchedEmbroider(A,B) → 1 proposal (both agreements in one Raft round-trip).
    check(plannedProposals.size == 1)
    check(plannedProposals.single().contains("embroider.a"))
    check(plannedProposals.single().contains("embroider.b"))
}
