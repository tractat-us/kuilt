package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.WakeCreditRig.A
import us.tractat.kuilt.heddle.WakeCreditRig.A1
import us.tractat.kuilt.heddle.WakeCreditRig.A2
import us.tractat.kuilt.heddle.WakeCreditRig.Arm
import us.tractat.kuilt.heddle.WakeCreditRig.LaneSpec
import us.tractat.kuilt.heddle.WakeCreditRig.Run
import us.tractat.kuilt.heddle.WakeCreditRig.WakeConfig
import us.tractat.kuilt.heddle.WakeCreditRig.fmt
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mis-sized reservation on the lease that straddles a wake gains its lane nothing (#3238), on the
 * deterministic slot rig ([WakeCreditRig]), measured as slot-time share.
 *
 * - **Honest**: unchanged from before the fix, row for row, clamps included.
 * - **Over-last** and **cancel-last**: over-reserving 4×, or cancelling, only the job that empties
 *   the queue, whose lease is open when the lane wakes. Within [BAR] of honest, one level and two.
 *   Before the fix: +0.136 and +0.036 at think 7, +0.056 and +0.056 at think 4.
 * - **Honest noise**: each estimate is the previous job's cost. Exactly the share it had before the
 *   fix: the leases that release were opened after the clamp, so the watermark leaves them alone.
 * - **The honest sibling** under a group: no worse than before the fix plus [SIBLING_SLACK]. Its
 *   remaining loss at think 4, about −0.035 against honest, is not a release handing back credit:
 *   while the over-reserving sibling's lease is open it inflates the front the honest one is clamped
 *   to. That in-flight effect belongs to #3239.
 * - **A long lease banks no idle credit**: a regression guard against treating a lane with work in
 *   flight as never idle, which hands it every slot when it turns busy.
 *
 * Every rig asserts it fired: bursts queued with the lane's lease open, the clamp bound, a release
 * was added back. The rig draws nothing at random, so every target walks the same trajectory; the
 * rows are identical on JVM and macosArm64. The uniform under- and over-reserve arms are printed,
 * not asserted: they are #3239's.
 */
class LocalHeddleWakeCreditTest {

    private fun oneLevel(think: Long, arm: Arm, costs: List<Long> = listOf(8L), burst: Int = 4, sleeperCredit: Long = 0L): Run =
        WakeCreditRig.run(
            WakeConfig(
                "1L think=$think burst=$burst costs=$costs a=$arm sleeper=$sleeperCredit",
                WakeCreditRig.oneLevel(),
                listOf(LaneSpec(A, arm, think, burst, costs)),
                sleeperCredit = sleeperCredit,
                horizon = HORIZON,
            ),
        ).also { println(it.row()) }

    private fun twoLevel(thinkA1: Long, arm: Arm): Run =
        WakeCreditRig.run(
            WakeConfig(
                "2L thinkA1=$thinkA1 a1=$arm a2=HONEST",
                WakeCreditRig.twoLevel(),
                listOf(LaneSpec(A1, arm, thinkA1, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, 11L - thinkA1, 4, listOf(8L))),
                horizon = HORIZON,
            ),
        ).also { println(it.row()) }

    private fun firedAtTheWake(run: Run, leaf: GroupId): () -> Unit = {
        val lane = run.lanes.getValue(leaf)
        assertTrue(lane.bursts > 100, "${run.label}: ${lane.bursts} bursts")
        assertTrue(lane.wakesWithOpenLease * 10 >= lane.bursts * 9, "${run.label}: ${lane.wakesWithOpenLease} of ${lane.bursts} bursts queued with a lease open")
    }

    private fun within(run: Run, honest: Run, leaf: GroupId): () -> Unit = {
        val dev = run.share(leaf) - honest.share(leaf)
        assertTrue(abs(dev) <= BAR, "${run.label}: ${leaf.value} ${fmt(run.share(leaf))} vs honest ${fmt(honest.share(leaf))}, ${fmt(dev)}")
    }

    @Test
    fun anHonestLaneIsUnchanged() {
        val seven = oneLevel(7L, Arm.HONEST)
        val four = oneLevel(4L, Arm.HONEST)
        val control = oneLevel(7L, Arm.HONEST, sleeperCredit = 24L)
        assertAll(
            { assertEquals("0.3637", fmt(seven.share(A)), "think 7, as before the fix") },
            { assertEquals(363, seven.clamps, "think 7 clamps, as before the fix") },
            { assertEquals("0.4443", fmt(four.share(A)), "think 4, as before the fix") },
            { assertEquals(444, four.clamps, "think 4 clamps, as before the fix") },
            { assertEquals(0, seven.compensations + four.compensations, "an honest lease releases nothing, so nothing is added back") },
            firedAtTheWake(seven, A),
            firedAtTheWake(four, A),
            { assertTrue(control.share(A) - seven.share(A) > BAR, "the metric sees a granted credit: sleeperCredit 3C gives ${fmt(control.share(A))}") },
        )
    }

    @Test
    fun overReservingTheStraddlingLeaseGainsNothing() {
        val checks = listOf(7L, 4L).flatMap { think ->
            val honest = oneLevel(think, Arm.HONEST)
            val over = oneLevel(think, Arm.OVER_LAST)
            listOf(
                within(over, honest, A),
                firedAtTheWake(over, A),
                { assertTrue(over.compensations > 100, "${over.label}: ${over.compensations} releases added back") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun cancellingTheStraddlingLeaseGainsNothing() {
        val checks = listOf(7L, 4L).flatMap { think ->
            val honest = oneLevel(think, Arm.HONEST)
            val cancel = oneLevel(think, Arm.CANCEL_LAST)
            listOf(
                within(cancel, honest, A),
                firedAtTheWake(cancel, A),
                { assertTrue(cancel.cancelled > 100, "${cancel.label}: ${cancel.cancelled} cancels") },
                { assertTrue(cancel.compensations > 100, "${cancel.label}: ${cancel.compensations} cancels added back") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /** Before the fix, these arms read exactly these shares; the fix must not move them. */
    @Test
    fun noisyHonestEstimatesPayNothing() {
        val today = mapOf(
            Triple(7L, listOf(4L, 4L, 4L, 20L), Arm.NOISE) to "0.2922",
            Triple(7L, listOf(2L, 14L), Arm.NOISE) to "0.3331",
            Triple(4L, listOf(4L, 4L, 4L, 20L), Arm.NOISE) to "0.4279",
            Triple(4L, listOf(2L, 14L), Arm.NOISE) to "0.4282",
        )
        val checks = today.flatMap { (key, share) ->
            val (think, costs, arm) = key
            val run = oneLevel(think, arm, costs, burst = 3)
            listOf(
                { assertEquals(share, fmt(run.share(A)), "${run.label}: the share it had before the fix") },
                { assertTrue(run.released > 0L && run.overrun > 0L, "${run.label}: the estimates missed both ways") },
                firedAtTheWake(run, A),
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun aStraddlingLeaseUnderAGroupGainsNothing() {
        // The honest sibling's share in each arm before the fix, which it may not fall more than SIBLING_SLACK below.
        val siblingToday = mapOf((7L to Arm.OVER_LAST) to 0.1668, (4L to Arm.OVER_LAST) to 0.1668, (7L to Arm.CANCEL_LAST) to 0.2668, (4L to Arm.CANCEL_LAST) to 0.1819)
        val checks = listOf(7L, 4L).flatMap { think ->
            val honest = twoLevel(think, Arm.HONEST)
            listOf(Arm.OVER_LAST, Arm.CANCEL_LAST).flatMap { arm ->
                val run = twoLevel(think, arm)
                val floor = siblingToday.getValue(think to arm) - SIBLING_SLACK
                listOf(
                    within(run, honest, A1),
                    firedAtTheWake(run, A1),
                    { assertTrue(run.compensations > 50, "${run.label}: ${run.compensations} releases added back") },
                    { assertTrue(run.share(A2) >= floor, "${run.label}: the honest sibling a2 ${fmt(run.share(A2))}, floor ${fmt(floor)}") },
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /** #3239's arms, recorded for its redesign. Asserts only that each ran. */
    @Test
    fun uniformMisReservationIsRecorded() {
        val runs = listOf(7L, 4L).flatMap { think -> listOf(Arm.OVER, Arm.UNDER, Arm.UNDER_LAST).map { oneLevel(think, it) } } +
            listOf(7L, 4L).flatMap { think -> listOf(Arm.OVER, Arm.UNDER).map { twoLevel(think, it) } }
        assertEquals(10, runs.size)
    }

    /**
     * An honest lane that always has one long lease open: each cycle one job of 400 and seven of 1,
     * then a think of 300, so its next burst queues while the 400 still runs. From [TURNS_BUSY] it is
     * backlogged with jobs of 1. Over the next 1,000 ticks it must get about its half and never take
     * the pool: a rule that let a lane with work in flight skip the clamp handed it every slot here
     * (measured: 7,521 picks in a row).
     */
    @Test
    fun aLongLeaseBanksNoIdleCredit() {
        val a = WakeCreditRig.A
        val b = WakeCreditRig.B
        val h = engineOver(WakeCreditRig.oneLevel(), WakeCreditRig.ROOT, supply = 1_000_000_000L, policy = LocalPolicy(defaultReservation = 1L))
        val slots = arrayOfNulls<Pair<Lease, Long>>(8)
        val queue = ArrayDeque<Long>()
        var nextArrival = 0L
        var job = 0
        var burstsWithLongLeaseOpen = 0
        var bursts = 0
        var aPicks = 0
        var run = 0
        var longest = 0
        var picksAfter = 0
        for (t in 0L until TURNS_BUSY + 1_000L) {
            for (i in slots.indices) {
                val s = slots[i] ?: continue
                if (s.second == t) {
                    h.complete(s.first.id, h.snapshot().leases.first { it.id == s.first.id }.reserved)
                    slots[i] = null
                }
            }
            val busy = t >= TURNS_BUSY
            if (!busy && queue.isEmpty() && t == nextArrival) {
                repeat(8) { queue.addLast(if (job++ % 8 == 0) 400L else 1L) }
                bursts++
                if (slots.any { it != null && it.first.leaf == a && it.first.reserved > 1L }) burstsWithLongLeaseOpen++
            }
            for (i in slots.indices) {
                if (slots[i] != null) continue
                val waiting = if (busy || queue.isNotEmpty()) setOf(a, b) else setOf(b)
                var lease = (h.acquire(waiting, waiting, startedAt = t, reservation = 1L) as AcquireOutcome.Acquired).lease
                var cost = 1L
                if (lease.leaf == a && !busy) {
                    cost = queue.removeFirst()
                    if (queue.isEmpty()) nextArrival = t + 300L
                    if (cost > 1L) {
                        h.extend(lease.id, cost - 1L)
                        lease = lease.copy(reserved = cost)
                    }
                }
                slots[i] = lease to t + cost
                if (busy) {
                    picksAfter++
                    if (lease.leaf == a) {
                        aPicks++
                        run++
                        longest = maxOf(longest, run)
                    } else {
                        run = 0
                    }
                }
            }
        }
        val share = aPicks.toDouble() / picksAfter
        println("LONG-LEASE share=${fmt(share)} longestRun=$longest picks=$picksAfter bursts=$bursts withLongLeaseOpen=$burstsWithLongLeaseOpen")
        assertAll(
            { assertTrue(bursts > 10 && burstsWithLongLeaseOpen * 10 >= (bursts - 1) * 9, "the rig: $burstsWithLongLeaseOpen of $bursts bursts queued with the long lease open") },
            { assertTrue(abs(share - 0.5) <= BAR, "a's share once busy: ${fmt(share)}") },
            { assertTrue(longest <= 8, "a's longest run of picks once busy: $longest") },
        )
    }

    private companion object {
        /** The concurrent-lease bar (`twoLevelTreeWithConcurrentLeases`). */
        const val BAR = 0.03

        /** How much below its pre-fix share the honest sibling under a group may fall. */
        const val SIBLING_SLACK = 0.01

        /** Short enough for a debug Kotlin/Native binary; the shares at 20,000 agree to within 0.001. */
        const val HORIZON = 4_000L

        const val TURNS_BUSY = 6_000L
    }
}
