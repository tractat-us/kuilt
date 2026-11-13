package us.tractat.kuilt.heddle

import kotlin.test.assertEquals

/**
 * The deterministic slot rig behind `LocalHeddleWakeCreditTest` (#3238), ported from the
 * `probe-wake-credit` branch's probe and widened to more than one bursty lane and a two-level tree.
 *
 * Integer time, [WakeConfig.slots] slots, no random draw. One leaf, [WakeCreditRig.B], is always
 * backlogged with jobs of cost 1. Every other lane is closed-loop: a burst of jobs queues, and
 * `think` units after the burst's last job **starts** the next burst queues, so with `think`
 * shorter than a job the lane is observed idle with its lease still open and then wakes while that
 * lease runs. Every slot is acquired with reservation 1, since a slot's reservation is chosen
 * before the pick; when a lane is picked, its lease is extended to the arm's reservation before
 * any other call, so every later pick sees the ledger a caller that reserved it at acquire would
 * have left.
 *
 * Share is **slot time**: the fraction of all slot-time units a lane's leases occupied. That equals
 * the charged share in every arm but the cancel arm, where a cancelled job is charged nothing but
 * still held its slot.
 */
internal object WakeCreditRig {
    const val HORIZON: Long = 20_000L
    private const val SUPPLY: Long = 1_000_000_000L

    val ROOT = GroupId("root")
    val A = GroupId("a")
    val B = GroupId("b")
    val G = GroupId("g")
    val A1 = GroupId("a1")
    val A2 = GroupId("a2")

    /** How a lane sizes one job's reservation, and whether it cancels it. */
    enum class Arm {
        HONEST,
        OVER,
        UNDER,
        OVER_LAST,
        UNDER_LAST,
        CANCEL_LAST,

        /** Each estimate is the previous job's actual cost: honest, but noisy when costs vary. */
        NOISE,
    }

    class LaneSpec(val leaf: GroupId, val arm: Arm, val think: Long, val burst: Int, val costs: List<Long>, val firstArrival: Long = 0L)

    class WakeConfig(
        val label: String,
        val ledger: EntitlementLedger,
        val lanes: List<LaneSpec>,
        val slots: Int = 8,
        val sleeperCredit: Long = 0L,
        val horizon: Long = HORIZON,
    )

    class LaneResult(
        val leaf: GroupId,
        val slotTime: Long,
        val picks: Int,
        val waitSum: Long,
        /** Bursts that queued while one of this lane's leases was still open. */
        val wakesWithOpenLease: Int,
        val bursts: Int,
    )

    class Run(
        val label: String,
        val lanes: Map<GroupId, LaneResult>,
        val totalSlotTime: Long,
        /** Acquires across which a wake offset on one of the bursty lanes' edges rose: a clamp that bound. */
        val clamps: Int,
        /** Engine-observed wakes on those edges: last observed not demanding, now demanding. */
        val wakes: Int,
        /** Completes and cancels across which a wake offset rose: a release was added back. */
        val compensations: Int,
        val released: Long,
        val overrun: Long,
        val cancelled: Int,
    ) {
        fun share(leaf: GroupId): Double = lanes.getValue(leaf).slotTime.toDouble() / totalSlotTime

        fun row(): String =
            "WAKE-CREDIT $label " + lanes.values.joinToString(" ") { "${it.leaf.value}=${fmt(share(it.leaf))}" } +
                " clamps=$clamps wakes=$wakes compensations=$compensations released=$released overrun=$overrun cancelled=$cancelled " +
                lanes.values.joinToString(" ") { "${it.leaf.value}:openWakes=${it.wakesWithOpenLease}/${it.bursts}" }
    }

    private class Job(val queuedAt: Long, val index: Int)

    private class Running(
        val lease: LeaseId,
        val lane: Int,
        val endsAt: Long,
        val actual: Long,
        val reserved: Long,
        val cancels: Boolean,
    )

    private class LaneState(val spec: LaneSpec) {
        val queue = ArrayDeque<Job>()
        var nextArrival = spec.firstArrival
        var jobs = 0
        var slotTime = 0L
        var picks = 0
        var waitSum = 0L
        var bursts = 0
        var wakesWithOpenLease = 0

        fun cost(index: Int): Long = spec.costs[index % spec.costs.size]
    }

    fun oneLevel(): EntitlementLedger = shapedLedger(ROOT, listOf(edgeOf(ROOT, A), edgeOf(ROOT, B)))

    fun twoLevel(): EntitlementLedger =
        shapedLedger(ROOT, listOf(edgeOf(ROOT, G), edgeOf(ROOT, B), edgeOf(G, A1), edgeOf(G, A2)))

