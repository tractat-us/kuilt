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

// ── combine ───────────────────────────────────────────────────────────────────

/**
 * Merge two [Draft]s into a single dependency DAG with two independent branches.
 *
 * The resulting draft's [Draft.embroideries] contains both branches' coordination
 * points. Neither is a predecessor of the other — they are truly independent and
 * can be batched into a single consensus round by the `consolidateEmbroideries` rewrite
 * in `:kuilt-warp-planning`.
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
