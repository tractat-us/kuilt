package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A pick never throws at any reachable state, however the waiting set moves between picks, and no
 * stored virtual time ever leaves its edge's grid (#3313, #3231).
 *
 * Each step draws a fresh waiting set — a random, non-empty subset of the lanes — acquires one
 * lease, and completes it at a random cost. A changing waiting set is what makes children wake,
 * and each wake clamps a child to a front that is a weighted mean over a *different* set than the
 * last one. Before the grid each such mean multiplied the denominators of the stored wake offsets,
 * so a pick overflowed `Long` within tens of steps; a constant waiting set never wakes anyone and
 * cannot reach the defect, so it is not an arm here.
 *
 * Every arm asserts its own loop count and that its rig fired: wakes clamped someone forward, or
 * seats landed under demanding siblings. And every arm checks the property that makes the fix a
 * fix rather than a postponement: each stored floor and wake offset sits **on its edge's grid**,
 * `value · w · G` a whole number of ticks. A denominator that compounds would break that first.
 *
 * The weights are mixed on purpose: whole numbers far apart (60 against 1), and fractions whose
 * denominators are coprime with each other (3/7, 2/5, 5/3), since a weight's denominator is what
 * a fractional sum carries into the mean.
 *
 * The generator walks only [List]s it built itself, in declaration order, so the same seed takes
 * the same trajectory on every target.
 *
 * Shape salvaged from #3316 (`faa2664c0`, `1a861aaaf`, `4230ec05b`, `8cc13c27b`): the two pick
 * paths, the ceiling run, the seat-churn arm with a persist and restore mid-run, and the habit of
 * asserting that the rig fired.
 */
class LocalHeddleOverflowPropertyTest {

    /** The **order** path offers every waiting lane as eligible, so eligibility and deadline run over the whole set. */
    @Test
    fun theOrderPathNeverOverflowsAsTheWaitingSetChanges() = assertNeverOverflows(seed = 7L, startPath = false)

    /** The **start** path offers one lane, so the pick has one candidate while the wake step sees every waiter. */
    @Test
    fun theStartPathNeverOverflowsAsTheWaitingSetChanges() = assertNeverOverflows(seed = 11L, startPath = true)

