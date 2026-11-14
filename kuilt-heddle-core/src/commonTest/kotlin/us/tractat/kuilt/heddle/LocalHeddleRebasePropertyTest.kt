package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
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
 * A twin engine with the rebase switched off makes the same calls. It must refuse at the ceiling
 * inside the run, which is what proves the arm needs the rebase at all, and every pick it makes
 * before that must be the rebasing engine's pick: the rebase moves every competing child by the
 * same virtual time, so the order cannot change. Any divergence means the rebase is not exact.
 *
 * Halfway through, the rebasing engine is persisted, encoded, decoded and restored, and the engine
 * it was taken from keeps running beside the restored one for [RESTORE_WINDOW] picks, which must
 * match: a raised fold and an absorbed offset survive the round trip.
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
        fun boot() = LocalHeddle.fromTopology(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
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
        var twinRefusedAt = -1
        var rebasesBeforeTwinRefused = -1
        var besidePicks = 0
        val divergences = ArrayList<String>()
        var highestShare = 0.0

        for (step in 0 until PICKS) {
            val waiting = leaves.filter { random.nextBoolean() }.ifEmpty { listOf(leaves[random.nextInt(leaves.size)]) }.toSet()
            val partial = step % PARTIAL_EVERY == 0
            val askUnreachable = step % UNREACHABLE_EVERY == 0
            val running = listOfNotNull(engine, beside, twin.takeIf { twinRefusedAt < 0 })
            val outcomes = running.map { e ->
                val unreachableOutcome = if (askUnreachable) e.acquire(waiting + unreachable, setOf(unreachable), startedAt = step.toLong()) else null
                val outcome = try {
                    e.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                } catch (ex: ArithmeticException) {
                    throw AssertionError("pick ${step + 1} threw $ex", ex)
                }
                if (outcome is AcquireOutcome.Acquired) {
                    val cost = if (partial) LocalHeddle.MAX_RESERVATION / 2 else LocalHeddle.MAX_RESERVATION
                    e.complete(outcome.lease.id, actualCost = cost)
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
            if (twinRefusedAt < 0) {
                when (val twinOutcome = outcomes.last().second) {
                    is AcquireOutcome.Acquired -> {
                        twinPicks++
                        if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                            divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                        }
                    }
                    is AcquireOutcome.PositionCeiling -> {
                        twinRefusedAt = step
                        rebasesBeforeTwinRefused = rebases + engine.rebasesForTest
                    }
                    else -> error("pick ${step + 1}: the twin answered $twinOutcome")
                }
            }

            if (step % SAMPLE_EVERY == 0) highestShare = maxOf(highestShare, highestCeilingShare(engine))
            if (step == PICKS / 2) {
                rebases += engine.rebasesForTest
                val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), engine.persist())
                val decoded = LocalHeddle.decode(bytes, Cbor)
                check(decoded is RestoreResult.Restored) { "the snapshot did not decode: $decoded" }
                beside = engine
                engine = LocalHeddle.restore(decoded.snapshot, policy, epoch = 2L)
                restored++
            }
        }
        rebases += engine.rebasesForTest
        highestShare = maxOf(highestShare, highestCeilingShare(engine))

        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(PICKS / UNREACHABLE_EVERY, refusedAsUnreachable, "every ask for the unreachable group was refused") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            { assertEquals(RESTORE_WINDOW, besidePicks, "the engine it was taken from ran beside the restored one") },
            // The rig, twice over: without the rebase the run reaches the ceiling, and the rebasing
            // engine rebased before the twin got there, so the comparison below spans rebases.
            { assertTrue(twinRefusedAt >= 0, "the twin, with no rebase, refused at its ceiling within $PICKS picks") },
            { assertTrue(rebasesBeforeTwinRefused >= 1, "the engine rebased before the twin refused: $rebasesBeforeTwinRefused") },
            { assertTrue(rebases > rebasesBeforeTwinRefused, "and kept rebasing after: $rebases in all") },
            { assertEquals(twinRefusedAt, twinPicks, "the twin picked every step until it refused") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's, and the restored engine's its origin's") },
            // Rebased at a sixteenth of the ceiling, a position stays far below it.
            { assertTrue(highestShare < MAX_SHARE, "the highest effective position reached ${highestShare * 100}% of its ceiling") },
        )
    }

    /**
     * Returns in earnest: every lease charged half its reservation or cancelled outright, at a
     * reservation large enough that rebases fire, over a run whose lifetime returns stay well below
     * the bound #3360 tracks. Every step picks, and every pick matches a twin's until that twin, if
     * ever, refuses.
     */
    @Test
    fun leasesChargedBelowTheirReservationKeepPickingAcrossRebases() {
        val topology = draft("rt") { RETURN_LANES.forEach { (name, weight) -> lane(name, weight) } }
        val policy = LocalPolicy(defaultReservation = 1L)
        fun boot() = LocalHeddle.fromTopology(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val engine = boot()
        val twin = boot().apply { rebaseForTest = false }
        val lanes = RETURN_LANES.map { topology.groupId(it.first) }
        val random = Random(3360)
        var picks = 0
        var twinRefusedAt = -1
        val divergences = ArrayList<String>()

        for (step in 0 until RETURN_PICKS) {
            val waiting = lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            val cancel = step % 4 == 0
            val running = listOfNotNull(engine, twin.takeIf { twinRefusedAt < 0 })
            val outcomes = running.map { e ->
                val outcome = e.acquire(waiting, waiting, startedAt = step.toLong(), reservation = RETURN_RESERVATION)
                if (outcome is AcquireOutcome.Acquired) {
                    if (cancel) e.cancel(outcome.lease.id) else e.complete(outcome.lease.id, actualCost = RETURN_RESERVATION / 2)
                }
                outcome
            }
            val outcome = outcomes[0]
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            picks++
            if (twinRefusedAt < 0) {
                when (val twinOutcome = outcomes[1]) {
                    is AcquireOutcome.Acquired -> if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                        divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                    }
                    is AcquireOutcome.PositionCeiling -> twinRefusedAt = step
                    else -> error("pick ${step + 1}: the twin answered $twinOutcome")
                }
            }
        }
        assertAll(
            { assertEquals(RETURN_PICKS, picks, "every step made one pick") },
            { assertTrue(engine.rebasesForTest >= 1, "the run rebased: ${engine.rebasesForTest}") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's while it picked") },
        )
    }

    /**
     * The largest share of its own ceiling any edge's position reads at in [engine]: its gross
     * position, or its effective one, the raw position plus the wake offset a pick reads.
     */
    private fun highestCeilingShare(engine: LocalHeddle): Double {
        val state = engine.snapshot()
        val ledger = state.ledger
        return ledger.allEdges().maxOf { edge ->
            val weight = checkNotNull(ledger.record(edge)).weight
            val gross = ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), weight)
            val raw = netTicks(gross, ledger.edge(edge)?.returned ?: 0L, weight)
            val offset = state.allocatorMemory.wakeOffsets[edge]?.let { ticksAt(it, weight) } ?: 0L
            maxOf(gross, raw + offset).toDouble() / positionCeiling(weight).toDouble()
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
        const val RETURN_RESERVATION = 1L shl 42
        val RETURN_LANES = listOf(
            "p" to Weight.ONE,
            "q" to Weight.of(3, 7),
            "r" to Weight.of(2),
        )
    }
}
