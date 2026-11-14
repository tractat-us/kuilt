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
 * Seven lanes with weights far apart and denominators coprime with each other, every lease at
 * [LocalHeddle.MAX_RESERVATION] and charged in full, under a waiting set drawn afresh each step. At
 * that size the heaviest lane's position reaches its ceiling within a few tens of thousands of picks,
 * so a run of [PICKS] passes it several times over.
 *
 * A twin engine with the rebase switched off runs the same calls. It must refuse at the ceiling
 * inside the run, which is what proves the arm needs the rebase at all, and every pick it makes
 * before that must be the rebasing engine's pick: the rebase moves every competing child by the
 * same virtual time, so the order cannot change. Any divergence means the rebase is not exact.
 *
 * Halfway through, the rebasing engine is persisted, encoded, decoded and restored, so a raised
 * fold survives the round trip and the run carries on from it.
 *
 * The generator walks only [List]s it built itself, in declaration order, so the same seed takes
 * the same trajectory on every target.
 */
class LocalHeddleRebasePropertyTest {

    @Test
    fun aBusyParentRebasesBeforeItsCeilingAndPicksAsAnUnrebasedTwin() {
        val topology = draft("rb") { LANES.forEach { (name, weight) -> lane(name, weight) } }
        val policy = LocalPolicy(defaultReservation = 1L)
        fun boot() = LocalHeddle.fromTopology(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        var engine = boot()
        val twin = boot().apply { rebaseForTest = false }
        val lanes = LANES.map { topology.groupId(it.first) }
        val random = Random(3329)

        var picks = 0
        var restored = 0
        var rebases = 0
        var twinPicks = 0
        var twinRefusedAt = -1
        var rebasesBeforeTwinRefused = -1
        val divergences = ArrayList<String>()
        var highestShare = 0.0

        for (step in 0 until PICKS) {
            val waiting = lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }.toSet()
            val outcome = try {
                engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            } catch (e: ArithmeticException) {
                throw AssertionError("pick ${step + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            engine.complete(outcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION)
            picks++

            if (twinRefusedAt < 0) {
                when (val twinOutcome = twin.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                    is AcquireOutcome.Acquired -> {
                        twinPicks++
                        if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                            divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                        }
                        twin.complete(twinOutcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION)
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
                engine = LocalHeddle.restore(decoded.snapshot, policy, epoch = 2L)
                restored++
            }
        }
        rebases += engine.rebasesForTest
        highestShare = maxOf(highestShare, highestCeilingShare(engine))

        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            // The rig, twice over: without the rebase the run reaches the ceiling, and the rebasing
            // engine rebased before the twin got there, so the comparison below spans rebases.
            { assertTrue(twinRefusedAt >= 0, "the twin, with no rebase, refused at its ceiling within $PICKS picks") },
            { assertTrue(rebasesBeforeTwinRefused >= 1, "the engine rebased before the twin refused: $rebasesBeforeTwinRefused") },
            { assertTrue(rebases > rebasesBeforeTwinRefused, "and kept rebasing after: $rebases in all") },
            { assertEquals(twinRefusedAt, twinPicks, "the twin picked every step until it refused") },
            { assertEquals(emptyList<String>(), divergences, "every pick before the twin refused was the twin's pick") },
            // Rebased at a sixteenth of the ceiling, a position stays far below it.
            { assertTrue(highestShare < MAX_SHARE, "the highest position reached ${highestShare * 100}% of its ceiling") },
        )
    }

    /** The largest share of its own ceiling any lane's gross position reads at in [engine]. */
    private fun highestCeilingShare(engine: LocalHeddle): Double {
        val ledger = engine.snapshot().ledger
        return ledger.allEdges().maxOf { edge ->
            val weight = checkNotNull(ledger.record(edge)).weight
            ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), weight).toDouble() / positionCeiling(weight).toDouble()
        }
    }

    private companion object {
        const val PICKS = 100_000
        const val SAMPLE_EVERY = 100
        const val MAX_REPORTED = 5

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
    }
}
