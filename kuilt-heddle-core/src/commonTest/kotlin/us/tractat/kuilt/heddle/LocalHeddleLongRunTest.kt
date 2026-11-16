package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A busy [LocalHeddle] parent runs for a long time: never refused, and fair (#3329, #3472).
 *
 * These rows were written for the rebase, which kept a busy parent's positions below their ceiling
 * and was checked against a twin that never rebased. Nothing rebases now: a position only counts up
 * from its edge's start, a pick orders it in 128 bits, and the twin is the engine. So each row
 * drives a parent far past where the rebase used to fire and asserts what is left to promise.
 * Every call answers, no pick is refused, and a closing stretch in which every lane waits hands
 * each lane its weight's share ([assertFairShares]).
 *
 * **These rows are the pin on pick order past the old ceiling** (design §7.1). A pick compare
 * narrowed back to `Long` reds three of them and nothing else in the module. The governed match in
 * `:kuilt-heddle`'s `HeddleAcceptanceTest` does not pin it: both of its sides call
 * [HeddlePolicy.pick], so a mutation there moves both alike (#3504).
 *
 * The generators walk only [List]s they built themselves, in declaration order, so the same seed
 * takes the same trajectory on every target.
 */
class LocalHeddleLongRunTest {

    /**
     * Seven lanes with weights far apart and denominators coprime with each other, plus a nested
     * group of two, every lease at [LocalHeddle.MAX_RESERVATION], under a waiting set drawn afresh
     * each step. One lease in [PARTIAL_EVERY] is charged half its reservation, so returns are in
     * play, and one step in [UNREACHABLE_EVERY] also asks for a group no lease can end at, which is
     * refused.
     *
     * The heaviest lane's effective position passes `2⁶³` ticks inside the run, so a pick that
     * narrowed a position to `Long` would throw or misorder there, and the row asserts it gets
     * there. Halfway through, the engine is persisted, encoded, decoded and restored, and the
     * engine it was taken from keeps running beside the restored one for [RESTORE_WINDOW] picks,
     * which must match.
     */
    @Test
    fun aBusyParentPassesItsCeilingAndKeepsPickingFairly() {
        val topology = draft("rb") {
            LANES.forEach { (name, weight) -> lane(name, weight) }
            group("n", Weight.of(2)) { NESTED.forEach { (name, weight) -> lane(name, weight) } }
        }
        val policy = LocalPolicy(defaultReservation = 1L)
        var engine = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        var beside: LocalHeddle? = null
        val leaves = LANES.map { topology.groupId(it.first) } + NESTED.map { topology.groupId("n", it.first) }
        val unreachable = topology.groupId("n")
        val random = Random(3329)

        var picks = 0
        var refusedAsUnreachable = 0
        var restored = 0
        var besidePicks = 0
        val divergences = ArrayList<String>()
        var highest = Int128.ZERO

        for (step in 0 until PICKS) {
            val waiting = leaves.filter { random.nextBoolean() }.ifEmpty { listOf(leaves[random.nextInt(leaves.size)]) }.toSet()
            val partial = step % PARTIAL_EVERY == 0
            val askUnreachable = step % UNREACHABLE_EVERY == 0
            val running = listOfNotNull(engine, beside)
            val outcomes = running.map { e ->
                val unreachableOutcome = if (askUnreachable) e.acquire(waiting + unreachable, setOf(unreachable), startedAt = step.toLong()) else null
                val outcome = try {
                    e.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                } catch (ex: ArithmeticException) {
                    throw AssertionError("pick ${step + 1} threw $ex", ex)
                }
                if (outcome is AcquireOutcome.Acquired) {
                    val cost = if (partial) LocalHeddle.MAX_RESERVATION / 2 else LocalHeddle.MAX_RESERVATION
                    e.complete(outcome.lease.id, actualCost = cost, waiting = emptySet())
                }
                unreachableOutcome to outcome
            }
            val (unreachableOutcome, outcome) = outcomes[0]
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            picks++
            if (unreachableOutcome == AcquireOutcome.NoLiveLineage(topology.groupId())) refusedAsUnreachable++

            if (beside != null) {
                val besideOutcome = outcomes[1].second
                if (besideOutcome !is AcquireOutcome.Acquired || besideOutcome.lease.leaf != outcome.lease.leaf) {
                    if (divergences.size < MAX_REPORTED) divergences += "pick ${step + 1} after the restore: ${outcome.lease.leaf.value} against $besideOutcome"
                }
                besidePicks++
                if (besidePicks == RESTORE_WINDOW) beside = null
            }
            if (step % SAMPLE_EVERY == 0) highest = maxOf(highest, highestEffectiveTicks(engine))
            if (step == PICKS / 2) {
                val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), engine.persist())
                val decoded = LocalHeddle.decode(bytes, Cbor)
                check(decoded is RestoreResult.Restored) { "the snapshot did not decode: $decoded" }
                beside = engine
                engine = LocalHeddle.restore(decoded.snapshot, policy, epoch = 2L)
                restored++
            }
        }
        highest = maxOf(highest, highestEffectiveTicks(engine))
        val shares = LANES.map { (name, w) -> topology.groupId(name) to w.share() } +
            NESTED.map { (name, w) -> topology.groupId("n", name) to Weight.of(2).share() * w.share() / NESTED.sumOf { it.second.share() } }

        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(PICKS / UNREACHABLE_EVERY, refusedAsUnreachable, "every ask for the unreachable group was refused") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            { assertEquals(RESTORE_WINDOW, besidePicks, "the engine it was taken from ran beside the restored one") },
            { assertEquals(emptyList<String>(), divergences, "the restored engine picked as the engine it was taken from") },
            { assertTrue(highest > Int128.of(Long.MAX_VALUE), "the rig: an effective position passed 2⁶³ ticks, $highest") },
            { assertFairShares(engine, shares) },
        )
    }

    /**
     * Returns in earnest: every lease charged half its reservation or cancelled outright, at a
     * reservation of `2⁴²`, over a run whose lifetime returns stay well below the bound #3360
     * tracks. Every step picks, and the closing stretch is fair.
     */
    @Test
    fun leasesChargedBelowTheirReservationKeepPickingFairly() {
        val topology = draft("rt") { RETURN_LANES.forEach { (name, weight) -> lane(name, weight) } }
        val engine = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val lanes = RETURN_LANES.map { topology.groupId(it.first) }
        val random = Random(3360)
        var picks = 0
        for (step in 0 until RETURN_PICKS) {
            val waiting = lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            val outcome = engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = RETURN_RESERVATION)
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            if (step % 4 == 0) engine.cancel(outcome.lease.id) else engine.complete(outcome.lease.id, actualCost = RETURN_RESERVATION / 2, waiting = emptySet())
            picks++
        }
        assertAll(
            { assertEquals(RETURN_PICKS, picks, "every step made one pick") },
            { assertFairShares(engine, RETURN_LANES.map { (name, w) -> topology.groupId(name) to w.share() }) },
        )
    }

    /**
     * #3360's lane: a lease that reserves [LocalHeddle.MAX_RESERVATION] and charges 1 unit returns
     * almost all of it, at weight `3/7`. Returned service used to grow the gross position, so this
     * lane met [AcquireOutcome.PositionCeiling] after 4,681 picks (measured on the slice 2–4 base,
     * JVM). The ceiling is now measured on the raw position, net of returns, and the gross is stored
     * wide (#3353), so the lane makes [RETURNED_LANE_PICKS] picks with no refusal. The gross passes
     * the ceiling, and the effective position a pick reads climbs one unit a pick, far below it: with
     * returns left out of the read, the peak arm reds.
     */
    @Test
    fun aLaneThatReturnsAlmostAllOfEachReservationNeverReachesTheCeiling() {
        val topology = draft("returns") {
            lane("a", Weight.of(3L, 7L))
            lane("b", Weight.ONE)
        }
        val engine = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        val a = topology.groupId("a")
        var picks = 0
        var peak = 0.0
        repeat(RETURNED_LANE_PICKS) { step ->
            val outcome = engine.acquire(setOf(a), setOf(a), startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            engine.complete(outcome.lease.id, actualCost = 1L, waiting = emptySet())
            picks++
            if (step % SAMPLE_EVERY == 0) peak = maxOf(peak, peakShare(engine))
        }
        peak = maxOf(peak, peakShare(engine))
        val returned = checkNotNull(engine.snapshot().ledger.let { it.edge(checkNotNull(it.lineageOf(a)).last()) }).returned
        assertAll(
            { assertEquals(RETURNED_LANE_PICKS, picks, "the run made its own picks") },
            // The bound the old read met: returned · G at the ceiling of weight 3/7.
            { assertTrue(returned.timesWide(TICKS_PER_UNIT) > Int128.of(positionCeiling(Weight.of(3L, 7L))), "the rig: the lane returned past the old bound ($returned units)") },
            { assertTrue(peak < 1.0, "the effective position, net of returns, stayed below the ceiling: $peak") },
        )
    }

    /**
     * The 60/30/10/1 spread #3313 was reported with, on `LocalHeddle` (#3353 review, item A): the
     * light lane alone for [WIDE_ALONE] picks, then a waiting set drawn afresh each pick, leases at
     * [LocalHeddle.MAX_RESERVATION] charged half or cancelled. A heavy lane waking to the light
     * lane's front lands far up its own grid. Every step picks, and the closing stretch is fair.
     */
    @Test
    fun aWideSpreadKeepsPickingFairly() {
        val weights = listOf("w60" to Weight.of(60L), "w30" to Weight.of(30L), "w10" to Weight.of(10L), "w1" to Weight.ONE)
        val topology = draft("wide") { weights.forEach { (name, w) -> lane(name, w) } }
        val engine = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val lanes = weights.map { topology.groupId(it.first) }
        val random = Random(3313)
        var picks = 0
        repeat(WIDE_PICKS) { step ->
            // The light lane alone first, long enough to climb; then any set.
            val waiting = if (step < WIDE_ALONE) setOf(lanes[3]) else lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            val outcome = engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            if (step % 4 == 0) engine.cancel(outcome.lease.id) else engine.complete(outcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION / 2, waiting = emptySet())
            picks++
        }
        assertAll(
            { assertEquals(WIDE_PICKS, picks, "every step made one pick") },
            { assertFairShares(engine, weights.map { (name, w) -> topology.groupId(name) to w.share() }) },
        )
    }

    /**
     * A parent driven only by charging calls, `admit`, `extend` and `complete`, and never picked at
     * (#3353 review, item I). Only a pick used to rebase, so this lane's position climbed until
     * `extend` refused with `PositionCeiling`; then a charging call rebased too. Now nothing does,
     * the lane passes its ceiling, and every call still lands. The sibling `b`, never charged, was
     * seen idle throughout, so the first pick that sees it waiting clamps it to `a`'s front, and
     * the two share the picks after: it is owed none of the run it sat out (§10.6).
     */
    @Test
    fun aParentDrivenOnlyByChargingCallsKeepsLanding() {
        val topology = draft("charged") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
        }
        val engine = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val a = topology.groupId("a")
        val b = topology.groupId("b")
        var leases = 0
        var extended = 0
        repeat(CHARGED_LEASES) { step ->
            val admitted = engine.admit(a, waiting = setOf(a), startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            check(admitted is AcquireOutcome.Acquired) { "lease ${step + 1}: expected a lease, got $admitted" }
            if (step % 3 == 0) {
                val out = engine.extend(admitted.lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet())
                check(out is LeaseOutcome.Done) { "lease ${step + 1}: extend answered $out" }
                extended++
            }
            engine.complete(admitted.lease.id, actualCost = LocalHeddle.MAX_RESERVATION, waiting = emptySet())
            leases++
        }
        val peak = peakShare(engine)
        val next = (0 until FAIR_AFTER_CHARGES).map { i ->
            val out = engine.acquire(setOf(a, b), setOf(a, b), startedAt = CHARGED_LEASES.toLong() + i)
            assertIs<AcquireOutcome.Acquired>(out, "pick $i after the run: $out").lease.also { engine.complete(it.id, actualCost = 1L, waiting = emptySet()) }.leaf
        }
        assertAll(
            { assertEquals(CHARGED_LEASES, leases, "every step admitted and completed one lease") },
            { assertTrue(extended > 0, "the rig: leases were extended") },
            { assertTrue(peak >= 1.0, "the rig: the lane passed its ceiling, $peak") },
            { assertEquals(FAIR_AFTER_CHARGES / 2, next.count { it == b }, "b, clamped level, takes half the picks after, not all of them: $next") },
        )
    }

    /**
     * Fair: [FAIR_PICKS] picks with every leaf in [weights] waiting, each charged in full, hand
     * each leaf its weight's share of them, within [FAIR_SLACK] picks. [weights] holds each leaf's
     * weight as a share of the root, a nested leaf's being its group's times its own share there.
     */
    private fun assertFairShares(engine: LocalHeddle, weights: List<Pair<GroupId, Double>>) {
        val all = weights.map { it.first }.toSet()
        val counts = LinkedHashMap<GroupId, Int>().apply { weights.forEach { put(it.first, 0) } }
        repeat(FAIR_PICKS) { i ->
            val out = engine.acquire(all, all, startedAt = i.toLong(), reservation = FAIR_RESERVATION)
            check(out is AcquireOutcome.Acquired) { "fair pick $i: $out" }
            engine.complete(out.lease.id, actualCost = FAIR_RESERVATION, waiting = emptySet())
            counts[out.lease.leaf] = counts.getValue(out.lease.leaf) + 1
        }
        val total = weights.sumOf { it.second }
        val off = weights.mapNotNull { (leaf, w) ->
            val expected = FAIR_PICKS * w / total
            val got = counts.getValue(leaf)
            if (kotlin.math.abs(got - expected) > FAIR_SLACK) "${leaf.value}: $got, owed $expected" else null
        }
        assertEquals(emptyList(), off, "every lane took its weight's share of $FAIR_PICKS picks: $counts")
    }

    /** This weight as a `Double`, for a share. */
    private fun Weight.share(): Double = numerator.toDouble() / denominator

    /**
     * The highest effective position among [engine]'s root children, as a share of each one's own
     * ceiling ([FairShareAllocator.peakPositionFraction]): the position a pick reads, net of
     * returns, plus its wake clamp.
     */
    private fun peakShare(engine: LocalHeddle): Double =
        engine.allocator.peakPositionFraction(engine.snapshot().ledger, engine.root) { Demand.NONE }

    /** The highest effective position, in ticks, of any edge in [engine]: raw, net of returns, plus its wake offset. */
    private fun highestEffectiveTicks(engine: LocalHeddle): Int128 {
        val state = engine.snapshot()
        val ledger = state.ledger
        return ledger.allEdges().maxOf { edge ->
            val raw = ledger.gauge(edge).exactGrossTicksAt(ledger.baseIssuance(edge)) - (ledger.edge(edge)?.returned ?: 0L).timesWide(TICKS_PER_UNIT)
            raw + (state.allocatorMemory.wakeOffsets[edge] ?: Int128.ZERO)
        }
    }

    private companion object {
        const val PICKS = 100_000
        const val SAMPLE_EVERY = 100
        const val MAX_REPORTED = 5
        const val PARTIAL_EVERY = 16
        const val UNREACHABLE_EVERY = 50
        const val RESTORE_WINDOW = 10_000

        /**
         * The closing stretch every lane waits through, its leases' size, and how far a lane's count
         * may sit from its share. The leases are the run's own size, so a lane's lag when the stretch
         * starts, under a lease (§8.2), is under one of its picks.
         */
        const val FAIR_PICKS = 4_000
        const val FAIR_RESERVATION = LocalHeddle.MAX_RESERVATION
        const val FAIR_SLACK = 3.0

        /** Picks with both lanes waiting after the charge-only run. */
        const val FAIR_AFTER_CHARGES = 8

        val LANES = listOf(
            "a" to Weight.of(60),
            "b" to Weight.of(7),
            "c" to Weight.of(5, 3),
            "d" to Weight.ONE,
            "e" to Weight.of(3, 7),
            "f" to Weight.of(2, 5),
            "g" to Weight.of(1, 9),
        )
        val NESTED = listOf(
            "x" to Weight.ONE,
            "y" to Weight.of(3, 2),
        )

        const val RETURN_PICKS = 30_000

        /** Leases the charge-only run admits: past the 2¹⁵ that take a weight-1 lane to its ceiling. */
        const val CHARGED_LEASES = 40_000

        /** Picks the 60/30/10/1 run makes, and the first of them its light lane makes alone. */
        const val WIDE_PICKS = 30_000
        const val WIDE_ALONE = 3_000

        /** Over twenty times the 4,681 picks #3360's lane made before it refused. */
        const val RETURNED_LANE_PICKS = 100_000
        const val RETURN_RESERVATION = 1L shl 42
        val RETURN_LANES = listOf(
            "p" to Weight.ONE,
            "q" to Weight.of(3, 7),
            "r" to Weight.of(2),
        )
    }
}
