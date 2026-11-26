package us.tractat.kuilt.heddle

import kotlin.test.assertEquals

/**
 * The slot rig behind #3239's ensemble: a [LocalHeddle] serving closed-loop bursty lanes beside one
 * always-backlogged lane, [B], with each bursty lane sizing its reservations by an [Estimate].
 *
 * Integer time, [SLOTS] slots, [HORIZON] ticks, no random draw. [B]'s jobs cost 1. A bursty lane
 * queues [Lane.burst] jobs of [Lane.cost] at [Lane.start], and `think` units after the pick that
 * empties its queue it queues the next burst. Every slot is acquired at reservation 1, since a
 * slot's reservation is chosen before the pick, and a picked lane extends its lease to the
 * estimate before any other call. Every lease completes at its real cost: there is no cancel here.
 *
 * Share is **slot time**, the fraction of all slot-time units a lane's leases held. With no cancel
 * that is also the charged share.
 *
 * [HORIZON] is 1,000 ticks, not the 4,000 of the design note's scratch rig, because Kotlin/Native
 * ran this rig about 23 times slower than the JVM (604 s against 26 s for the suite, on a loaded box). Over the ensembles in [ReservationLeanTest]
 * the shorter run moves no arm's mean by more than 0.002, nor any extreme by more than 0.0031, on the JVM.
 */
internal object ReservationLeanRig {
    const val SLOTS: Int = 8
    const val HORIZON: Long = 1_000L
    private const val SUPPLY: Long = 1_000_000_000L

    val ROOT = GroupId("root")
    val A = GroupId("a")
    val B = GroupId("b")
    val G = GroupId("g")
    val A1 = GroupId("a1")
    val A2 = GroupId("a2")

    /** How a lane sizes each job's reservation. */
    enum class Estimate {
        /** The real cost. */
        HONEST,

        /** A quarter of the cost, at least 1: every job overruns. */
        QUARTER,

        /** Four times the cost: every job refunds three quarters of its reservation. */
        FOUR_TIMES,

        /** Four times the cost on the job that empties the queue, so the refund lands in the gap. */
        FOUR_TIMES_ON_LAST,

        /** 1, extended by 1 each time the job reaches what it holds: it never refunds and never overruns. */
        ONE_THEN_EXTEND,
    }

    class Lane(val leaf: GroupId, val estimate: Estimate, val think: Long, val burst: Int, val cost: Long = 8L, val start: Long = 0L)

    class Run(private val slotTime: Map<GroupId, Long>, private val total: Long) {
        fun share(leaf: GroupId): Double = slotTime.getValue(leaf).toDouble() / total

        /** The slot-time units [leaf]'s leases held: the exact integer [share] divides. */
        fun slotTime(leaf: GroupId): Long = slotTime.getValue(leaf)
    }

    private class Running(val lease: LeaseId, val lane: Int, val start: Long, val endsAt: Long, val cost: Long, var reserved: Long)

    private class LaneState(val spec: Lane) {
        var queued = 0
        var nextArrival = spec.start
        var slotTime = 0L
        var reported = 0L
    }

    fun oneLevel(): EntitlementLedger = shapedLedger(ROOT, listOf(edgeOf(ROOT, A), edgeOf(ROOT, B)))

    fun twoLevel(): EntitlementLedger =
        shapedLedger(ROOT, listOf(edgeOf(ROOT, G), edgeOf(ROOT, B), edgeOf(G, A1), edgeOf(G, A2)))

    /**
     * Run [lanes] over [ledger]. Before returning it asserts the property the contract names: every
     * leaf's spend, read off its edge, is the sum of the costs `complete` was given for it, whatever
     * was reserved.
     */
    fun run(ledger: EntitlementLedger, lanes: List<Lane>): Run {
        val h = engineOver(ledger, ROOT, supply = SUPPLY, policy = LocalPolicy(defaultReservation = 1L))
        val states = lanes.map { LaneState(it) }
        val slots = arrayOfNulls<Running>(SLOTS)
        var bTime = 0L
        var bReported = 0L
        // Sorted, so nothing here walks a hash order.
        fun waiting(): Set<GroupId> = (states.filter { it.queued > 0 }.map { it.spec.leaf } + B).sorted().toSet()

        for (t in 0L until HORIZON) {
            for (i in slots.indices) {
                val run = slots[i] ?: continue
                if (run.endsAt != t) continue
                assertEquals(run.cost, (h.complete(run.lease, run.cost, waiting()) as LeaseOutcome.Done).charged)
                if (run.lane < 0) bReported += run.cost else states[run.lane].reported += run.cost
                slots[i] = null
            }
            for (run in slots) {
                if (run == null || run.lane < 0 || states[run.lane].spec.estimate != Estimate.ONE_THEN_EXTEND) continue
                if (t - run.start >= run.reserved && run.reserved < run.cost) {
                    assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.extend(run.lease, 1L, waiting()))
                    run.reserved += 1L
                }
            }
            for (lane in states) {
                if (lane.queued == 0 && t == lane.nextArrival) lane.queued = lane.spec.burst
            }
            for (i in slots.indices) {
                if (slots[i] != null) continue
                val w = waiting()
                val out = h.acquire(w, w, startedAt = t, reservation = 1L)
                check(out is AcquireOutcome.Acquired) { "t=$t slot=$i: $out" }
                val lease = out.lease
                val index = states.indexOfFirst { it.spec.leaf == lease.leaf }
                if (index < 0) {
                    slots[i] = Running(lease.id, lane = -1, start = t, endsAt = t + 1L, cost = 1L, reserved = 1L)
                    bTime += 1L
                    continue
                }
                val lane = states[index]
                lane.queued--
                val emptiesQueue = lane.queued == 0
                if (emptiesQueue) lane.nextArrival = t + lane.spec.think
                val cost = lane.spec.cost
                val reservation = when (lane.spec.estimate) {
                    Estimate.HONEST -> cost
                    Estimate.QUARTER -> maxOf(1L, cost / 4)
                    Estimate.FOUR_TIMES -> 4 * cost
                    Estimate.FOUR_TIMES_ON_LAST -> if (emptiesQueue) 4 * cost else cost
                    Estimate.ONE_THEN_EXTEND -> 1L
                }
                if (reservation > 1L) assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.extend(lease.id, reservation - 1L, waiting()))
                slots[i] = Running(lease.id, index, start = t, endsAt = t + cost, cost = cost, reserved = reservation)
                lane.slotTime += cost
            }
        }
        assertEquals(0, h.forcedMints, "the supply covered every overrun")
        assertSpendIsReported(h.snapshot().ledger, ledger, states.associate { it.spec.leaf to it.reported } + (B to bReported))
        // Slot time is counted at the pick, so trim what runs past the horizon from every lane alike.
        val laneTime = states.withIndex().associate { (index, lane) ->
            lane.spec.leaf to lane.slotTime - slots.filterNotNull().filter { it.lane == index }.sumOf { it.endsAt - HORIZON }
        }
        val all = laneTime + (B to bTime - slots.filterNotNull().filter { it.lane < 0 }.sumOf { it.endsAt - HORIZON })
        return Run(all, all.values.sum())
    }

    /** Each leaf's spend through its own edge is what its completions reported, and nothing it reserved. */
    private fun assertSpendIsReported(after: EntitlementLedger, shape: EntitlementLedger, reported: Map<GroupId, Long>) {
        for ((leaf, sum) in reported) {
            val edge = shape.allEdges().single { shape.record(it)?.child == leaf }
            assertEquals(sum, checkNotNull(after.edge(edge)).spent, "${leaf.value}'s spend against the costs it reported")
        }
    }
}
