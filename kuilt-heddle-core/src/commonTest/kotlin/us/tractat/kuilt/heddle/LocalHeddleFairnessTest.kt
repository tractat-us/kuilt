package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task C's fairness and supply evidence, re-run on [LocalHeddle] (#3173, plan Task L1). Task C
 * gathered it on a test-only prototype before the engine's API was frozen, and pinned every
 * integer the runs produced in one `fingerprint()` string. The pins below are those strings,
 * unchanged: the engine makes exactly the picks, extensions, overruns and wakes the prototype did,
 * on every target. The prototype is gone; this is what it proved.
 *
 * ## The generator
 *
 * 10,000 seeded steps over `root → t → {a:3, b:1, c:1}`. While fewer leases are open than the
 * concurrency target (re-drawn over 1..8 every 50 steps) a step acquires; otherwise it draws an open
 * lease and either **extends** it or **completes** it. Each lease is given, at acquire, a reservation
 * of 1..5, a hidden actual cost of 0..8, and whether it renews. A renewing lease whose actual cost
 * exceeds what it has reserved is extended by one quantum (its reservation) and **left open**, so
 * later picks see its virtual service already risen (design §4); any other lease completes, and an
 * overrun is then extended inline by `complete`.
 *
 * ## Three arms
 *
 * - **backlogged** — every leaf always waiting and eligible. The ideal is the weights, 3:1:1.
 * - **churn** — each leaf's waiting flag flips with probability 1/10 per step, and every acquire
 *   carries a seeded slot label (`x` or `y`): `a` runs on either, `b` only on `x`, `c` only on `y`.
 *   The ideal is **waiting-GPS**: each completed lease's actual cost shared by weight among the
 *   leaves that were *waiting* when it was acquired. A leaf waiting but not eligible keeps its
 *   claim; an idle one forfeits it at the wake clamp.
 * - **starved** — the churn generator with a watermark and chunk of 2, below the largest
 *   reservation. It must report exhaustion and still never refuse a charge.
 *
 * ## Which fields the engine decides
 *
 * The fingerprints are a differential against the prototype, so they stay byte-identical; but not
 * every field in them is the engine's.
 * - **Engine-decided:** the per-leaf totals (which leaf each pick chose), `acquires`, `filtered`,
 *   `seesExtended`, and `wakes`, which the harness counts but reads off the engine's own wake memory.
 * - **Harness-decided:** `extends`, `completes` and `inlineOverruns` follow the generator's plan for
 *   each lease; `nothingEligible` counts the generator's empty eligible sets, which the engine only
 *   echoes; and every supply field, `exhaustedAcquires`, `exhaustedExtends`, `mints`, `forcedMints`
 *   and `minUnspent`, is the harness's own top-up below. The engine's `SupplyExhausted` arms and its
 *   own forced mint are never reached here (each run asserts the engine minted nothing itself), so
 *   they are pinned in `LocalHeddleTest` instead: `exhaustedSupplyIsNamed`,
 *   `admitIsNeverRefusedForSupply` and `anOverrunWithTheRootShortForcesOneMint`.
 *
 * ## Supply is the harness's, as it was the prototype's
 *
 * Before L2 the engine mints nothing on its own except a `complete`'s or an `admit`'s shortfall.
 * So [Harness.topUp] is the prototype's top-up, run before the call it funds: at most one chunk
 * below the watermark, the shortfall too when the call is a `complete`, and an acquire or extend
 * refused when one chunk cannot cover it. That reproduces the prototype's ledger at every pick,
 * which is what makes the fingerprints comparable. Supply in the first two arms is a watermark of
 * 40 (capacity 8 × the largest reservation 5) with a 40-unit chunk, so exhaustion is impossible
 * there by construction; the starved arm is where it is measured.
 *
 * ## The bar
 *
 * - backlogged: every leaf's overall share within **0.01** of 3:1:1, every 500-step window within **0.05**;
 * - churn: every leaf's overall share within **0.03** of waiting-GPS, every 500-step window within **0.15**.
 *
 * On Task C's first generator, feeding the **eligible** set to wake detection moved the churn arm's
 * overall error from 0.0108 to 0.0737 (red) while the window stayed under its bar, so the overall
 * bound is the sharper instrument. The window bar has not been shown to red on anything.
 */
class LocalHeddleFairnessTest {

