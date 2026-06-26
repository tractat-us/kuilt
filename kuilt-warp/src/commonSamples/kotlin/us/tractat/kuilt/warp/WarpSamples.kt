package us.tractat.kuilt.warp

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Samples for the warp B3 monotone combinators used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── shuttle / Draft ──────────────────────────────────────────────────────────

/**
 * Build a [Draft] pipeline that maps and filters coordination-free, then embroideries
 * (consensus) once at the end. Nothing executes — only OpIds are recorded.
 */
@Suppress("unused")
internal fun sampleShuttle() {
    val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
        .map(OpId("score"))
        .filter(OpId("above-threshold"))
        .embroider(OpId("rank"))

    check(draft.stages.size == 4)
    check(draft.isMonotone.not())          // has an Embroider stage
    check(draft.embroidery?.opId == OpId("rank"))
}

// ── zip ──────────────────────────────────────────────────────────────────────

/**
 * Pair a grow-only counter with a net counter into one atomic coordination-free snapshot.
 * Both components converge independently under componentwise join.
 */
@Suppress("unused")
internal fun sampleZip() {
    val r1 = ReplicaId("r1")
    val r2 = ReplicaId("r2")

    // Each peer carries its own (events seen, net score) snapshot.
    val peerA: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
        CoordinationFree(GCounter.of(r1 to 3L))
            .zip(CoordinationFree(PNCounter.ZERO.piece(PNCounter.ZERO.increment(r1, 100L).delta)))

    val peerB: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
        CoordinationFree(GCounter.of(r2 to 7L))
            .zip(CoordinationFree(PNCounter.ZERO.piece(PNCounter.ZERO.increment(r2, 200L).delta)))

    // After merging: GCounter sums, PNCounter sums.
    val merged = peerA.embroider(peerB)
    check(merged.state.first.value == 10L)   // 3 + 7
    check(merged.state.second.value == 300L) // 100 + 200

    // Idempotent: absorbing the same peer again changes nothing.
    check(merged.embroider(peerA).state == merged.state)
}

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

// ── WarpStats ─────────────────────────────────────────────────────────────────

/**
 * Observe elements into a [WarpStats] sketch, query estimated cardinality,
 * and verify the CRDT convergence property (merge equals direct observation).
 */
@Suppress("unused")
internal fun sampleWarpStats() {
    val src = OpId("source.docs")

    // Observe 100 distinct elements.
    var stats = WarpStats.empty()
    for (i in 1..100) stats = stats.piece(stats.observe(src, "doc_$i"))

    // estimatedCardinality is a HyperLogLog sketch — within ~0.81% relative error.
    val estimate = stats.estimatedCardinality(src)
    check(estimate in 90L..110L) { "expected ~100, got $estimate" }

    // Unseen sources return 0.
    check(stats.estimatedCardinality(OpId("source.other")) == 0L)

    // Two replicas that observed different halves converge to the same answer.
    var replicaA = WarpStats.empty()
    for (i in 1..50) replicaA = replicaA.piece(replicaA.observe(src, "doc_$i"))
    var replicaB = WarpStats.empty()
    for (i in 51..100) replicaB = replicaB.piece(replicaB.observe(src, "doc_$i"))

    val merged = replicaA.piece(replicaB)
    check(merged.estimatedCardinality(src) == stats.estimatedCardinality(src)) {
        "merging two halves must equal observing all at once"
    }
}

// ── IncrementalResult / awaitThreshold ────────────────────────────────────────

/**
 * [IncrementalResult] accumulates lattice contributions that can only grow. Each call
 * to [IncrementalResult.contribute] joins the new delta into the running result via the
 * lattice join — idempotent, commutative, and thread-safe.
 *
 * [IncrementalResult.awaitThreshold] is the LVar-style observation primitive: it
 * suspends until the result first satisfies a monotone predicate, then returns that
 * stable snapshot. Because the lattice only grows, once crossed the threshold stays
 * crossed — the returned value is permanently valid without rechecking.
 *
 * In tests: launch `awaitThreshold` on `backgroundScope`, then drive with `runCurrent()`
 * after each `contribute`. See `IncrementalExecutionTest.awaitThresholdSuspendsUntilCrossed`.
 */
@Suppress("unused")
internal fun sampleAwaitThreshold() {
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")

    // Contribute is synchronous and thread-safe.
    val result = IncrementalResult(GCounter.ZERO)
    result.contribute(GCounter.of(alice to 3L))
    result.contribute(GCounter.of(bob to 2L))

    // The lattice only grows: current value is the join of all contributions so far.
    check(result.state.value.value == 5L)

    // Duplication is absorbed — same delta twice changes nothing.
    result.contribute(GCounter.of(alice to 3L))
    check(result.state.value.value == 5L)

    // awaitThreshold: in a suspend context, suspends until the predicate is first true.
    //   val crossed: GCounter = result.awaitThreshold { it.value >= 5L }
    // Returns the first value that satisfies the predicate; the lattice cannot fall below it.
}

// ── ConvergentExecution ───────────────────────────────────────────────────────

/**
 * [ConvergentExecution] ties a [Draft] to a converging [IncrementalResult], queuing
 * lattice deltas through an `UNLIMITED` channel processed on the caller-supplied [scope].
 *
 * The [scope] is **required** with no default — production wires a service scope;
 * tests wire `backgroundScope` (sharing the test scheduler, keeping contributions
 * in sync with virtual time). Under a `StandardTestDispatcher`, call `runCurrent()`
 * after [ConvergentExecution.submit] to drain the queue and read the updated result.
 *
 * See `IncrementalExecutionTest.executionLinksMonotoneDraftToConvergentResult` for
 * a full async example under a test dispatcher.
 */
