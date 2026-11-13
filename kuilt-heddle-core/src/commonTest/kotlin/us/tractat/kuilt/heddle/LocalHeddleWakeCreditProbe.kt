package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROBE, not a regression test: does a mis-sized reservation open at the moment of a wake move the
 * §10.6 clamp, and does that show up as service share?
 *
 * The suspected mechanism. A leaf with an open lease and an empty queue is not in `waiting`, so the
 * allocator observes it idle. When its next job queues while that lease is still open, the wake
 * clamp is `max(0, front − vRaw)` with `vRaw` counting the open lease's **reservation**, not its
 * cost, and the offset is max-joined, never revisited. Over-reserving then releases `r − C` at
 * `complete`, walking the leaf back below the front it was clamped to (credit); under-reserving
 * charges `C − r` on top of a clamp that already landed it on the front (penalty).
 *
 * ## The rig
 *
 * Fully deterministic: no random draw, integer time, [Config.slots] slots. `root → {a, b}`, equal
 * weight.
 * - `b` is always backlogged, jobs of cost [B_COST], reserved honestly.
 * - `a` is closed-loop by default: a burst of [Config.burst] jobs of cost [A_COST] queues;
 *   [Config.think] time units after the burst's last job **starts**, the next burst queues. Since
 *   `think < A_COST`, `a` is observed idle (queue empty, lease still open) by `b`'s acquires, then
 *   wakes while that lease is open. With [Config.period] set, bursts instead arrive open-loop, every
 *   `period` units whatever was served.
 * - **Two slots are the vacuous configuration**: `b` then advances on one slot while `a` idles for
 *   less than `A_COST`, so `a`'s in-flight charge always keeps it ahead of the front and the clamp
 *   computes zero in every arm (measured: 0 clamps). The headlines use eight.
 * - Every slot is acquired with reservation [B_COST], because a slot's reservation is chosen before
 *   the pick. When `a` is picked, the lease is immediately [LocalHeddle.extend]ed to the arm's
 *   reservation, before any other call, so every later pick sees exactly the ledger a caller that
 *   reserved `r` at acquire would have left. The honest arm goes through the same extend.
 *
 * Arms, by `a`'s reservation `r` against its cost `C`: honest `r = C`, over `r = 4C`, under
 * `r = C/4` (the overrun charged inline at `complete`), the same two applied **only to the job that
 * empties a's queue** (the lease that straddles the idle edge; every other job honest), and a
 * positive control: honest with a policy `sleeperCredit = 3C`, a deliberately granted wake credit
 * of exactly the over arm's `r − C`, to show the share metric can see one.
 *
 * ## What it found (#3197 review question)
 *
 * The clamp mechanism is real and exact: the stored offset is short by `r − C` per open lease when
 * over-reserving and long by `C − r` when under-reserving (`creditPerWake` = 24·n and −6·n). In
 * isolation (the last-only arms) it is cashable idle credit, the same size as the `sleeperCredit`
 * control. But with **every** job mis-sized the sign inverts: over-reservers lose share and wait
 * longer, under-reservers gain, because the reservation also moves `vRaw` at every pick while the
 * lease is open, and that effect dominates the clamp's.
 *
 * ## What it measures
 *
 * - `share` — `a`'s fraction of committed (actually charged) service.
 * - `gps` — waiting-GPS, as `LocalHeddleFairnessTest` defines it: each completed lease's cost split
 *   by weight among the leaves waiting when it was acquired.
 * - `credit` — at each wake, the offset the clamp would have stored had `vRaw` counted the open
 *   leases at their actual cost, minus the offset it did store. Positive is credit, negative penalty.
 *   The instrument is checked against the engine: before each wake the probe predicts the stored
 *   offset from the formula, and `modelMisses` counts the wakes where the engine disagreed.
 */
class LocalHeddleWakeCreditProbe {

    private enum class Arm(val label: String, val sleeperCredit: Long = 0L) {
        HONEST("honest"),
        OVER("over-4x"),
        UNDER("under-1/4"),
        SLEEPER_CONTROL("honest+sleeperCredit", sleeperCredit = 3 * A_COST),

        /** Over-reserve only the job that empties a's queue: the one whose lease straddles the idle edge. */
        OVER_LAST("over-4x-last-only"),

        /** Under-reserve only the job that empties a's queue. */
        UNDER_LAST("under-1/4-last-only"),
    }

