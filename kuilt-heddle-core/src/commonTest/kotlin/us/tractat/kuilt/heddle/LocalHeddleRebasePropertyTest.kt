package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A busy [LocalHeddle] parent never reaches its position ceiling, because the engine rebases it
 * first, and the rebase changes no pick (#3329).
 *
 * Seven lanes with weights far apart and denominators coprime with each other, plus a nested group
 * of two, every lease at [LocalHeddle.MAX_RESERVATION], under a waiting set drawn afresh each step.
 * At that size the heaviest lane's effective position, its raw position plus its wake offset,
 * reaches its ceiling within about 1,200 picks, so a run of [PICKS] passes it many times over. One
 * lease in [PARTIAL_EVERY] is charged half its reservation, so returns are in play, and one step in
 * [UNREACHABLE_EVERY] also asks for a group no lease can end at, which is refused.
 *
 * A twin engine with the rebase switched off makes the same calls. It must pass its ceiling inside
 * the run, which is what proves the arm reaches the regime the rebase exists for, and every pick it
 * makes must be the rebasing engine's pick, past the ceiling too: the rebase moves every competing
 * child by the same virtual time, and a pick orders the full 128-bit position, so neither the
 * rebase nor the ceiling can change the order. Any divergence means one of them is not exact. The
 * twin used to refuse at its ceiling, and the comparison stopped there.
 *
 * Halfway through, the rebasing engine is persisted, encoded, decoded and restored, and the engine
 * it was taken from keeps running beside the restored one for [RESTORE_WINDOW] picks, which must
 * match: a raised fold and an absorbed offset survive the round trip.
 *
 * The twin is also the step B engine (design §7.1, *Future: positions with no origin*, slice 1):
 * with the rebase off it never raises an origin. So every driver here that rebases runs one, and
 * asserts beside the picks that the twin raised no origin, that the rebasing side landed no joiner
 * at its floor and clamped no never-observed child below zero, the only two causes the design lets
 * the two differ by, and, where the run is long enough, that the twin's positions passed `2⁶³`
 * ticks. The returned-lane driver has no twin: one lane asks and nets one unit a pick, so it never
 * rebases and a twin would run the same code.
 *
 * The generator walks only [List]s it built itself, in declaration order, so the same seed takes
 * the same trajectory on every target.
 */
class LocalHeddleRebasePropertyTest {

    @Test
    fun aBusyParentRebasesBeforeItsCeilingAndPicksAsAnUnrebasedTwin() {
        val topology = draft("rb") {
            LANES.forEach { (name, weight) -> lane(name, weight) }
            group("n", Weight.of(2)) { NESTED.forEach { (name, weight) -> lane(name, weight) } }
        }
        val policy = LocalPolicy(defaultReservation = 1L)
        fun boot() = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        var engine = boot()
        val twin = boot().apply { rebaseForTest = false }
        var beside: LocalHeddle? = null
        val leaves = LANES.map { topology.groupId(it.first) } + NESTED.map { topology.groupId("n", it.first) }
        val unreachable = topology.groupId("n")
        val random = Random(3329)

        var picks = 0
        var refusedAsUnreachable = 0
        var restored = 0
        var rebases = 0
        var twinPicks = 0
        var twinPassedAt = -1
        var rebasesBeforeTwinPassed = -1
        var besidePicks = 0
        val divergences = ArrayList<String>()
        var highestShare = 0.0
        var floorLandings = 0
        var unobservedBelowZero = 0
        var twinPeak = Int128.ZERO

        for (step in 0 until PICKS) {
            val waiting = leaves.filter { random.nextBoolean() }.ifEmpty { listOf(leaves[random.nextInt(leaves.size)]) }.toSet()
            unobservedBelowZero += neverObservedBelowZero(engine, waiting)
            val partial = step % PARTIAL_EVERY == 0
            val askUnreachable = step % UNREACHABLE_EVERY == 0
            val running = listOfNotNull(engine, beside, twin)
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
            when (val twinOutcome = outcomes.last().second) {
                is AcquireOutcome.Acquired -> {
                    twinPicks++
                    if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                        divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                    }
                }
                else -> error("pick ${step + 1}: the twin answered $twinOutcome")
            }

            if (step % SAMPLE_EVERY == 0) {
                highestShare = maxOf(highestShare, highestCeilingShare(engine))
                twinPeak = maxOf(twinPeak, peakEffectiveTicksAnywhere(twin))
                if (twinPassedAt < 0 && highestCeilingShare(twin) >= 1.0) {
                    twinPassedAt = step
                    rebasesBeforeTwinPassed = rebases + engine.rebasesForTest
                }
            }
            if (step == PICKS / 2) {
                rebases += engine.rebasesForTest
                floorLandings += engine.floorLandingsForTest
                val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), engine.persist())
                val decoded = LocalHeddle.decode(bytes, Cbor)
                check(decoded is RestoreResult.Restored) { "the snapshot did not decode: $decoded" }
                beside = engine
                engine = LocalHeddle.restore(decoded.snapshot, policy, epoch = 2L)
                restored++
            }
        }
        rebases += engine.rebasesForTest
        floorLandings += engine.floorLandingsForTest
        highestShare = maxOf(highestShare, highestCeilingShare(engine))

        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(PICKS / UNREACHABLE_EVERY, refusedAsUnreachable, "every ask for the unreachable group was refused") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            { assertEquals(RESTORE_WINDOW, besidePicks, "the engine it was taken from ran beside the restored one") },
            // The rig, twice over: without the rebase the run passes the ceiling, well inside the
            // run, and the rebasing engine rebased before the twin got there, so the comparison
            // below spans both rebases and positions past the ceiling.
            { assertTrue(twinPassedAt in 0 until PICKS / 2, "the twin, with no rebase, passed its ceiling in the first half: $twinPassedAt") },
            { assertTrue(rebasesBeforeTwinPassed >= 1, "the engine rebased before the twin passed it: $rebasesBeforeTwinPassed") },
            { assertTrue(rebases > rebasesBeforeTwinPassed, "and kept rebasing after: $rebases in all") },
            { assertEquals(PICKS, twinPicks, "the twin picked every step, past its ceiling too") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's, and the restored engine's its origin's") },
            // The step B twin (#3472, slice 1): the twin never raised an origin and its positions
            // passed 2⁶³ ticks, and the rebasing side met neither cause the design lets it differ by.
            { assertEquals(emptyMap(), raisedOrigins(twin.snapshot().ledger), "the twin never raised an origin") },
            { assertTrue(twinPeak >= LONG_LIMIT_TICKS, "the rig: the twin's positions passed 2⁶³ ticks ($twinPeak)") },
            { assertEquals(0, floorLandings, "the rebasing side landed no joiner at its floor") },
            { assertEquals(0, unobservedBelowZero, "the rebasing side clamped no never-observed child below zero") },
            // Rebased at a sixteenth of the ceiling, a position stays far below it.
            { assertTrue(highestShare < MAX_SHARE, "the highest effective position reached ${highestShare * 100}% of its ceiling") },
        )
    }

    /**
     * Returns in earnest: every lease charged half its reservation or cancelled outright, at a
     * reservation large enough that rebases fire, over a run whose lifetime returns stay well below
     * the bound #3360 tracks. Every step picks, and every pick matches a twin's that never rebases.
     */
    @Test
    fun leasesChargedBelowTheirReservationKeepPickingAcrossRebases() {
        val topology = draft("rt") { RETURN_LANES.forEach { (name, weight) -> lane(name, weight) } }
        val policy = LocalPolicy(defaultReservation = 1L)
        fun boot() = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val engine = boot()
        val twin = boot().apply { rebaseForTest = false }
        val lanes = RETURN_LANES.map { topology.groupId(it.first) }
        val random = Random(3360)
        var picks = 0
        var twinPicks = 0
        val divergences = ArrayList<String>()
        var unobservedBelowZero = 0
        var twinPeak = Int128.ZERO

        for (step in 0 until RETURN_PICKS) {
            val waiting = lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            unobservedBelowZero += neverObservedBelowZero(engine, waiting)
            if (step % SAMPLE_EVERY == 0) twinPeak = maxOf(twinPeak, peakEffectiveTicksAnywhere(twin))
            val cancel = step % 4 == 0
            val running = listOf(engine, twin)
            val outcomes = running.map { e ->
                val outcome = e.acquire(waiting, waiting, startedAt = step.toLong(), reservation = RETURN_RESERVATION)
                if (outcome is AcquireOutcome.Acquired) {
                    if (cancel) e.cancel(outcome.lease.id) else e.complete(outcome.lease.id, actualCost = RETURN_RESERVATION / 2, waiting = emptySet())
                }
                outcome
            }
            val outcome = outcomes[0]
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            picks++
            val twinOutcome = outcomes[1]
            check(twinOutcome is AcquireOutcome.Acquired) { "pick ${step + 1}: the twin answered $twinOutcome" }
            twinPicks++
            if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
            }
        }
        assertAll(
            { assertEquals(RETURN_PICKS, picks, "every step made one pick") },
            { assertTrue(engine.rebasesForTest >= 1, "the run rebased: ${engine.rebasesForTest}") },
            { assertEquals(RETURN_PICKS, twinPicks, "and so did the twin") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's") },
            { assertEquals(emptyMap(), raisedOrigins(twin.snapshot().ledger), "the twin never raised an origin") },
            // Not long enough for the twin to pass 2⁶³ ticks: its leases are a quarter of the largest
            // and half of each comes back, so its peak reaches about 1.4 · 10¹⁸ (JVM). Read, not asserted.
            { assertTrue(twinPeak > Int128.ZERO, "the twin climbed ($twinPeak)") },
            { assertEquals(0, engine.floorLandingsForTest, "the rebasing side landed no joiner at its floor") },
            { assertEquals(0, unobservedBelowZero, "the rebasing side clamped no never-observed child below zero") },
        )
    }

    /**
     * #3360's lane: a lease that reserves [LocalHeddle.MAX_RESERVATION] and charges 1 unit returns
     * almost all of it, at weight `3/7`. Returned service used to grow the gross position, which
     * nothing brought down, so this lane met [AcquireOutcome.PositionCeiling] after 4,681 picks
     * (measured on the slice 2–4 base, JVM). The ceiling is now measured on the raw position,
     * net of returns and relative to the parent's origin, and the gross is stored wide (#3353), so
     * the lane makes [RETURNED_LANE_PICKS] picks with no refusal. No pick refuses now, so the peak is
     * the witness: the gross passes the ceiling, and the effective position a pick reads stays below
     * it. Either the net-of-returns read or the rebase holds it there alone. With the rebase off the
     * raw position climbs one unit a pick, and with returns left out of the read the rebase brings
     * the lane back down, so the peak arm reds only with both removed (21× its ceiling, JVM).
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
     * light lane alone for [WIDE_ALONE] picks, then a waiting set drawn afresh each pick, leases at [LocalHeddle.MAX_RESERVATION] charged half or
     * cancelled. Rebased on each lane's own grid, the light lane could hold the front at its
     * threshold, and a heavy lane waking to it landed past its own ceiling. The run counts its own
     * picks and rebases, and pins the peak effective position below the ceiling.
     */
    @Test
    fun aWideSpreadNeverReachesTheCeiling() {
        val topology = draft("wide") {
            lane("w60", Weight.of(60L))
            lane("w30", Weight.of(30L))
            lane("w10", Weight.of(10L))
            lane("w1", Weight.ONE)
        }
        fun boot() = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val engine = boot()
        // The step B twin (#3472, slice 1): the same calls, the rebase off.
        val twin = boot().apply { rebaseForTest = false }
        val lanes = listOf("w60", "w30", "w10", "w1").map { topology.groupId(it) }
        val random = Random(3313)
        var picks = 0
        var peak = 0.0
        var twinPeak = Int128.ZERO
        var unobservedBelowZero = 0
        val divergences = ArrayList<String>()
        repeat(WIDE_PICKS) { step ->
            // The light lane alone first, long enough to climb toward its own threshold; then any set.
            val waiting = if (step < WIDE_ALONE) setOf(lanes[3]) else lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            unobservedBelowZero += neverObservedBelowZero(engine, waiting)
            val leaves = listOf(engine, twin).map { e ->
                val outcome = e.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
                if (step % 4 == 0) e.cancel(outcome.lease.id) else e.complete(outcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION / 2, waiting = emptySet())
                outcome.lease.leaf
            }
            if (leaves[0] != leaves[1] && divergences.size < MAX_REPORTED) divergences += "pick ${step + 1}: ${leaves[0].value} against the twin's ${leaves[1].value}"
            picks++
            peak = maxOf(peak, peakShare(engine))
            if (step % SAMPLE_EVERY == 0) twinPeak = maxOf(twinPeak, peakEffectiveTicksAnywhere(twin))
        }
        assertAll(
            { assertEquals(WIDE_PICKS, picks, "every step made one pick") },
            { assertTrue(engine.rebasesForTest >= 1, "the rig: the run rebased (${engine.rebasesForTest})") },
            { assertTrue(peak > 0.0 && peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's") },
            { assertEquals(emptyMap(), raisedOrigins(twin.snapshot().ledger), "the twin never raised an origin") },
            { assertTrue(twinPeak >= LONG_LIMIT_TICKS, "the rig: the twin's positions passed 2⁶³ ticks ($twinPeak)") },
            { assertEquals(0, engine.floorLandingsForTest, "the rebasing side landed no joiner at its floor") },
            { assertEquals(0, unobservedBelowZero, "the rebasing side clamped no never-observed child below zero") },
        )
    }

    /**
     * A parent driven only by charging calls, `admit`, `extend` and `complete`, and never picked at
     * (#3353 review, item I). Only a pick used to rebase, so this lane's position climbed by
     * [LocalHeddle.MAX_RESERVATION] per lease until `extend` refused with `PositionCeiling`. A
     * charging call now runs the same origin step at every parent on its path. The run counts its
     * own leases and rebases, and pins the peak effective position below the ceiling.
     */
    @Test
    fun aParentDrivenOnlyByChargingCallsNeverReachesTheCeiling() {
        val topology = draft("charged") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
        }
        fun boot() = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val engine = boot()
        // The step B twin (#3472, slice 1): the same calls, the rebase off. Nothing here picks, so
        // the two are compared on what a pick would do next: a preview of [PREVIEWED] picks with
        // both lanes waiting, after every lease.
        val twin = boot().apply { rebaseForTest = false }
        val a = topology.groupId("a")
        val both = setOf(a, topology.groupId("b"))
        var leases = 0
        var extended = 0
        var peak = 0.0
        var twinPeak = Int128.ZERO
        var unobservedBelowZero = 0
        var previewed = 0
        val divergences = ArrayList<String>()
        repeat(CHARGED_LEASES) { step ->
            unobservedBelowZero += neverObservedBelowZero(engine, setOf(a))
            for (e in listOf(engine, twin)) {
                val admitted = e.admit(a, waiting = setOf(a), startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                check(admitted is AcquireOutcome.Acquired) { "lease ${step + 1}: expected a lease, got $admitted" }
                if (step % 3 == 0) {
                    val out = e.extend(admitted.lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet())
                    check(out is LeaseOutcome.Done) { "lease ${step + 1}: extend answered $out" }
                    if (e === engine) extended++
                }
                e.complete(admitted.lease.id, actualCost = LocalHeddle.MAX_RESERVATION, waiting = emptySet())
            }
            leases++
            peak = maxOf(peak, peakShare(engine))
            if (step % SAMPLE_EVERY == 0) {
                val next = listOf(engine, twin).map { it.previewPicks(both.associateWith { PREVIEWED }, both, List(PREVIEWED) { LocalHeddle.MAX_RESERVATION }) }
                previewed += next[0].size
                if (next[0] != next[1] && divergences.size < MAX_REPORTED) divergences += "after lease ${step + 1}: ${next[0]} against the twin's ${next[1]}"
                twinPeak = maxOf(twinPeak, peakEffectiveTicksAnywhere(twin))
            }
        }
        twinPeak = maxOf(twinPeak, peakEffectiveTicksAnywhere(twin))
        assertAll(
            { assertEquals(CHARGED_LEASES, leases, "every step admitted and completed one lease") },
            { assertTrue(extended > 0, "the rig: leases were extended") },
            { assertTrue(engine.rebasesForTest >= 1, "the rig: the parent rebased (${engine.rebasesForTest})") },
            { assertTrue(peak > 0.0 && peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertEquals(PREVIEWED * (CHARGED_LEASES / SAMPLE_EVERY), previewed, "the rig: every preview picked its full count") },
            { assertEquals(emptyList<String>(), divergences, "every preview matched the twin's") },
            { assertEquals(emptyMap(), raisedOrigins(twin.snapshot().ledger), "the twin never raised an origin") },
            { assertTrue(twinPeak >= LONG_LIMIT_TICKS, "the rig: the twin's positions passed 2⁶³ ticks ($twinPeak)") },
            { assertEquals(0, engine.floorLandingsForTest, "the rebasing side landed no joiner at its floor") },
            { assertEquals(0, unobservedBelowZero, "the rebasing side clamped no never-observed child below zero") },
        )
    }

    /**
     * The highest effective position among the root's children in [engine], as a share of each
     * one's own ceiling: the position a pick reads, net of returns, plus its wake clamp
     * ([FairShareAllocator.peakPositionFraction]). Unlike [highestCeilingShare] it leaves the gross
     * out, which a lane that returns most of each lease carries past its ceiling by design.
     */
    private fun peakShare(engine: LocalHeddle): Double =
        engine.allocator.peakPositionFraction(engine.snapshot().ledger, engine.root) { Demand.NONE }

    /**
     * The largest share of its own ceiling any edge's position reads at in [engine]: its gross
     * position, or its effective one, the raw position plus the wake offset a pick reads.
     */
    private fun highestCeilingShare(engine: LocalHeddle): Double {
        val state = engine.snapshot()
        val ledger = state.ledger
        return ledger.allEdges().maxOf { edge ->
            val weight = checkNotNull(ledger.record(edge)).weight
            val origin = originTicks(ledger.origin(checkNotNull(ledger.record(edge)).parent), weight)
            val wideGross = ledger.gauge(edge).exactGrossTicksAt(ledger.baseIssuance(edge)) - origin
            // Wide: an idle lane sits as far below zero as the origin has risen past it (#3353).
            val raw = wideGross - (ledger.edge(edge)?.returned ?: 0L) * TICKS_PER_UNIT
            val effective = raw + (state.allocatorMemory.wakeOffsets[edge] ?: Int128.ZERO)
            maxOf(wideGross.toDouble(), effective.toDouble()) / positionCeiling(weight).toDouble()
        }
    }

    private companion object {
        const val PICKS = 100_000
        const val SAMPLE_EVERY = 100
        const val MAX_REPORTED = 5
        const val PARTIAL_EVERY = 16
        const val UNREACHABLE_EVERY = 50
        const val RESTORE_WINDOW = 10_000

        /** A rebase fires at a sixteenth of the ceiling; a quarter leaves room for the spread above it. */
        const val MAX_SHARE = 0.25

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

        /** Leases the charge-only run admits: past the 2¹⁵ that took a weight-1 lane to its ceiling. */
        const val CHARGED_LEASES = 40_000

        /** Picks each preview of the charge-only run looks ahead. */
        const val PREVIEWED = 4

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
