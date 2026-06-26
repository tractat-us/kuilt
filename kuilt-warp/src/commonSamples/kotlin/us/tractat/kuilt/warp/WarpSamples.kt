package us.tractat.kuilt.warp

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