    fun run(config: WakeConfig): Run {
        val h = engineOver(
            config.ledger,
            ROOT,
            supply = SUPPLY,
            policy = LocalPolicy(defaultReservation = 1L, sleeperCredit = config.sleeperCredit),
        )
        val lanes = config.lanes.map { LaneState(it) }
        val watched = lanes.flatMap { checkNotNull(config.ledger.lineageOf(it.spec.leaf)) }.distinct()
        val slots = arrayOfNulls<Running>(config.slots)
        var bTime = 0L
        var clamps = 0
        var wakes = 0
        var compensations = 0
        var released = 0L
        var overrun = 0L
        var cancelled = 0

        fun offsets(): Map<AttachmentId, Rational> = h.snapshot().allocatorMemory.wakeOffsets
        fun rose(before: Map<AttachmentId, Rational>, after: Map<AttachmentId, Rational>): Boolean =
            watched.any { (after[it] ?: Rational.ZERO) > (before[it] ?: Rational.ZERO) }

        for (t in 0L until config.horizon) {
            for (i in slots.indices) {
                val run = slots[i] ?: continue
                if (run.endsAt != t) continue
                val before = offsets()
                if (run.cancels) {
                    assertEquals(LeaseOutcome.Done(0L, 0L), h.cancel(run.lease))
                    released += run.reserved
                    cancelled++
                } else {
                    val expectedOverrun = maxOf(0L, run.actual - run.reserved)
                    assertEquals(LeaseOutcome.Done(run.actual, expectedOverrun), h.complete(run.lease, run.actual))
                    released += maxOf(0L, run.reserved - run.actual)
                    overrun += expectedOverrun
                }
                if (rose(before, offsets())) compensations++
                slots[i] = null
            }
            for ((index, lane) in lanes.withIndex()) {
                if (lane.queue.isEmpty() && t == lane.nextArrival) {
                    repeat(lane.spec.burst) { lane.queue.addLast(Job(t, lane.jobs++)) }
                    lane.bursts++
                    if (slots.any { it != null && it.lane == index }) lane.wakesWithOpenLease++
                }
            }
            for (i in slots.indices) {
                if (slots[i] != null) continue
                val waiting = lanes.filter { it.queue.isNotEmpty() }.mapTo(HashSet()) { it.spec.leaf }.apply { add(B) }
                val before = h.snapshot()
                val out = h.acquire(waiting, waiting, startedAt = t, reservation = 1L)
                check(out is AcquireOutcome.Acquired) { "t=$t slot=$i: $out" }
                val after = h.snapshot()
                if (rose(before.allocatorMemory.wakeOffsets, after.allocatorMemory.wakeOffsets)) clamps++
                wakes += watched.count { before.allocatorMemory.demandingObserved[it] == false && after.allocatorMemory.demandingObserved[it] == true }
                val lease = out.lease
                val laneIndex = lanes.indexOfFirst { it.spec.leaf == lease.leaf }
                if (laneIndex < 0) {
                    slots[i] = Running(lease.id, lane = -1, endsAt = t + 1L, actual = 1L, reserved = 1L, cancels = false)
                    bTime += 1L
                    continue
                }
                val lane = lanes[laneIndex]
                val job = lane.queue.removeFirst()
                lane.picks++
                lane.waitSum += t - job.queuedAt
                val emptiesQueue = lane.queue.isEmpty()
                if (emptiesQueue) lane.nextArrival = t + lane.spec.think
                val actual = lane.cost(job.index)
                val r = when (lane.spec.arm) {
                    Arm.HONEST, Arm.CANCEL_LAST -> actual
                    Arm.OVER -> 4 * actual
                    Arm.UNDER -> maxOf(1L, actual / 4)
                    Arm.OVER_LAST -> if (emptiesQueue) 4 * actual else actual
                    Arm.UNDER_LAST -> if (emptiesQueue) maxOf(1L, actual / 4) else actual
                    Arm.NOISE -> lane.cost(job.index + lane.spec.costs.size - 1)
                }
                if (r > 1L) assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, r - 1L))
                val cancels = lane.spec.arm == Arm.CANCEL_LAST && emptiesQueue
                slots[i] = Running(lease.id, laneIndex, endsAt = t + actual, actual = actual, reserved = r, cancels = cancels)
                lane.slotTime += actual
            }
        }
        assertEquals(0, h.forcedMints, "the supply covered every overrun")
        // Slot time is counted at the pick, so trim what runs past the horizon from every lane alike.
        val laneResults = lanes.associate { lane ->
            val past = slots.filterNotNull().filter { it.lane == lanes.indexOf(lane) }.sumOf { it.endsAt - config.horizon }
            lane.spec.leaf to LaneResult(lane.spec.leaf, lane.slotTime - past, lane.picks, lane.waitSum, lane.wakesWithOpenLease, lane.bursts)
        }
        val bPast = slots.filterNotNull().filter { it.lane < 0 }.sumOf { it.endsAt - config.horizon }
        val bResult = LaneResult(B, bTime - bPast, 0, 0L, 0, 0)
        val all = laneResults + (B to bResult)
        return Run(
            config.label, all, totalSlotTime = all.values.sumOf { it.slotTime },
            clamps, wakes, compensations, released, overrun, cancelled,
        )
    }

    fun fmt(d: Double): String = (kotlin.math.round(d * 10_000) / 10_000).toString()
}