    private fun reservationFor(arm: Arm, emptiesQueue: Boolean): Long = when (arm) {
        Arm.HONEST, Arm.SLEEPER_CONTROL -> A_COST
        Arm.OVER -> 4 * A_COST
        Arm.UNDER -> A_COST / 4
        Arm.OVER_LAST -> if (emptiesQueue) 4 * A_COST else A_COST
        Arm.UNDER_LAST -> if (emptiesQueue) A_COST / 4 else A_COST
    }

    private class Running(val lease: LeaseId, val isA: Boolean, val endsAt: Long, val actual: Long, val reserved: Long, val aWaited: Boolean)

    /** One workload: [slots] concurrent slots, `a` queues [burst] jobs [think] units after its last burst's final job starts. */
    private data class Config(val slots: Int, val think: Long, val burst: Int, val period: Long = 0L) {
        override fun toString(): String = "slots=$slots think=$think burst=$burst period=$period"
    }

    private class Result(
        val arm: Arm,
        val config: Config,
        val aService: Long,
        val bService: Long,
        val gpsA: Double,
        val acquires: Int,
        val aPicks: Int,
        val wakes: Int,
        val clamps: Int,
        val wakesWithOpenA: Int,
        val released: Long,
        val overrun: Long,
        val creditSum: Double,
        val creditMax: Double,
        val creditMin: Double,
        val modelMisses: Int,
        val aWaitSum: Long,
    ) {
        val total: Long get() = aService + bService
        val share: Double get() = aService.toDouble() / total
        val gps: Double get() = gpsA / total

        fun row(honest: Result?): String =
            "WAKE-CREDIT $config arm=${arm.label} share=${fmt(share)} gps=${fmt(gps)} errVsGps=${fmt(share - gps)} " +
                "devVsHonest=${honest?.let { fmt(share - it.share) } ?: "-"} a=$aService b=$bService acquires=$acquires " +
                "aPicks=$aPicks wakes=$wakes clamps=$clamps wakesWithOpenA=$wakesWithOpenA released=$released " +
                "overrun=$overrun creditSum=${fmt(creditSum)} creditPerWake=${fmt(creditSum / maxOf(1, wakes))} " +
                "creditMax=${fmt(creditMax)} creditMin=${fmt(creditMin)} modelMisses=$modelMisses meanWaitA=${fmt(aWaitSum.toDouble() / maxOf(1, aPicks))}"
    }