    @Test
    fun backloggedLeasesFollowTheWeights() {
        val run = runFairness(seed = 3173, churn = false, supply = NORMAL_SUPPLY)
        println(run.report("backlogged"))
        val overall = run.maxOverallError(run.idealWaiting)
        val window = run.maxWindowError(run.idealWaiting)
        assertAll(
            { assertEquals(STEPS, run.loops, "every step ran") },
            { assertEquals(STEPS, run.stepKinds(), "every step did exactly one thing") },
            { assertTrue(run.acquiresSeeingExtended > 0, "a pick saw an extended open lease: ${run.acquiresSeeingExtended}") },
            { assertTrue(run.extends > 0, "the rig renewed by extend: ${run.extends}") },
            { assertTrue(run.inlineOverruns > 0, "the rig left overruns to complete: ${run.inlineOverruns}") },
            { assertTrue(run.mints > 0, "top-up minted: ${run.mints}") },
            { assertEquals(0, run.exhaustedAcquires + run.exhaustedExtends + run.forcedMints, "a capacity-sized watermark never runs dry") },
            { assertEquals(0, run.nothingEligible, "a backlogged leaf is always eligible") },
            { assertTrue(overall <= 0.01, "overall share vs 3:1:1: $overall") },
            { assertTrue(window <= 0.05, "500-step window vs 3:1:1: $window") },
            // The pins: identical on every target, since the walk is over Lists and the allocator sorts.
            {
                assertEquals(
                    "a=9199 b=3070 c=3064 acquires=3764 extends=2472 completes=3764 nothingEligible=0 " +
                        "exhaustedAcquires=0 exhaustedExtends=0 inlineOverruns=1051 filtered=0 seesExtended=2412 wakes=0 " +
                        "mints=385 forcedMints=0 minUnspent=35",
                    run.fingerprint(),
                )
            },
            { assertEquals(0.000222, overall, PIN_TOLERANCE, "pinned overall error") },
            { assertEquals(0.023810, window, PIN_TOLERANCE, "pinned window error") },
        )
    }

    @Test
    fun churningLeasesFollowWaitingEntitlement() {
        val run = runFairness(seed = 3173, churn = true, supply = NORMAL_SUPPLY)
        println(run.report("churn"))
        val overall = run.maxOverallError(run.idealWaiting)
        val window = run.maxWindowError(run.idealWaiting)
        assertAll(
            { assertEquals(STEPS, run.loops, "every step ran") },
            { assertEquals(STEPS, run.stepKinds(), "every step did exactly one thing") },
            { assertTrue(run.acquiresSeeingExtended > 0, "a pick saw an extended open lease: ${run.acquiresSeeingExtended}") },
            { assertTrue(run.extends > 0, "the rig renewed by extend: ${run.extends}") },
            { assertTrue(run.inlineOverruns > 0, "the rig left overruns to complete: ${run.inlineOverruns}") },
            { assertTrue(run.mints > 0, "top-up minted: ${run.mints}") },
            { assertEquals(0, run.exhaustedAcquires + run.exhaustedExtends + run.forcedMints, "a capacity-sized watermark never runs dry") },
            { assertTrue(run.filteredAcquires > 0, "a successful acquire had a waiting leaf the label excluded: ${run.filteredAcquires}") },
            { assertTrue(run.wakes > 0, "the allocator sampled a leaf going idle and coming back: ${run.wakes}") },
            { assertTrue(overall <= 0.03, "overall share vs waiting-GPS: $overall") },
            { assertTrue(window <= 0.15, "500-step window vs waiting-GPS: $window") },
            {
                assertEquals(
                    // Re-pinned for the 2⁻⁴ position grid (#3313): 4 picks moved from b to a. Exact
                    // rationals read a=6731 b=2899 and an overall error of 0.016677.
                    "a=6735 b=2895 c=3172 acquires=3216 extends=1943 completes=3209 nothingEligible=1632 " +
                        "exhaustedAcquires=0 exhaustedExtends=0 inlineOverruns=878 filtered=1489 seesExtended=1994 wakes=1030 " +
                        "mints=322 forcedMints=0 minUnspent=33",
                    run.fingerprint(),
                )
            },
            { assertEquals(0.016990, overall, PIN_TOLERANCE, "pinned overall error") },
            { assertEquals(0.089246, window, PIN_TOLERANCE, "pinned window error") },
        )
    }

