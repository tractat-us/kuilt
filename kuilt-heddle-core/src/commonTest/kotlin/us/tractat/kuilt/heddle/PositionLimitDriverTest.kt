package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A seeded driver over weights at the budget's edges (design §7.1, the position limit): joins,
 * leaves and reweights by reload, leases at the largest size, cancels, and overruns. After every
 * call nothing has thrown, `validate()` is empty, and the root's **load** is within the limit.
 * Across the seeds the limit refuses at least once, so the run reached the state it exists to test.
 *
 * The load is §7.1's: the sum of the active children's *effective* positions, each counted by its
 * size. An effective position is the raw one, `ticks + (baseIssued − folded) · G − returned · G`,
 * plus the allocator's wake offset, which lives in its memory and never in the ledger. So this
 * test reads the wake offsets out of [LocalHeddleState.allocatorMemory] and builds the sum itself,
 * rather than calling the production expression it is checking. Summing the stored `ticks` alone,
 * as this test first did (#3538), misses the issuance since the last checkpoint, the returns and
 * every wake offset. The run asserts that a wake offset was present at some step, so that part of
 * the sum is exercised.
 *
 * The bound is §7.1's too: a landing is kept only if the load after it is at most `2¹²⁵` ticks,
 * and the step's own charge can then add one grant and one overrun on top. That is
 * [STEP_CHARGE]. The limit is written out here, not read from [POSITION_LIMIT], so a weakened
 * constant reds this test instead of moving its bound along with it. "Inside 128 bits", the bound
 * this test first used, is four times the limit. Even with every load check in production deleted,
 * this run peaks at about 0.59 of the limit (#3538). The targeted tests in
 * `FairShareAllocatorRefusalTest` are what pin the load check itself.
 *
 * Every draw walks a [List] built here, sorted, never a `Set` or a map view (#2592), so a seed
 * takes the same trajectory on every target.
 */
class PositionLimitDriverTest {

    private val weights: List<Weight> = listOf(
        Weight.of(1L, 1L shl 14),
        Weight.of(1L, 30_000L),
        Weight.ONE,
        Weight.of(4L),
        Weight.of(1L shl 40),
        Weight.of(1L shl 57),
        Weight.of(1L shl 58),
    )
    private val names: List<String> = listOf("a", "b", "c", "d")

    @Test
    fun noCallThrowsTheBooksStayValidAndTheLimitRefuses() {
        var refusals = 0
        var calls = 0
        var stepsWithWakeOffsets = 0
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val pick = { list: List<Weight> -> list[rng.nextInt(list.size)] }
            var lanes: List<Pair<String, Weight>> = listOf("a" to weights[0])
            var generation = 0L
            fun shape() = draft("p") { for ((name, w) in lanes.sortedBy { it.first }) lane(name, w) }
            val h = LocalHeddle.bootWhole(shape(), LocalPolicy(defaultReservation = 1L), epoch = 1L)
            h.mintSupply(1L shl 58)
            repeat(STEPS) { step ->
                calls++
                when (rng.nextInt(10)) {
                    0 -> {
                        // A reload: add, drop or reweight one lane.
                        val name = names[rng.nextInt(names.size)]
                        lanes = if (lanes.any { it.first == name } && lanes.size > 1 && rng.nextBoolean()) {
                            lanes.filterNot { it.first == name }
                        } else {
                            lanes.filterNot { it.first == name } + (name to pick(weights))
                        }
                        val result = h.apply(shape(), generation = ++generation)
                        refusals += result.refusals.count { "position limit" in (it as? StepRefusal.Refused)?.reason.orEmpty() }
                    }
                    else -> {
                        val leaves = lanes.map { shape().groupId(it.first) }.sortedBy { it.value }.toSet()
                        when (val out = h.acquire(leaves, leaves, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                            is AcquireOutcome.Acquired -> when (rng.nextInt(4)) {
                                0 -> h.cancel(out.lease.id)
                                1 -> h.complete(out.lease.id, LocalHeddle.MAX_RESERVATION + rng.nextLong(1L, 1L shl 20), waiting = emptySet())
                                else -> h.complete(out.lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet())
                            }
                            is AcquireOutcome.LifetimeExhausted -> refusals++
                            else -> Unit
                        }
                    }
                }
                val state = h.snapshot()
                assertEquals(emptyList(), state.ledger.validate(), "seed $seed, step $step: the books are valid")
                val load = loadOf(state, GroupId("p"))
                assertTrue(
                    load != null && load <= LIMIT + STEP_CHARGE,
                    "seed $seed, step $step: the root's load $load is within the limit and one step's charge",
                )
                if (state.allocatorMemory.wakeOffsets.isNotEmpty()) stepsWithWakeOffsets++
            }
        }
        assertAll(
            { assertEquals(SEEDS * STEPS, calls, "the rig: every step ran") },
            { assertTrue(refusals > 0, "the rig: the position limit refused at least once ($refusals)") },
            { assertTrue(stepsWithWakeOffsets > 0, "the rig: a wake offset was in the load at some step") },
        )
    }

    /**
     * [parent]'s load in [state], design §7.1: the sum of its active children's effective positions,
     * raw plus wake offset, each counted by its size. `null` when the sum leaves 128 bits.
     */
    private fun loadOf(state: LocalHeddleState, parent: GroupId): Int128? = try {
        val ledger = state.ledger
        ledger.activeChildren(parent).sortedBy { it.attachment.value }.fold(Int128.ZERO) { load, summary ->
            val id = summary.attachment
            val base = ledger.baseIssuance(id)
            val gross = ledger.gauge(id)?.let { g -> Int128.fromTwosComplement(g.ticksHigh, g.ticksLow) + (base - g.folded).timesWide(TICKS_PER_UNIT) }
                ?: base.timesWide(TICKS_PER_UNIT)
            val effective = gross - summary.returned.timesWide(TICKS_PER_UNIT) + (state.allocatorMemory.wakeOffsets[id] ?: Int128.ZERO)
            load + if (effective < Int128.ZERO) Int128.ZERO - effective else effective
        }
    } catch (_: ArithmeticException) {
        null
    }

    private companion object {
        const val SEEDS = 6
        const val STEPS = 400

        /** The position limit, `2¹²⁵` ticks (design §7.1), written out rather than read from production. */
        val LIMIT: Int128 = Int128.fromTwosComplement(1L shl 61, 0L)

        /** The most one step charges past a landing: a grant of the largest size, and an overrun of up to `2²⁰` units. */
        val STEP_CHARGE: Int128 = (LocalHeddle.MAX_RESERVATION + (1L shl 20)).timesWide(TICKS_PER_UNIT)
    }
}