@Suppress("unused")
internal fun sampleConvergentExecution(scope: CoroutineScope) {
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")

    val draft = Warp.shuttle(OpId("source"))
        .map(OpId("map.score"))
        .filter(OpId("filter.threshold"))

    // scope is a service scope in production; backgroundScope in tests.
    val exec = ConvergentExecution(draft = draft, scope = scope, initial = GCounter.ZERO)

    // submit is non-blocking — queued to an UNLIMITED channel, processed on scope.
    exec.submit(GCounter.of(alice to 5L))
    exec.submit(GCounter.of(bob to 3L))
    // After scope runs: exec.result.state.value.value == 8L

    // The draft is exposed for inspection and cost-model integration.
    check(exec.draft.isMonotone)
    check(exec.draft.stages.size == 3)
}

// ── FedAvg ───────────────────────────────────────────────────────────────────

/**
 * Each peer trains locally and contributes a single sample to the federated average.
 * The merged [FedAvg.weights] is the count-weighted mean across all peers — no central
 * server, no coordination round, converges regardless of delivery order or duplicates.
 */
@Suppress("unused")
internal fun sampleFedAvg() {
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")
    val carol = ReplicaId("carol")

    // Each peer trains locally and contributes its results.
    val fromAlice = FedAvg.contribution(alice, sampleCount = 100L, localWeights = listOf(0.5, 0.3))
    val fromBob   = FedAvg.contribution(bob,   sampleCount = 200L, localWeights = listOf(0.7, 0.1))
    val fromCarol = FedAvg.contribution(carol, sampleCount = 300L, localWeights = listOf(0.9, 0.5))

    // Any replica merges contributions in any order — result is the same.
    val merged = FedAvg.ZERO.piece(fromAlice).piece(fromBob).piece(fromCarol)

    // weights[i] = Σ(n_k * w_k[i]) / Σ(n_k)
    val w = merged.weights
    check(w.size == 2)
    // Spot-check: (100*0.5 + 200*0.7 + 300*0.9) / (100+200+300) = 460/600 ≈ 0.7667
    check(w[0] in 0.766..0.768)

    // Idempotent: absorbing the same contribution again changes nothing.
    check(merged.piece(fromAlice) == merged)
    check(merged.piece(fromBob) == merged)

    // Rides the coordination-free path — no Seam or Raft required.
    val free = CoordinationFree(fromAlice).embroider(CoordinationFree(fromBob))
    check(free.state == FedAvg.ZERO.piece(fromAlice).piece(fromBob))
}

// ── combine ───────────────────────────────────────────────────────────────────

/**
 * Merge two [Draft]s into a single dependency DAG with two independent branches.
 *
 * The resulting draft's [Draft.embroideries] contains both branches' coordination
 * points. Neither is a predecessor of the other — they are truly independent and
 * can be batched into a single consensus round by [Draft.consolidateEmbroideries].
 */
@Suppress("unused")
internal fun sampleCombine() {
    val docs = Warp.shuttle(OpId("source.docs"))
        .map(OpId("map.score"))
        .embroider(OpId("embroider.rank"))
    val scores = Warp.shuttle(OpId("source.scores"))
        .filter(OpId("filter.nonzero"))
        .embroider(OpId("embroider.vote"))

    val combined: Draft<Unit> = docs.combine(scores)

    // Both branches' embroideries are present — independent, no edges connect them.
    check(combined.embroideries.size == 2)
    check(!combined.isMonotone)
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

// ── joinAllOrNull ─────────────────────────────────────────────────────────────

/**
 * Merge a dynamically-built list of contributions where the list may be empty.
 * Returns null when the list is empty rather than throwing [NoSuchElementException].
 */
@Suppress("unused")
internal fun sampleJoinAllOrNull() {
    val r1 = ReplicaId("r1")
    val r2 = ReplicaId("r2")

    // Empty list — returns null instead of throwing.
    val empty = joinAllOrNull(emptyList<CoordinationFree<GCounter>>())
    check(empty == null)

    // Non-empty list — same result as joinAll.
    val contributions = listOf(
        CoordinationFree(GCounter.of(r1 to 10L)),
        CoordinationFree(GCounter.of(r2 to 20L)),
    )
    val merged = joinAllOrNull(contributions)
    check(merged?.state?.value == 30L)
}

// ── pinned execution ──────────────────────────────────────────────────────────

/**
 * Pin a task to a named owner, decoupling who-runs from the consistent-hash ring.
 *
 * A descriptor with [TaskDescriptor.pinnedOwner] set is owned by exactly that peer; absent,
 * the task is ring-assigned. This is what [WarpNode.enqueueLocal] builds under the hood to
 * keep a data-local step — e.g. a federated-learning training round on private data — on the
 * peer that holds the data.
 */
@Suppress("unused")
internal fun samplePinnedExecution() {
    val alice = PeerId("alice")

    // Ring-assigned: hash(taskId) picks the owner (work-stealing).
    val free = TaskDescriptor(OpId("train"), byteArrayOf(1, 2, 3))
    check(free.pinnedOwner == null)

    // Pinned: only `alice` ever claims and runs this task — it never re-homes.
    val pinned = TaskDescriptor(OpId("train"), byteArrayOf(1, 2, 3), pinnedOwner = alice)
    check(pinned.pinnedOwner == alice)
}
