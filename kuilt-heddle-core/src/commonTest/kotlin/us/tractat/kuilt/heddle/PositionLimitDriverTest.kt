package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A seeded driver over weights at the budget's edges (design §7.1, the position limit): joins,
 * leaves and reweights by reload, leases at the largest size, cancels, and overruns. After every
 * call nothing has thrown, `validate()` is empty, and the root's load, the sum of its active
 * children's stored positions, is inside 128 bits. Across the seeds the limit refuses at least
 * once, so the run reached the state it exists to test.
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
                val ledger = h.snapshot().ledger
                assertEquals(emptyList(), ledger.validate(), "seed $seed, step $step: the books are valid")
                assertTrue(loadInside128Bits(ledger, GroupId("p")), "seed $seed, step $step: the root's load is inside 128 bits")
            }
        }
        assertAll(
            { assertEquals(SEEDS * STEPS, calls, "the rig: every step ran") },
            { assertTrue(refusals > 0, "the rig: the position limit refused at least once ($refusals)") },
        )
    }

    /** Whether the sum of [parent]'s active children's stored positions forms inside 128 bits. */
    private fun loadInside128Bits(ledger: EntitlementLedger, parent: GroupId): Boolean = try {
        ledger.activeChildren(parent).map { it.attachment }.sortedBy { it.value }
            .mapNotNull { ledger.gauge(it)?.let { g -> Int128.fromTwosComplement(g.ticksHigh, g.ticksLow) } }
            .fold(Int128.ZERO) { sum, p -> sum + p }
        true
    } catch (_: ArithmeticException) {
        false
    }

    private companion object {
        const val SEEDS = 6
        const val STEPS = 400
    }
}