    @Test
    fun starvedSupplyIsReportedAndNeverRefusesACharge() {
        val run = runFairness(seed = 3173, churn = true, supply = STARVED_SUPPLY)
        println(run.report("starved"))
        assertAll(
            { assertEquals(STEPS, run.loops, "every step ran") },
            { assertEquals(STEPS, run.stepKinds(), "every step did exactly one thing") },
            { assertTrue(run.exhaustedAcquires > 0, "an acquire reported exhaustion: ${run.exhaustedAcquires}") },
            { assertTrue(run.exhaustedExtends > 0, "an extend reported exhaustion: ${run.exhaustedExtends}") },
            { assertTrue(run.forcedMints > 0, "complete covered an overrun the root could not: ${run.forcedMints}") },
            { assertTrue(run.acquires > 0 && run.completes > 0, "work still flowed: ${run.acquires} / ${run.completes}") },
            {
                assertEquals(
                    // Re-pinned for the 2⁻⁴ position grid (#3313): 5 picks moved from b to a.
                    "a=4034 b=1832 c=1874 acquires=1964 extends=1929 completes=1959 nothingEligible=1705 " +
                        "exhaustedAcquires=2403 exhaustedExtends=40 inlineOverruns=737 filtered=914 seesExtended=1325 wakes=857 " +
                        "mints=3316 forcedMints=460 minUnspent=0",
                    run.fingerprint(),
                )
            },
        )
    }

    private class Supply(val watermark: Long, val mintChunk: Long)

    /** What the generator decided for one lease at acquire. */
    private class Plan(val reservation: Long, val actual: Long, var renews: Boolean, val waited: List<Int>, val eligible: List<Int>)

    /** The prototype's top-up, run by the harness before the call it funds. */
    private class Harness(val heddle: LocalHeddle, val supply: Supply) {
        var mints = 0
        var forcedMints = 0
        var minUnspent = Long.MAX_VALUE

        /**
         * Fund a call needing [amount] at the root: one chunk below the watermark, then the exact
         * shortfall too if [force], else `false` when the root still could not cover it. Nothing is
         * minted when it answers `false`.
         */
        fun topUp(amount: Long, force: Boolean): Boolean {
            val held = heddle.snapshot().ledger.holdings(ROOT, LOCAL)
            if (mints + forcedMints > 0) minUnspent = minOf(minUnspent, held)
            val chunk = if (held < supply.watermark) supply.mintChunk else 0L
            val shortfall = maxOf(0L, amount - held - chunk)
            if (shortfall > 0L && !force) return false
            if (chunk > 0L) {
                heddle.mintSupply(chunk)
                mints++
            }
            if (shortfall > 0L) {
                heddle.mintSupply(shortfall)
                forcedMints++
            }
            return true
        }
    }

