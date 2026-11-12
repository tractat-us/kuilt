package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Capacity and scale evidence for the lease model, gathered on [LeasePrototype] before the
 * `LocalHeddle` API is frozen (#3173, plan Task C). The numbers here are the deliverable: L1's
 * tolerances are taken from what these tests pin.
 *
 * ## Fairness
 *
 * 10,000 seeded steps over `root → t → {a:3, b:1, c:1}`. Each step either acquires (while fewer
 * leases are open than the current concurrency target, which churns over 1..8) or completes a seeded
 * open lease. Reservations are 1..5 and actual costs 0..8, so leases overrun; half the overruns are
 * renewed by `extend` in quanta first, half are left to `complete` to extend inline.
 *
 * Two arms, read together:
 * - **backlogged** — every leaf always waiting and eligible. The ideal is the weights, 3:1:1.
 * - **churn** — each leaf's waiting flag flips with probability 1/10 per step, and every acquire
 *   carries a seeded slot label (`x` or `y`): `a` runs on either, `b` only on `x`, `c` only on `y`.
 *   The ideal is **waiting-GPS**: each completed lease's actual cost shared by weight among the
 *   leaves that were *waiting* when it was acquired. That is heddle's entitlement model — a leaf
 *   waiting but not eligible keeps its claim, an idle one forfeits it at the wake clamp. The
 *   eligible-GPS share is reported beside it, not asserted.
 *
 * The bar was set before the first run, and is asserted:
 * - backlogged: every leaf's overall share within **0.01** of 3:1:1, every 500-step window within **0.05**;
 * - churn: every leaf's overall share within **0.03** of waiting-GPS, every 500-step window within **0.15**.
 *
 * ## Scale
 *
 * 5,000 runtime-style children under one lane plus 50,000 retired edges, built through the ledger's
 * own mutators, then timed acquires and completes. Timing is **printed, never asserted**: it is a
 * property of the box's load, not of the code. Run on JVM, `macosArm64` and `wasmJs` and read the
 * `LEASE-SCALE` lines.
 */
class LeaseCharacterizationTest {

    // ── Fairness ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun backloggedLeasesFollowTheWeights() {
        val run = runFairness(seed = 3173, churn = false)
        println(run.report("backlogged"))
        assertAll(
            { assertEquals(STEPS, run.loops, "every step ran") },
            { assertEquals(STEPS, run.acquires + run.completes + run.nothingEligible, "every step did one thing") },
            { assertTrue(run.overrunCompletes > 0, "the rig overran: ${run.overrunCompletes}") },
            { assertTrue(run.extends > 0, "the rig renewed by extend: ${run.extends}") },
            { assertTrue(run.inlineOverruns > 0, "the rig left overruns to complete: ${run.inlineOverruns}") },
            { assertTrue(run.mints > 0, "top-up minted: ${run.mints}") },
            { assertTrue(run.minUnspent >= 0L, "supply never ran out: ${run.minUnspent}") },
            { assertEquals(0, run.nothingEligible, "a backlogged leaf is always eligible") },
            { assertTrue(run.maxOverallError(run.idealWaiting) <= 0.01, "overall share vs 3:1:1: ${run.maxOverallError(run.idealWaiting)}") },
            { assertTrue(run.maxWindowError(run.idealWaiting) <= 0.05, "500-step window vs 3:1:1: ${run.maxWindowError(run.idealWaiting)}") },
        )
    }

    @Test
    fun churningLeasesFollowWaitingEntitlement() {
        val run = runFairness(seed = 3173, churn = true)
        println(run.report("churn"))
        assertAll(
            { assertEquals(STEPS, run.loops, "every step ran") },
            { assertEquals(STEPS, run.acquires + run.completes + run.nothingEligible, "every step did one thing") },
            { assertTrue(run.overrunCompletes > 0, "the rig overran: ${run.overrunCompletes}") },
            { assertTrue(run.extends > 0, "the rig renewed by extend: ${run.extends}") },
            { assertTrue(run.inlineOverruns > 0, "the rig left overruns to complete: ${run.inlineOverruns}") },
            { assertTrue(run.mints > 0, "top-up minted: ${run.mints}") },
            { assertTrue(run.minUnspent >= 0L, "supply never ran out: ${run.minUnspent}") },
            { assertTrue(run.filteredAcquires > 0, "the label excluded a waiting leaf: ${run.filteredAcquires}") },
            { assertTrue(run.wakes > 0, "a leaf went idle and came back: ${run.wakes}") },
            { assertTrue(run.maxOverallError(run.idealWaiting) <= 0.03, "overall share vs waiting-GPS: ${run.maxOverallError(run.idealWaiting)}") },
            { assertTrue(run.maxWindowError(run.idealWaiting) <= 0.15, "500-step window vs waiting-GPS: ${run.maxWindowError(run.idealWaiting)}") },
        )
    }

    private fun runFairness(seed: Int, churn: Boolean): FairnessRun {
        val rng = Random(seed)
        val tree = smallTree()
        val proto = LeasePrototype(tree.ledger, ROOT, tree.parentOf, watermark = CAPACITY * MAX_RESERVATION, mintChunk = CAPACITY * MAX_RESERVATION)
        val run = FairnessRun()
        val waiting = BooleanArray(LEAVES.size) { true }
        // What each open lease was acquired under, by lease id: the leaves waiting, and those eligible.
        val acquiredUnder = HashMap<Long, Pair<List<Int>, List<Int>>>()
        var target = 1 + rng.nextInt(CAPACITY.toInt())

        for (step in 0 until STEPS) {
            run.loops++
            if (step % 50 == 0) target = 1 + rng.nextInt(CAPACITY.toInt())
            if (churn) {
                for (i in LEAVES.indices) {
                    if (rng.nextInt(10) == 0) {
                        if (!waiting[i]) run.wakes++
                        waiting[i] = !waiting[i]
                    }
                }
            }
            val open = proto.openLeases()
            if (open.size < target) {
                val waitingIdx = LEAVES.indices.filter { waiting[it] }
                val label = if (churn) SLOT_LABELS[rng.nextInt(SLOT_LABELS.size)] else null
                val eligibleIdx = waitingIdx.filter { label == null || label in LEAF_LABELS[it] }
                if (eligibleIdx.size < waitingIdx.size) run.filteredAcquires++
                val reservation = 1L + rng.nextInt(MAX_RESERVATION.toInt())
                val out = proto.acquire(
                    waitingIdx.mapTo(HashSet()) { LEAVES[it] },
                    eligibleIdx.mapTo(HashSet()) { LEAVES[it] },
                    reservation,
                )
                when (out) {
                    is PrototypeAcquire.Acquired -> {
                        run.acquires++
                        acquiredUnder[out.lease.id] = waitingIdx to eligibleIdx
                    }
                    PrototypeAcquire.NothingEligible -> run.nothingEligible++
                    is PrototypeAcquire.DescentFailed -> fail("descent failed at ${out.at.value} on step $step")
                }
            } else {
                val lease = open[rng.nextInt(open.size)]
                val actual = rng.nextInt(MAX_ACTUAL + 1).toLong()
                if (actual > lease.reserved) run.overrunCompletes++
                if (actual > lease.reserved && rng.nextBoolean()) {
                    // Renew in quanta of the original reservation until the lease covers its cost.
                    var reserved = lease.reserved
                    while (reserved < actual) {
                        proto.extend(lease.id, lease.reserved)
                        reserved += lease.reserved
                        run.extends++
                    }
                }
                val done = proto.complete(lease.id, actual)
                if (done.overrun > 0L) run.inlineOverruns++
                run.completes++
                val (waitedIdx, eligibleIdx) = checkNotNull(acquiredUnder.remove(lease.id)) { "lease ${lease.id} untracked" }
                val leaf = LEAVES.indexOf(lease.leaf)
                run.record(step, leaf, actual, waitedIdx, eligibleIdx)
            }
            if (step % 1000 == 999) assertConserved(proto, "step $step")
        }
        assertConserved(proto, "end")
        run.mints = proto.mints
        run.minUnspent = proto.minUnspent
        return run
    }

    private fun assertConserved(proto: LeasePrototype, at: String) {
        val l = proto.ledger
        val groups = listOf(ROOT, LANE) + LEAVES
        val held = groups.sumOf { l.holdings(it, LeasePrototype.SELF) }
        val leafHeld = LEAVES.sumOf { l.holdings(it, LeasePrototype.SELF) }
        val reserved = proto.openLeases().sumOf { it.reserved }
        assertAll(
            { assertEquals(l.mintedTotal(), held + l.leafSpentTotal(), "Σ holdings + spent == minted at $at") },
            { assertEquals(reserved, leafHeld, "leaf holdings are exactly the open reservations at $at") },
            { assertEquals(emptyList(), l.validate(), "validate() is empty at $at") },
        )
    }

    /** Per-step service and the two ideals, kept as per-step rows so any 500-step window can be read. */
    private class FairnessRun {
        var loops = 0
        var acquires = 0
        var completes = 0
        var nothingEligible = 0
        var overrunCompletes = 0
        var extends = 0
        var inlineOverruns = 0
        var filteredAcquires = 0
        var wakes = 0
        var mints = 0
        var minUnspent = 0L
        val service = Array(STEPS) { LongArray(LEAVES.size) }
        val idealWaiting = Array(STEPS) { DoubleArray(LEAVES.size) }
        val idealEligible = Array(STEPS) { DoubleArray(LEAVES.size) }

        fun record(step: Int, leaf: Int, actual: Long, waited: List<Int>, eligible: List<Int>) {
            service[step][leaf] += actual
            share(idealWaiting[step], actual, waited)
            share(idealEligible[step], actual, eligible)
        }

        private fun share(into: DoubleArray, actual: Long, among: List<Int>) {
            val total = among.sumOf { WEIGHTS[it] }
            for (i in among) into[i] += actual.toDouble() * WEIGHTS[i] / total
        }

        fun totals(): LongArray = LongArray(LEAVES.size) { i -> service.sumOf { it[i] } }

        fun maxOverallError(ideal: Array<DoubleArray>): Double = windowError(ideal, 0, STEPS)

        fun maxWindowError(ideal: Array<DoubleArray>): Double =
            (0..STEPS - WINDOW).maxOf { start -> windowError(ideal, start, start + WINDOW) }

        private fun windowError(ideal: Array<DoubleArray>, from: Int, to: Int): Double {
            val got = DoubleArray(LEAVES.size)
            val want = DoubleArray(LEAVES.size)
            for (s in from until to) {
                for (i in LEAVES.indices) {
                    got[i] += service[s][i].toDouble()
                    want[i] += ideal[s][i]
                }
            }
            val gotTotal = got.sum()
            val wantTotal = want.sum()
            if (gotTotal == 0.0 || wantTotal == 0.0) return 0.0
            return LEAVES.indices.maxOf { abs(got[it] / gotTotal - want[it] / wantTotal) }
        }

        fun report(arm: String): String {
            val totals = totals()
            val all = totals.sum().toDouble()
            val shares = LEAVES.indices.joinToString(" ") { "${LEAVES[it].value}=${totals[it]}(${fmt(totals[it] / all)})" }
            val idealW = idealShares(idealWaiting)
            val idealE = idealShares(idealEligible)
            return "LEASE-FAIRNESS arm=$arm service[$shares] waitingGps[$idealW] eligibleGps[$idealE] " +
                "overallErrWaiting=${fmt(maxOverallError(idealWaiting))} windowErrWaiting=${fmt(maxWindowError(idealWaiting))} " +
                "overallErrEligible=${fmt(maxOverallError(idealEligible))} windowErrEligible=${fmt(maxWindowError(idealEligible))} " +
                "acquires=$acquires completes=$completes nothingEligible=$nothingEligible overruns=$overrunCompletes " +
                "extends=$extends inlineOverruns=$inlineOverruns filtered=$filteredAcquires wakes=$wakes " +
                "mints=$mints minUnspent=$minUnspent"
        }

        private fun idealShares(ideal: Array<DoubleArray>): String {
            val sums = DoubleArray(LEAVES.size) { i -> ideal.sumOf { it[i] } }
            val all = sums.sum()
            return LEAVES.indices.joinToString(" ") { "${LEAVES[it].value}=${fmt(sums[it] / all)}" }
        }
    }

    private class Tree(val ledger: EntitlementLedger, val parentOf: Map<GroupId, GroupId>)

    /** `root → t → {a:3, b:1, c:1}`, every edge active, no supply yet: top-up mints all of it. */
    private fun smallTree(): Tree {
        var l = EntitlementLedger.bootstrap(ROOT, emptyMap(), nonce = "genesis")
        val records = listOf(AttachmentRecord(AttachmentId("root>t"), ROOT, LANE, Weight.ONE)) +
            LEAVES.indices.map { i -> AttachmentRecord(AttachmentId("t>${LEAVES[i].value}"), LANE, LEAVES[i], Weight.of(WEIGHTS[i])) }
        for (r in records) {
            l = l.piece(checkNotNull(l.prepare(r)).delta)
            l = l.piece(checkNotNull(l.activate(r.id)).delta)
        }
        return Tree(l, mapOf(LANE to ROOT) + LEAVES.associateWith { LANE })
    }

    // ── Scale ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun acquireAtFiveThousandChildrenAndFiftyThousandRetiredEdges() {
        val rows = listOf(
            scaleRow(active = 5_000, retired = 50_000),
            scaleRow(active = 5_000, retired = 0),
            scaleRow(active = 50, retired = 0),
        )
        rows.forEach { println(it) }
    }

    private fun scaleRow(active: Int, retired: Int): String {
        val clock = TimeSource.Monotonic
        val built = clock.markNow()
        val lane = GroupId("lane")
        val laneEdge = AttachmentRecord(AttachmentId("root>lane"), ROOT, lane, Weight.ONE)
        val children = (0 until active).map { k ->
            AttachmentRecord(AttachmentId("lane>c-$k"), lane, GroupId("lane/c-$k"), Weight.ONE)
        }
        val gone = (0 until retired).map { k ->
            AttachmentRecord(AttachmentId("lane>gone-$k"), lane, GroupId("lane/gone-$k"), Weight.ONE)
        }

        var l = EntitlementLedger.bootstrap(ROOT, emptyMap(), nonce = "genesis")
        l = l.piece(checkNotNull(l.prepare(laneEdge)).delta)
        l = l.piece(checkNotNull(l.activate(laneEdge.id)).delta)
        val prepared = l.piece(joinAll((children + gone).map { checkNotNull(l.prepare(it)).delta }))
        val shaped = prepared.piece(
            joinAll(
                children.map { checkNotNull(prepared.activate(it.id)).delta } +
                    gone.map { checkNotNull(prepared.close(it.id)).delta },
            ),
        )
        val ledger = shaped.piece(joinAll(gone.map { checkNotNull(shaped.retire(it.id)).delta }))
        val buildTime = built.elapsedNow()

        val parentOf = HashMap<GroupId, GroupId>().apply {
            put(lane, ROOT)
            children.forEach { put(it.child, lane) }
        }
        val proto = LeasePrototype(ledger, ROOT, parentOf, watermark = CAPACITY * MAX_RESERVATION, mintChunk = CAPACITY * MAX_RESERVATION)
        val everyone = children.mapTo(HashSet()) { it.child }

        val seatMark = clock.markNow()
        val first = proto.acquire(everyone, everyone, reservation = 3L)
        val seatTime = seatMark.elapsedNow()
        val acquireTimes = (0 until TIMED).map {
            val mark = clock.markNow()
            val out = proto.acquire(everyone, everyone, reservation = 3L)
            mark.elapsedNow().also { assertTrue(out is PrototypeAcquire.Acquired, "timed acquire $it: $out") }
        }
        // Where an acquire's time goes: each ledger primitive it leans on, timed alone on the same ledger.
        val probe = proto.ledger
        val someChild = children.first().child
        val primitives = listOf(
            "activeChildren" to { probe.activeChildren(lane).size.toLong() },
            "holdings" to { probe.holdings(lane, LeasePrototype.SELF) },
            "lineageOf" to { probe.lineageOf(someChild)?.size?.toLong() ?: -1L },
            "isLeaf" to { if (probe.isLeaf(someChild)) 1L else 0L },
            "piece" to { probe.piece(probe.mint(MintId("probe"), ROOT, LeasePrototype.SELF, 1L).delta).mintedTotal() },
        ).joinToString(" ") { (name, call) ->
            val mark = clock.markNow()
            repeat(PRIMITIVE_REPS) { call() }
            "$name=${mark.elapsedNow().inWholeMicroseconds / PRIMITIVE_REPS}"
        }
        val completeTimes = proto.openLeases().map { lease ->
            val mark = clock.markNow()
            proto.complete(lease.id, actual = 2L)
            mark.elapsedNow()
        }

        val after = proto.ledger
        assertAll(
            { assertTrue(first is PrototypeAcquire.Acquired, "the seating acquire: $first") },
            { assertEquals(active, after.activeChildren(lane).size, "active children") },
            { assertEquals(retired, gone.count { after.lifecycle(it.id) == Lifecycle.RETIRED }, "retired edges") },
            { assertEquals(active, children.count { after.gauge(it.id) != null }, "the first acquire seated every newborn") },
            { assertEquals(TIMED + 1, completeTimes.size, "every lease completed") },
            { assertEquals(TIMED, acquireTimes.size, "timed acquires") },
            { assertEquals((TIMED + 1) * 2L, after.leafSpentTotal(), "each lease charged its 2 units") },
        )
        return "LEASE-SCALE active=$active retired=$retired buildMs=${buildTime.inWholeMilliseconds} " +
            "firstAcquireMs=${seatTime.inWholeMilliseconds} " +
            "acquireUs[mean=${acquireTimes.meanUs()} min=${acquireTimes.minOf { it.inWholeMicroseconds }} max=${acquireTimes.maxOf { it.inWholeMicroseconds }}] " +
            "completeUs[mean=${completeTimes.meanUs()} max=${completeTimes.maxOf { it.inWholeMicroseconds }}] " +
            "primitiveUs[$primitives] mints=${proto.mints}"
    }

    /** Join many small deltas pairwise, so building `n` edges costs `n log n` map copies rather than `n²`. */
    private fun joinAll(deltas: List<EntitlementLedger>): EntitlementLedger {
        var layer = deltas
        while (layer.size > 1) {
            layer = layer.chunked(2).map { pair -> if (pair.size == 2) pair[0].piece(pair[1]) else pair[0] }
        }
        return layer.singleOrNull() ?: EntitlementLedger.ZERO
    }

    private fun List<Duration>.meanUs(): Long = sumOf { it.inWholeMicroseconds } / size

    private companion object {
        const val STEPS = 10_000
        const val WINDOW = 500
        const val TIMED = 10
        const val PRIMITIVE_REPS = 5
        const val CAPACITY = 8L
        const val MAX_RESERVATION = 5L
        const val MAX_ACTUAL = 8
        val ROOT = GroupId("root")
        val LANE = GroupId("t")
        val LEAVES = listOf(GroupId("a"), GroupId("b"), GroupId("c"))
        val WEIGHTS = listOf(3L, 1L, 1L)
        val SLOT_LABELS = listOf("x", "y")
        val LEAF_LABELS = listOf(setOf("x", "y"), setOf("x"), setOf("y"))

        fun fmt(d: Double): String = ((d * 10_000).let { kotlin.math.round(it) } / 10_000).toString()
    }
}
