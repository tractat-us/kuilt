package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import kotlin.random.Random

/**
 * The rig behind the window-rule rows (#3353): one parent, lanes named by [lanes], driven for a
 * number of steps through `acquire` alone, every lease at [LocalHeddle.MAX_RESERVATION]. Each lease
 * is cancelled, completed at half or completed in full, by step, so service is uneven and lights
 * drift apart by up to a lease.
 *
 * Lanes are seeded draws over [List]s the rig built, so the trajectory is the same on every target.
 */
internal class WindowRuleRig(
    lanes: List<Pair<String, Weight>>,
    /** Off for a twin on the rule before the window rule ([LocalHeddle.windowRuleForTest]). */
    windowRule: Boolean = true,
) {
    private var topology = topologyOf(lanes)
    private var generation = 1L
    var names: List<String> = lanes.map { it.first }
        private set

    val engine: LocalHeddle = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply {
        windowRuleForTest = windowRule
        reconcileCapacity(1L)
    }

    /** What one run did: every count a row asserts on, and a fingerprint of the leaves it picked. */
    class Result(
        val steps: Int,
        val picks: Int,
        val refusals: Int,
        val firstRefusal: Int,
        val picksAfterRefusal: Int,
        val reloads: Int,
        val rebases: Int,
        val floorLandings: Int,
        /** The root's origin at the end, in whole units. */
        val origin: Int128,
        /** The lane each lease went to, by name. */
        val leaves: List<String>,
        /** The highest effective position any lane reached after a step, as a fraction of its ceiling. */
        val peakShare: Double,
    ) {
        override fun toString(): String =
            "steps=$steps picks=$picks refusals=$refusals firstRefusal=$firstRefusal picksAfterRefusal=$picksAfterRefusal " +
                "reloads=$reloads rebases=$rebases floorLandings=$floorLandings origin=$origin peakShare=$peakShare"
    }

    /** Replace the lanes with [lanes]: a lane left out is retired, a new name is a newborn. */
    fun reload(lanes: List<Pair<String, Weight>>) {
        topology = topologyOf(lanes)
        names = lanes.map { it.first }
        val result = engine.apply(topology, generation = generation++)
        check(result.refusals.isEmpty()) { "the rig: the reload landed, ${result.refusals}" }
    }

    /**
     * Run [steps] steps. Before each, [before] may reload; then [waiting] names the lanes that wait.
     * A refusal is counted and the run goes on, so a permanent wedge reads as every later step refused.
     */
    fun run(
        steps: Int,
        before: (step: Int) -> Boolean = { false },
        waiting: (step: Int, names: List<String>) -> Set<String>,
    ): Result {
        var picks = 0
        var refusals = 0
        var firstRefusal = -1
        var picksAfterRefusal = 0
        var reloads = 0
        val leaves = ArrayList<String>()
        var peakShare = 0.0
        for (step in 0 until steps) {
            if (before(step)) reloads++
            val set = waiting(step, names).mapTo(HashSet()) { topology.groupId(it) }
            when (val out = engine.acquire(set, set, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                is AcquireOutcome.Acquired -> {
                    picks++
                    if (firstRefusal >= 0) picksAfterRefusal++
                    leaves += names.first { topology.groupId(it) == out.lease.leaf }
                    when (step % 4) {
                        0 -> engine.cancel(out.lease.id)
                        1 -> engine.complete(out.lease.id, actualCost = LocalHeddle.MAX_RESERVATION / 2)
                        else -> engine.complete(out.lease.id, actualCost = LocalHeddle.MAX_RESERVATION)
                    }
                }
                is AcquireOutcome.PositionCeiling -> {
                    refusals++
                    if (firstRefusal < 0) firstRefusal = step
                }
                else -> error("the rig: step $step answered $out")
            }
            peakShare = maxOf(peakShare, engine.allocator.peakPositionFraction(engine.snapshot().ledger, engine.root) { Demand.NONE })
        }
        return Result(steps, picks, refusals, firstRefusal, picksAfterRefusal, reloads, engine.rebasesForTest, engine.floorLandingsForTest, engine.snapshot().ledger.origin(engine.root), leaves, peakShare)
    }

    companion object {
        private fun topologyOf(lanes: List<Pair<String, Weight>>) = draft("p") { lanes.forEach { (n, w) -> lane(n, w) } }

        /** Every lane waits on a coin flip, at least one. */
        fun churn(random: Random): (Int, List<String>) -> Set<String> = { _, names ->
            names.filter { random.nextBoolean() }.ifEmpty { listOf(names[random.nextInt(names.size)]) }.toSet()
        }

        /** `heavy` wakes about once in [idle] steps and waits [burst] steps; every other lane always waits. */
        fun randomWaker(random: Random, idle: Int, burst: Int): (Int, List<String>) -> Set<String> {
            var awakeUntil = -1
            return { step, names ->
                if (step >= awakeUntil && random.nextInt(idle) == 0) awakeUntil = step + burst
                names.filter { it != "heavy" || step < awakeUntil }.toSet()
            }
        }

        /**
         * A fresh weight-[k] lane born about once in [every] steps, waiting its first [burst] steps,
         * beside [stayers], which always wait. The old heavy lane is retired by one reload and the new
         * one added by the next, so every newborn enters through `acquire`.
         */
        fun newborns(
            k: Weight,
            stayers: List<Pair<String, Weight>>,
            random: Random,
            every: Int,
            burst: Int,
            steps: Int,
            windowRule: Boolean = true,
        ): Result {
            val rig = WindowRuleRig(stayers + ("h0" to k), windowRule)
            var born = 0
            var bornAt = 0
            return rig.run(
                steps,
                before = { step ->
                    val due = step > 0 && step - bornAt > burst && random.nextInt(every) == 0
                    if (due) {
                        born++
                        bornAt = step
                        rig.reload(stayers)
                        rig.reload(stayers + ("h$born" to k))
                    }
                    due
                },
                waiting = { step, names -> names.filter { !it.startsWith("h") || step - bornAt < burst }.toSet() },
            )
        }
    }
}