    private fun runFairness(seed: Int, churn: Boolean, supply: Supply): FairnessRun {
        val rng = Random(seed)
        val heddle = LocalHeddle.over(smallTree(), ROOT, LocalPolicy(defaultReservation = 1L), epoch = 0L, Leasable.EveryGroup)
        val harness = Harness(heddle, supply)
        val run = FairnessRun()
        val waiting = BooleanArray(LEAVES.size) { true }
        val plans = HashMap<LeaseId, Plan>() // looked up by lease id, never iterated
        var target = 1 + rng.nextInt(CAPACITY)

        for (step in 0 until STEPS) {
            run.loops++
            if (step % 50 == 0) target = 1 + rng.nextInt(CAPACITY)
            if (churn) {
                for (i in LEAVES.indices) if (rng.nextInt(10) == 0) waiting[i] = !waiting[i]
            }
            val open = heddle.snapshot().leases
            if (open.size < target) {
                val waitingIdx = LEAVES.indices.filter { waiting[it] }
                val label = if (churn) SLOT_LABELS[rng.nextInt(SLOT_LABELS.size)] else null
                val eligibleIdx = waitingIdx.filter { label == null || label in LEAF_LABELS[it] }
                val reservation = 1L + rng.nextInt(MAX_RESERVATION)
                val actual = rng.nextInt(MAX_ACTUAL + 1).toLong()
                val renews = rng.nextBoolean()
                val seesExtended = open.any { lease -> lease.reserved > (plans[lease.id]?.reservation ?: lease.reserved) }
                val eligibleLeaves = eligibleIdx.mapTo(HashSet()) { LEAVES[it] }
                val before = heddle.snapshot()
                if (eligibleLeaves.isNotEmpty() && !harness.topUp(reservation, force = false)) {
                    run.exhaustedAcquires++
                    assertSame(before.ledger, heddle.snapshot().ledger, "an exhausted acquire on step $step left the ledger unchanged")
                } else {
                    val waitingLeaves = waitingIdx.mapTo(HashSet()) { LEAVES[it] }
                    when (val out = heddle.acquire(waitingLeaves, eligibleLeaves, startedAt = step.toLong(), reservation = reservation)) {
                        is AcquireOutcome.Acquired -> {
                            if (out.lease.leaf !in eligibleLeaves) fail("step $step leased ${out.lease.leaf.value}, outside $eligibleLeaves")
                            run.acquires++
                            if (eligibleIdx.size < waitingIdx.size) run.filteredAcquires++
                            if (seesExtended) run.acquiresSeeingExtended++
                            plans[out.lease.id] = Plan(reservation, actual, renews, waitingIdx, eligibleIdx)
                        }
                        AcquireOutcome.NothingEligible -> run.nothingEligible++
                        AcquireOutcome.SupplyExhausted -> fail("the harness funded step $step, yet the engine reported exhaustion")
                        is AcquireOutcome.NoLiveLineage -> fail("descent failed at ${out.at.value} on step $step")
                        is AcquireOutcome.PositionCeiling -> fail("no position nears its ceiling here, step $step")
                    }
                    // A wake is an edge the allocator last observed idle and has now observed waiting.
                    val observed = before.allocatorMemory.demandingObserved
                    run.wakes += heddle.snapshot().allocatorMemory.demandingObserved.count { (edge, demanding) -> demanding && observed[edge] == false }
                }
            } else {
                val lease = open[rng.nextInt(open.size)]
                val plan = checkNotNull(plans[lease.id]) { "lease ${lease.id} untracked" }
                if (plan.renews && plan.actual > lease.reserved) {
                    if (harness.topUp(plan.reservation, force = false)) {
                        assertEquals(LeaseOutcome.Done(0L, 0L), heddle.extend(lease.id, plan.reservation))
                        run.extends++
                    } else {
                        // The work runs on unrenewed and `complete` covers it: a lease that kept
                        // retrying could hold every slot while supply never came back.
                        run.exhaustedExtends++
                        plan.renews = false
                    }
                } else {
                    val overrun = maxOf(0L, plan.actual - lease.reserved)
                    if (overrun > 0L) harness.topUp(overrun, force = true)
                    assertEquals(LeaseOutcome.Done(plan.actual, overrun), heddle.complete(lease.id, plan.actual))
                    if (overrun > 0L) run.inlineOverruns++
                    run.completes++
                    plans.remove(lease.id)
                    run.record(step, LEAVES.indexOf(lease.leaf), plan.actual, plan.waited, plan.eligible)
                }
            }
            if (step % 1000 == 999) assertConserved(heddle, "step $step")
        }
        assertConserved(heddle, "end")
        run.mints = harness.mints
        run.forcedMints = harness.forcedMints
        run.minUnspent = harness.minUnspent
        assertEquals(0, heddle.forcedMints, "the harness funded every overrun, so the engine never minted its own")
        return run
    }

    private fun assertConserved(heddle: LocalHeddle, at: String) {
        val s = heddle.snapshot()
        val l = s.ledger
        val groups = listOf(ROOT, LANE) + LEAVES
        val held = groups.sumOf { l.holdings(it, LOCAL) }
        val leafHeld = LEAVES.sumOf { l.holdings(it, LOCAL) }
        val reserved = s.leases.sumOf { it.reserved }
        assertAll(
            { assertEquals(l.mintedTotal(), held + l.leafSpentTotal(), "Σ holdings + spent == minted at $at") },
            { assertEquals(reserved, leafHeld, "leaf holdings are exactly the open reservations at $at") },
            { assertEquals(emptyList(), l.validate(), "validate() is empty at $at") },
        )
    }

    /**
     * Per-step service and the two ideals as **prefix sums**, so any window is two subtractions and
     * the worst 500-step window is one pass.
     */
    private class FairnessRun {
        var loops = 0
        var acquires = 0
        var completes = 0
        var extends = 0
        var nothingEligible = 0
        var exhaustedAcquires = 0
        var exhaustedExtends = 0
        var inlineOverruns = 0
        var filteredAcquires = 0
        var acquiresSeeingExtended = 0
        var wakes = 0
        var mints = 0
        var forcedMints = 0
        var minUnspent = 0L
        private val service = Array(STEPS) { LongArray(LEAVES.size) }
        val idealWaiting = Array(STEPS) { DoubleArray(LEAVES.size) }
        val idealEligible = Array(STEPS) { DoubleArray(LEAVES.size) }

        fun stepKinds(): Int = acquires + extends + completes + nothingEligible + exhaustedAcquires + exhaustedExtends

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

        private fun prefix(rows: Array<DoubleArray>): Array<DoubleArray> {
            val out = Array(STEPS + 1) { DoubleArray(LEAVES.size) }
            for (s in 0 until STEPS) for (i in LEAVES.indices) out[s + 1][i] = out[s][i] + rows[s][i]
            return out
        }

        private val servicePrefix: Array<DoubleArray> by lazy {
            prefix(Array(STEPS) { s -> DoubleArray(LEAVES.size) { i -> service[s][i].toDouble() } })
        }

