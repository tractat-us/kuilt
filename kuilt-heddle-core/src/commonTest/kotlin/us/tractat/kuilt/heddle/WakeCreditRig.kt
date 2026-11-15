package us.tractat.kuilt.heddle

import kotlin.test.assertEquals

/**
 * The deterministic slot rig behind #3238 and #3376: a [LocalHeddle] serving bursty lanes beside
 * one always-backlogged lane, through `acquire`, `extend`, `complete` and `cancel`.
 *
 * Integer time, [WakeConfig.slots] slots, no random draw. One leaf, [B], is always backlogged with
 * jobs of cost 1. Every other lane is closed-loop: a burst of jobs queues, and `think` units after
 * the burst's last job **starts** the next burst queues, so with `think` shorter than a job the
 * lane is observed idle with its lease still open and then wakes while that lease runs. Every slot
 * is acquired with reservation 1, since a slot's reservation is chosen before the pick; when a lane
 * is picked, its lease is extended to the arm's reservation before any other call, so every later
 * pick sees the ledger a caller that reserved it at acquire would have left.
 *
 * Share is **slot time**: the fraction of all slot-time units a lane's leases occupied. That equals
 * the charged share in every arm but the cancel arm, where a cancelled job is charged nothing but
 * still held its slot.
 */
internal object WakeCreditRig {
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
        OVER_LAST,
        UNDER_LAST,
        CANCEL_LAST,

        /** Each estimate is the previous job's actual cost: honest, but noisy when costs vary. */
        NOISE,
    }

    class LaneSpec(val leaf: GroupId, val arm: Arm, val think: Long, val burst: Int, val costs: List<Long>)

    class WakeConfig(
        val ledger: EntitlementLedger,
        val lanes: List<LaneSpec>,
        val slots: Int = 8,
        val horizon: Long,
    )

    class Run(
        private val slotTime: Map<GroupId, Long>,
        private val totalSlotTime: Long,
        /** Engine-observed wakes on the bursty lanes' edges: last observed not demanding, now demanding. */
        val wakes: Int,
        /** Bursts that queued while one of that lane's leases was still open. */
        val wakesWithOpenLease: Int,
    ) {
        fun share(leaf: GroupId): Double = slotTime.getValue(leaf).toDouble() / totalSlotTime
    }

    private class Job(val index: Int)

    private class Running(val lease: LeaseId, val lane: Int, val endsAt: Long, val actual: Long, val cancels: Boolean)

    private class LaneState(val spec: LaneSpec) {
        val queue = ArrayDeque<Job>()
        var nextArrival = 0L
        var jobs = 0
        var slotTime = 0L

        fun cost(index: Int): Long = spec.costs[index % spec.costs.size]
    }

    fun oneLevel(): EntitlementLedger = shapedLedger(ROOT, listOf(edgeOf(ROOT, A), edgeOf(ROOT, B)))

    fun twoLevel(): EntitlementLedger =
        shapedLedger(ROOT, listOf(edgeOf(ROOT, G), edgeOf(ROOT, B), edgeOf(G, A1), edgeOf(G, A2)))

    fun run(config: WakeConfig): Run {
        val h = engineOver(config.ledger, ROOT, supply = SUPPLY, policy = LocalPolicy(defaultReservation = 1L))
        val lanes = config.lanes.map { LaneState(it) }
        val watched = lanes.flatMap { checkNotNull(config.ledger.lineageOf(it.spec.leaf)) }.distinct()
        val slots = arrayOfNulls<Running>(config.slots)
        var bTime = 0L
        var wakes = 0
        var wakesWithOpenLease = 0

        for (t in 0L until config.horizon) {
            for (i in slots.indices) {
                val run = slots[i] ?: continue
                if (run.endsAt != t) continue
                if (run.cancels) {
                    assertEquals(LeaseOutcome.Done(0L, 0L), h.cancel(run.lease))
                } else {
                    h.complete(run.lease, run.actual)
                }
                slots[i] = null
            }
            for ((index, lane) in lanes.withIndex()) {
                if (lane.queue.isEmpty() && t == lane.nextArrival) {
                    repeat(lane.spec.burst) { lane.queue.addLast(Job(lane.jobs++)) }
                    if (slots.any { it != null && it.lane == index }) wakesWithOpenLease++
                }
            }
            for (i in slots.indices) {
                if (slots[i] != null) continue
                val waiting = lanes.filter { it.queue.isNotEmpty() }.mapTo(HashSet()) { it.spec.leaf }.apply { add(B) }
                val before = h.snapshot().allocatorMemory.demandingObserved
                val out = h.acquire(waiting, waiting, startedAt = t, reservation = 1L)
                check(out is AcquireOutcome.Acquired) { "t=$t slot=$i: $out" }
                val after = h.snapshot().allocatorMemory.demandingObserved
                wakes += watched.count { before[it] == false && after[it] == true }
                val lease = out.lease
                val laneIndex = lanes.indexOfFirst { it.spec.leaf == lease.leaf }
                if (laneIndex < 0) {
                    slots[i] = Running(lease.id, lane = -1, endsAt = t + 1L, actual = 1L, cancels = false)
                    bTime += 1L
                    continue
                }
                val lane = lanes[laneIndex]
                val job = lane.queue.removeFirst()
                val emptiesQueue = lane.queue.isEmpty()
                if (emptiesQueue) lane.nextArrival = t + lane.spec.think
                val actual = lane.cost(job.index)
                val r = when (lane.spec.arm) {
                    Arm.HONEST, Arm.CANCEL_LAST -> actual
                    Arm.OVER_LAST -> if (emptiesQueue) 4 * actual else actual
                    Arm.UNDER_LAST -> if (emptiesQueue) maxOf(1L, actual / 4) else actual
                    Arm.NOISE -> lane.cost(job.index + lane.spec.costs.size - 1)
                }
                if (r > 1L) assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, r - 1L))
                val cancels = lane.spec.arm == Arm.CANCEL_LAST && emptiesQueue
                slots[i] = Running(lease.id, laneIndex, endsAt = t + actual, actual = actual, cancels = cancels)
                lane.slotTime += actual
            }
        }
        assertEquals(0, h.forcedMints, "the supply covered every overrun")
        // Slot time is counted at the pick, so trim what runs past the horizon from every lane alike.
        val laneTime = lanes.withIndex().associate { (index, lane) ->
            lane.spec.leaf to lane.slotTime - slots.filterNotNull().filter { it.lane == index }.sumOf { it.endsAt - config.horizon }
        }
        val all = laneTime + (B to bTime - slots.filterNotNull().filter { it.lane < 0 }.sumOf { it.endsAt - config.horizon })
        return Run(all, all.values.sum(), wakes, wakesWithOpenLease)
    }
}