    private fun run(arm: Arm, config: Config): Result {
        val burst = config.burst
        val h = engineOver(
            shapedLedger(ROOT, listOf(edgeOf(ROOT, A), edgeOf(ROOT, B))),
            ROOT,
            supply = SUPPLY,
            policy = LocalPolicy(defaultReservation = B_COST, sleeperCredit = arm.sleeperCredit),
        )
        val slots = arrayOfNulls<Running>(config.slots)
        val queueA = ArrayDeque<Long>().apply { repeat(burst) { addLast(0L) } }
        var aWaitSum = 0L
        var nextArrival = -1L
        var aService = 0L
        var bService = 0L
        var gpsA = 0.0
        var acquires = 0
        var aPicks = 0
        var wakes = 0
        var clamps = 0
        var wakesWithOpenA = 0
        var released = 0L
        var overrun = 0L
        var creditSum = Rational.ZERO
        var creditMax = Rational.ZERO
        var creditMin = Rational.ZERO
        var modelMisses = 0

        for (t in 0L until HORIZON) {
            // 1. Completions due now, in slot order.
            for (i in slots.indices) {
                val run = slots[i] ?: continue
                if (run.endsAt != t) continue
                val expectedOverrun = maxOf(0L, run.actual - run.reserved)
                assertEquals(LeaseOutcome.Done(run.actual, expectedOverrun), h.complete(run.lease, run.actual))
                released += maxOf(0L, run.reserved - run.actual)
                overrun += expectedOverrun
                if (run.isA) aService += run.actual else bService += run.actual
                if (run.aWaited) gpsA += run.actual / 2.0
                slots[i] = null
            }
            // 2. a's next burst.
            if (config.period > 0L && t > 0L && t % config.period == 0L) repeat(burst) { queueA.addLast(t) }
            if (config.period == 0L && t == nextArrival) {
                repeat(burst) { queueA.addLast(t) }
                nextArrival = -1L
            }
            // 3. Fill every free slot.
            for (i in slots.indices) {
                if (slots[i] != null) continue
                val aWaiting = queueA.isNotEmpty()
                val waiting = if (aWaiting) setOf(A, B) else setOf(B)
                val before = h.snapshot()
                val openA = slots.filterNotNull().filter { it.isA }
                val inflation = openA.sumOf { it.reserved - it.actual }
                val out = h.acquire(waiting, waiting, startedAt = t, reservation = B_COST)
                check(out is AcquireOutcome.Acquired) { "t=$t slot=$i: $out" }
                acquires++
                val after = h.snapshot()

                val wasIdle = before.allocatorMemory.demandingObserved[A_EDGE] == false
                val nowDemanding = after.allocatorMemory.demandingObserved[A_EDGE] == true
                if (wasIdle && nowDemanding) {
                    wakes++
                    if (openA.isNotEmpty()) wakesWithOpenA++
                    val carried = before.allocatorMemory.wakeOffsets[A_EDGE] ?: Rational.ZERO
                    val stored = after.allocatorMemory.wakeOffsets[A_EDGE] ?: Rational.ZERO
                    if (stored > carried) clamps++
                    val front = vRaw(before.ledger, B_EDGE) + (before.allocatorMemory.wakeOffsets[B_EDGE] ?: Rational.ZERO)
                    val vA = vRaw(before.ledger, A_EDGE)
                    val credit = Rational.of(arm.sleeperCredit) // weight 1: credit in virtual time is the raw amount
                    val modelled = Rational.max(carried, Rational.max(Rational.ZERO, front - vA - credit))
                    if (modelled != stored) modelMisses++
                    val atCost = Rational.max(carried, Rational.max(Rational.ZERO, front - (vA - Rational.of(inflation)) - credit))
                    val delta = atCost - stored
                    creditSum += delta
                    if (delta > creditMax) creditMax = delta
                    if (delta < creditMin) creditMin = delta
                }

                val lease = out.lease
                if (lease.leaf == A) {
                    aPicks++
                    aWaitSum += t - queueA.removeFirst()
                    if (queueA.isEmpty()) nextArrival = t + config.think
                    val r = reservationFor(arm, emptiesQueue = queueA.isEmpty())
                    check(r >= B_COST) { "the rig sizes a's reservation by extending a B_COST slot" }
                    if (r > B_COST) assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, r - B_COST))
                    slots[i] = Running(lease.id, isA = true, endsAt = t + A_COST, actual = A_COST, reserved = r, aWaited = aWaiting)
                } else {
                    slots[i] = Running(lease.id, isA = false, endsAt = t + B_COST, actual = B_COST, reserved = B_COST, aWaited = aWaiting)
                }
            }
        }
        assertEquals(0, h.forcedMints, "the supply covered every overrun")
        return Result(
            arm, config, aService, bService, gpsA, acquires, aPicks, wakes, clamps, wakesWithOpenA, released, overrun,
            creditSum.toDouble(), creditMax.toDouble(), creditMin.toDouble(), modelMisses, aWaitSum,
        )
    }

    private fun runAll(config: Config): Map<Arm, Result> {
        val results = Arm.entries.associateWith { run(it, config) }
        val honest = results.getValue(Arm.HONEST)
        for (arm in Arm.entries) println(results.getValue(arm).row(if (arm == Arm.HONEST) null else honest))
        return results
    }

    /**
     * The rig, per arm: wakes happen with a's lease open, the clamp fires, the instrument reads the
     * engine, and the share metric can see a deliberately granted wake credit of the size in question.
     */
    private fun assertRig(results: Map<Arm, Result>) {
        val honest = results.getValue(Arm.HONEST)
        val over = results.getValue(Arm.OVER)
        val under = results.getValue(Arm.UNDER)
        val control = results.getValue(Arm.SLEEPER_CONTROL)
        assertAll(
            *Arm.entries.flatMap { arm ->
                val res = results.getValue(arm)
                listOf(
                    { assertTrue(res.wakes > 100, "${arm.label}: a woke ${res.wakes} times") },
                    { assertTrue(res.wakesWithOpenA * 10 >= res.wakes * 9, "${arm.label}: ${res.wakesWithOpenA} of ${res.wakes} wakes had a's lease open") },
                    { assertEquals(0, res.modelMisses, "${arm.label}: the probe's clamp formula matches the engine at every wake") },
                )
            }.toTypedArray(),
            { assertTrue(honest.clamps > 100, "the honest clamp fired: ${honest.clamps}") },
            { assertTrue(under.clamps > 100, "the under clamp fired: ${under.clamps}") },
            { assertEquals(0.0, honest.creditSum, "positive control on the instrument: an honest reservation has nothing to mis-count") },
            { assertEquals(0L, honest.released + honest.overrun, "the honest arm neither releases nor overruns") },
            { assertTrue(over.released > 0L, "the over arm released: ${over.released}") },
            { assertTrue(under.overrun > 0L, "the under arm overran: ${under.overrun}") },
            { assertTrue(over.creditSum > 0.0, "the clamp under-counts the over arm's offset: ${over.creditSum}") },
            { assertTrue(under.creditSum < 0.0, "the clamp over-counts the under arm's offset: ${under.creditSum}") },
            {
                assertTrue(
                    control.share - honest.share > BAR,
                    "positive control on the metric: sleeperCredit=${Arm.SLEEPER_CONTROL.sleeperCredit} moves a's share past the bar, " +
                        "${fmt(control.share)} vs ${fmt(honest.share)}",
                )
            },
        )
    }

    @Test
    fun headlineRigFired() {
        for (c in HEADLINES) assertRig(runAll(c))
    }

    /**
     * The verdict, **expected to red**: every mis-sized arm's share within the
     * `twoLevelTreeWithConcurrentLeases` bar (0.03) of the honest arm's, on the headline workloads.
     * The red's shape is the measurement: which arms, which sign.
     */
    @Test
    fun misSizedReservationsStayWithinTheConcurrentLeaseBar() {
        val rows = HEADLINES.flatMap { c ->
            val results = runAll(c)
            val honest = results.getValue(Arm.HONEST)
            listOf(Arm.OVER, Arm.UNDER, Arm.OVER_LAST, Arm.UNDER_LAST).map { arm ->
                val res = results.getValue(arm)
                val check: () -> Unit = {
                    assertTrue(
                        abs(res.share - honest.share) <= BAR,
                        "$c ${arm.label}: share ${fmt(res.share)} vs honest ${fmt(honest.share)}, deviation ${fmt(res.share - honest.share)}",
                    )
                }
                check
            }
        }
        assertAll(*rows.toTypedArray())
    }

    /** Exploration only: print every arm across a grid of workloads. Asserts nothing but the instrument. */
    @Test
    fun sweep() {
        val misses = ArrayList<String>()
        for (slots in listOf(2, 4, 8)) for (think in listOf(2L, 4L, 6L, 7L)) for (burst in listOf(1, 4, 16, 64)) {
            val results = runAll(Config(slots, think, burst))
            for ((arm, res) in results) if (res.modelMisses != 0) misses += "$arm ${res.config}: ${res.modelMisses}"
        }
        assertEquals(emptyList(), misses, "the probe's clamp formula matched the engine at every wake")
    }

    /** Exploration only: open-loop arrivals, where share is pinned by offered load and credit can only show as wait. */
    @Test
    fun openLoopSweep() {
        val misses = ArrayList<String>()
        for (c in listOf(Config(8, 0L, 1, 3L), Config(8, 0L, 1, 5L), Config(8, 0L, 2, 6L), Config(8, 0L, 2, 7L), Config(16, 0L, 4, 7L), Config(16, 0L, 3, 6L))) {
            val results = runAll(c)
            for ((arm, res) in results) if (res.modelMisses != 0) misses += "$arm ${res.config}: ${res.modelMisses}"
        }
        assertEquals(emptyList(), misses, "the probe's clamp formula matched the engine at every wake")
    }

    private companion object {
        const val B_COST = 1L
        const val A_COST = 8L
        const val HORIZON = 20_000L
        const val SUPPLY = 1_000_000_000L
        const val BAR = 0.03

        /** Closed loop, eight slots: the two workloads with the largest deviations in the sweep. */
        val HEADLINES = listOf(Config(slots = 8, think = 7L, burst = 4), Config(slots = 8, think = 4L, burst = 4))
        val ROOT = GroupId("root")
        val A = GroupId("a")
        val B = GroupId("b")
        val A_EDGE = AttachmentId("root>a")
        val B_EDGE = AttachmentId("root>b")

        /** Raw virtual service at weight 1: gross minus returned. */
        fun vRaw(l: EntitlementLedger, edge: AttachmentId): Rational {
            val gross = checkNotNull(l.grossVirtualService(edge)) { "no gross for ${edge.value}" }
            val returned = l.activeChildren(ROOT).single { it.attachment == edge }.returned
            return gross - Rational.of(returned)
        }

        fun Rational.toDouble(): Double = numerator.toDouble() / denominator

        fun fmt(d: Double): String = (kotlin.math.round(d * 10_000) / 10_000).toString()
    }
}