        fun maxOverallError(ideal: Array<DoubleArray>): Double {
            val want = prefix(ideal)
            return windowError(want, 0, STEPS)
        }

        fun maxWindowError(ideal: Array<DoubleArray>): Double {
            val want = prefix(ideal)
            return (0..STEPS - WINDOW).maxOf { start -> windowError(want, start, start + WINDOW) }
        }

        private fun windowError(want: Array<DoubleArray>, from: Int, to: Int): Double {
            val got = DoubleArray(LEAVES.size) { servicePrefix[to][it] - servicePrefix[from][it] }
            val wanted = DoubleArray(LEAVES.size) { want[to][it] - want[from][it] }
            val gotTotal = got.sum()
            val wantTotal = wanted.sum()
            if (gotTotal == 0.0 || wantTotal == 0.0) return 0.0
            return LEAVES.indices.maxOf { abs(got[it] / gotTotal - wanted[it] / wantTotal) }
        }

        /** Every integer the run produced, in one string, so a pin mismatch shows the whole row. */
        fun fingerprint(): String {
            val totals = totals()
            return LEAVES.indices.joinToString(" ") { "${LEAVES[it].value}=${totals[it]}" } +
                " acquires=$acquires extends=$extends completes=$completes nothingEligible=$nothingEligible " +
                "exhaustedAcquires=$exhaustedAcquires exhaustedExtends=$exhaustedExtends inlineOverruns=$inlineOverruns " +
                "filtered=$filteredAcquires seesExtended=$acquiresSeeingExtended wakes=$wakes " +
                "mints=$mints forcedMints=$forcedMints minUnspent=$minUnspent"
        }

        fun report(arm: String): String {
            val totals = totals()
            val all = totals.sum().toDouble()
            val shares = LEAVES.indices.joinToString(" ") { "${LEAVES[it].value}=${fmt(totals[it] / all)}" }
            return "LEASE-FAIRNESS arm=$arm shares[$shares] waitingGps[${idealShares(idealWaiting)}] " +
                "eligibleGps[${idealShares(idealEligible)}] " +
                "overallErrWaiting=${fmt(maxOverallError(idealWaiting))} windowErrWaiting=${fmt(maxWindowError(idealWaiting))} " +
                "overallErrEligible=${fmt(maxOverallError(idealEligible))} windowErrEligible=${fmt(maxWindowError(idealEligible))} " +
                fingerprint()
        }

        private fun idealShares(ideal: Array<DoubleArray>): String {
            val sums = DoubleArray(LEAVES.size) { i -> ideal.sumOf { it[i] } }
            val all = sums.sum()
            return LEAVES.indices.joinToString(" ") { "${LEAVES[it].value}=${fmt(sums[it] / all)}" }
        }
    }

    /** `root → t → {a:3, b:1, c:1}`, every edge active, no supply yet: top-up mints all of it. */
    private fun smallTree(): EntitlementLedger {
        var l = EntitlementLedger.bootstrap(ROOT, emptyMap(), nonce = "genesis")
        val records = listOf(AttachmentRecord(AttachmentId("root>t"), ROOT, LANE, Weight.ONE)) +
            LEAVES.indices.map { i -> AttachmentRecord(AttachmentId("t>${LEAVES[i].value}"), LANE, LEAVES[i], Weight.of(WEIGHTS[i])) }
        for (r in records) {
            l = l.piece(checkNotNull(l.prepare(r)).delta)
            l = l.piece(checkNotNull(l.activate(r.id)).delta)
        }
        return l
    }

    private companion object {
        const val STEPS = 10_000
        const val WINDOW = 500
        const val CAPACITY = 8
        const val MAX_RESERVATION = 5
        const val MAX_ACTUAL = 8
        const val PIN_TOLERANCE = 1e-4
        val NORMAL_SUPPLY = Supply(watermark = CAPACITY * MAX_RESERVATION.toLong(), mintChunk = CAPACITY * MAX_RESERVATION.toLong())
        val STARVED_SUPPLY = Supply(watermark = 2L, mintChunk = 2L)
        val ROOT = GroupId("root")
        val LANE = GroupId("t")
        val LEAVES = listOf(GroupId("a"), GroupId("b"), GroupId("c"))
        val WEIGHTS = listOf(3L, 1L, 1L)
        val SLOT_LABELS = listOf("x", "y")
        val LEAF_LABELS = listOf(setOf("x", "y"), setOf("x"), setOf("y"))

        fun fmt(d: Double): String = (kotlin.math.round(d * 10_000) / 10_000).toString()
    }
}