    /**
     * #3231's trigger, which is magnitude rather than compounding: fractional weights `3/7 : 2/5`,
     * every lease at [LocalHeddle.MAX_RESERVATION] and charged in full, with both lanes always
     * waiting. Before the fix the pick threw at cycle 4,995, about `2⁵⁶` units of service under the
     * parent, and every later acquire there threw too. Twice that many cycles run here.
     */
    @Test
    fun fractionalWeightsPickPastTheOldServiceCeiling() {
        val topology = draft("ceiling") {
            lane("a", Weight.of(3, 7))
            lane("b", Weight.of(2, 5))
        }
        val engine = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        val lanes = listOf(topology.groupId("a"), topology.groupId("b"))
        val picked = IntArray(lanes.size)
        repeat(CEILING_CYCLES) { cycle ->
            val outcome = try {
                engine.acquire(lanes.toSet(), lanes.toSet(), startedAt = cycle.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            } catch (e: ArithmeticException) {
                throw AssertionError("cycle ${cycle + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "cycle ${cycle + 1}: expected a lease, got $outcome" }
            picked[lanes.indexOf(outcome.lease.leaf)]++
            engine.complete(outcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION)
        }
        assertAll(
            { assertEquals(CEILING_CYCLES, picked.sum(), "every cycle made one pick") },
            // 3/7 : 2/5 is 15 : 14.
            { assertTrue(picked.all { it > CEILING_CYCLES / 3 }, "both lanes are served near 15 : 14: ${picked.toList()}") },
            { assertEquals(emptyList<String>(), offGrid(engine.snapshot())) },
        )
    }

    /**
     * The seat, the second way in, which needs no wake at all: a runtime child attaches and is seated
     * at its parent's front, the same weighted mean, written into its gauge floor in the ledger. While
     * its siblings are waiting each seat carried their `Σ w` into the floor's denominator, so churning
     * runtime children — attach one, detach the oldest — under a waiting set of *every attached
     * child* overflowed after 19–39 seats, and the floor survived a persist and restore with it.
     *
     * Every attached child always waits, so nothing ever idles and wakes: the wake path is not what
     * this arm reaches. Halfway through, the engine is persisted, encoded, decoded and restored, and
     * the run carries on from the restored one.
     */
    @Test
    fun churningRuntimeChildrenUnderAHeldWaitingSetNeverOverflows() {
        val topology = draft("churn") {
            lane("a", Weight.of(3))
            group("rt", Weight.of(2)) { runtimeChildren(Weight.of(3, 2)) }
        }
        val policy = LocalPolicy(defaultReservation = 1L)
        var engine = LocalHeddle.fromTopology(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val a = topology.groupId("a")
        val rt = listOf("rt")
        val attached = ArrayDeque<String>()
        val random = Random(3313)
        var seats = 0
        var picks = 0
        var restored = 0
        val seatedAtAFront = HashSet<String>()
        val offGridSamples = ArrayList<String>()

        for (step in 0 until CHURN_SEATS) {
            val name = "c$step"
            check(engine.attachRuntimeChild(rt, name, generation = step.toLong()).refusals.isEmpty()) { "attach $name" }
            attached.addLast(name)
            val waiting = (attached.map { topology.runtimeChildId(rt, it) } + a).toSet()
            val outcome = try {
                engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = 1L)
            } catch (e: ArithmeticException) {
                throw AssertionError("seat ${seats + 1}, pick ${picks + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "seat ${seats + 1}: expected a lease, got $outcome" }
            seats++
            // The rig: children seated at their siblings' front rather than at their own origin.
            // The pre-grid seat stored that front, a weighted mean, as it was, and that is what grew.
            val ledger = engine.snapshot().ledger
            for (child in attached) {
                val edge = ledger.lineageOf(topology.runtimeChildId(rt, child))?.lastOrNull() ?: continue
                if (ledger.gauge(edge)?.floor?.let { it > Rational.ZERO } == true) seatedAtAFront += child
            }
            engine.complete(outcome.lease.id, actualCost = 1L + random.nextInt(MAX_COST))
            picks++
            if (attached.size > CHURN_WIDTH) {
                check(engine.detachRuntimeChild(rt, attached.removeFirst()).refusals.isEmpty()) { "detach at seat $seats" }
            }
            if (step % SAMPLE_EVERY == 0) offGridSamples += offGrid(engine.snapshot())
            if (step == CHURN_SEATS / 2) {
                val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), engine.persist())
                val decoded = LocalHeddle.decode(bytes, Cbor)
                check(decoded is RestoreResult.Restored) { "the snapshot did not decode: $decoded" }
                engine = LocalHeddle.restore(decoded.snapshot, policy, epoch = 2L)
                restored++
            }
        }
        assertAll(
            { assertEquals(CHURN_SEATS, seats, "every step attached and seated one child") },
            { assertEquals(CHURN_SEATS, picks, "every step made one pick") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            // Measured: 2,138 of 5,000. The rest were seated while no sibling under `rt` was seated yet.
            { assertTrue(seatedAtAFront.size > CHURN_SEATS / 4, "the rig: children seated at a front, ${seatedAtAFront.size}") },
            { assertEquals(emptyList<String>(), offGridSamples) },
        )
    }

    /**
     * A snapshot v0.7.4 wrote just before it wedged: four lanes `3/7, 2/5, 1, 3` after 30,723 picks
     * under a changing waiting set, its wake offsets carrying denominators up to 72,557,275 and its
     * next pick overflowing. It restores without migration, because every stored value is read up to
     * its edge's grid, and picks on — and the first wake offset a pick rewrites lands back on the
     * grid.
     */
    @Test
    fun aSnapshotThatWedgedOnTheOldArithmeticRestoresAndPicksOn() {
        val decoded = LocalHeddle.decode(WEDGED_AT_0_7_4, Json)
        val snapshot = assertIs<RestoreResult.Restored>(decoded, "decode: $decoded").snapshot
        val fatBefore = offGrid(snapshot)
        val engine = LocalHeddle.restore(snapshot, LocalPolicy(defaultReservation = 1L), epoch = 2L)
        val lanes = listOf("fs/l0", "fs/l1", "fs/l2", "fs/l3").map(::GroupId)
        val random = Random(3231)
        var picks = 0
        repeat(WEDGED_PICKS) { step ->
            val waiting = lanes.shuffled(random).take(1 + random.nextInt(lanes.size)).toSet()
            val outcome = try {
                engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = 1L)
            } catch (e: ArithmeticException) {
                throw AssertionError("pick ${step + 1} after restore threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            engine.complete(outcome.lease.id, actualCost = 1L + random.nextInt(MAX_COST))
            picks++
        }
        assertAll(
            // The rig: the snapshot really does carry the fat values that wedged v0.7.4.
            { assertTrue(fatBefore.size >= 4, "the checked-in snapshot is off the grid: $fatBefore") },
            { assertEquals(WEDGED_PICKS, picks, "every step made one pick") },
            // Gauges are rewritten on grid by every checkpoint; a wake offset by its next wake.
            { assertEquals(emptyList<String>(), offGrid(engine.snapshot()).filter { it.startsWith("floor") }) },
        )
    }

    private fun assertNeverOverflows(seed: Long, startPath: Boolean) {
        val topology = draft("fs") {
            LANES.forEach { (name, weight) -> lane(name, weight) }
            group("nested", Weight.of(2)) {
                NESTED.forEach { (name, weight) -> lane(name, weight) }
            }
        }
        val engine = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        val leaves = LANES.map { topology.groupId(it.first) } + NESTED.map { topology.groupId("nested", it.first) }
        val random = Random(seed)

        var picks = 0
        var samplesWithAClampedWaker = 0
        val offGridSamples = ArrayList<String>()
        repeat(PICKS) { step ->
            val size = 1 + random.nextInt(leaves.size)
            val waiting = leaves.shuffled(random).take(size)
            val eligible = if (startPath) listOf(waiting[random.nextInt(waiting.size)]) else waiting
            val outcome = try {
                engine.acquire(waiting.toSet(), eligible.toSet(), startedAt = step.toLong(), reservation = 1L)
            } catch (e: ArithmeticException) {
                throw AssertionError("seed $seed, start path $startPath: pick ${step + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "seed $seed, pick ${step + 1}: expected a lease, got $outcome" }
            engine.complete(outcome.lease.id, actualCost = 1L + random.nextInt(MAX_COST))
            picks++
            if (step % SAMPLE_EVERY == 0) {
                val snapshot = engine.snapshot()
                if (snapshot.allocatorMemory.wakeOffsets.values.any { it > Rational.ZERO }) samplesWithAClampedWaker++
                offGridSamples += offGrid(snapshot)
            }
        }
        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(0, engine.snapshot().leases.size, "every lease was completed") },
            // The rig: wakes clamped someone forward. Without one the run never reached the state
            // that overflowed, and its green would prove nothing.
            { assertTrue(samplesWithAClampedWaker > PICKS / SAMPLE_EVERY / 2, "a clamped waker in $samplesWithAClampedWaker samples") },
            { assertEquals(emptyList<String>(), offGridSamples) },
        )
    }

    /**
     * Every stored floor and wake offset in [ledger] and [memory] that is **not** on its edge's grid — whose
     * `value · w · G` is not a whole number of ticks — named for the failure message. Empty is the
     * fix: a compounding denominator would put a value off the grid before it overflowed anything.
     */
    private fun offGrid(state: LocalHeddleState): List<String> = offGrid(state.ledger, state.allocatorMemory)

    private fun offGrid(snapshot: LocalHeddleSnapshot): List<String> = offGrid(snapshot.ledger, snapshot.allocatorMemory)

    private fun offGrid(ledger: EntitlementLedger, memory: AllocatorMemory): List<String> {
        val floors = ledger.allEdges().mapNotNull { edge ->
            val floor = ledger.gauge(edge)?.floor ?: return@mapNotNull null
            val w = ledger.record(edge)?.weight ?: return@mapNotNull null
            if (onGrid(floor, w)) null else "floor ${edge.value}=$floor"
        }
        val offsets = memory.wakeOffsets.mapNotNull { (edge, offset) ->
            val w = ledger.record(edge)?.weight ?: return@mapNotNull null
            if (onGrid(offset, w)) null else "offset ${edge.value}=$offset"
        }
        return (floors + offsets).sorted()
    }

    /**
     * Whether `value · w · G` is a whole number: exact, in 128 bits. `G` is restated as [GRID]
     * rather than read from production, so a change to the grid reds this test too.
     */
    private fun onGrid(value: Rational, w: Weight): Boolean =
        value.numerator.timesWide(w.numerator * GRID) % (value.denominator * w.denominator) == 0L

    private companion object {
        /** Ticks per service unit, design §7.1 (#3313). */
        const val GRID = 16L
        const val PICKS = 100_000
        const val SAMPLE_EVERY = 100
        const val CEILING_CYCLES = 10_000
        const val CHURN_SEATS = 5_000
        const val CHURN_WIDTH = 4
        const val WEDGED_PICKS = 20_000
        const val MAX_COST = 3_600

        val LANES = listOf(
            "a" to Weight.of(60),
            "b" to Weight.of(30),
            "c" to Weight.of(10),
            "d" to Weight.of(1),
            "e" to Weight.of(3, 7),
            "f" to Weight.of(2, 5),
        )
        val NESTED = listOf(
            "x" to Weight.of(5, 3),
            "y" to Weight.ONE,
            "z" to Weight.of(4),
        )
    }
}
