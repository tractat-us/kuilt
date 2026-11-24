package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.ViewHistories.answers
import us.tractat.kuilt.heddle.ViewHistories.before
import us.tractat.kuilt.heddle.ViewHistories.history
import us.tractat.kuilt.heddle.ViewHistories.question
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LocalHeddleSnapshot.view] (#3321): a reader holding only a snapshot asks `previewPicks`, `explain`
 * and `entitlement` and gets the engine's own answers, with no engine and nothing moved.
 */
class LocalHeddleViewTest {

    private fun encode(snapshot: LocalHeddleSnapshot): String = Json.encodeToString(LocalHeddleSnapshot.serializer(), snapshot)

    /**
     * Across random histories and questions, a snapshot's view answers exactly as the engine that
     * took it, and as an engine restored from it under `epoch + 1`, the workaround the view replaces.
     * Neither the engine nor the snapshot changes. Two control arms keep it honest: a view over the
     * same snapshot with its wake memory forgotten, and one with its capacity forgotten, each answer
     * differently on some seed, so the match rests on the view reading both.
     */
    @Test
    fun aViewAnswersAsTheEngineAndAsARestore() {
        val kinds = HashSet<WaitExplanation.Kind>()
        var picked = 0
        var memoryMattered = 0
        var capacityMattered = 0
        for (seed in 0 until SEEDS) {
            val policy = LocalPolicy(defaultReservation = 2L + seed % 3)
            val h = history(seed, policy)
            val rng = Random(seed * 7919 + 1)
            repeat(QUESTIONS) { round ->
                val q = question(rng)
                val persisted = h.persist()
                val json = encode(persisted)
                val engineState = h.snapshot()

                val fromEngine = h.answers(q)
                val fromView = persisted.view(policy).answers(q)
                val fromRestore = LocalHeddle.restore(persisted, policy, epoch = persisted.epoch + 1).answers(q)

                val forgetful = LocalHeddleSnapshot(
                    persisted.epoch, persisted.ledger, AllocatorMemory.EMPTY, persisted.totalSlots, persisted.open, persisted.lastApplied,
                )
                if (forgetful.view(policy).answers(q) != fromEngine) memoryMattered++
                val unsized = LocalHeddleSnapshot(
                    persisted.epoch, persisted.ledger, persisted.allocatorMemory, 0L, persisted.open, persisted.lastApplied,
                )
                if (unsized.view(policy).answers(q) != fromEngine) capacityMattered++

                assertAll(
                    { assertEquals(fromEngine, fromView, "seed $seed, question $round: the view answers as the engine") },
                    { assertEquals(fromEngine, fromRestore, "seed $seed, question $round: and as a restore under epoch + 1") },
                    { assertEquals(persisted, h.persist(), "seed $seed, question $round: the engine is as it was") },
                    { assertEquals(engineState, h.snapshot(), "seed $seed, question $round: its state too") },
                    { assertEquals(json, encode(persisted), "seed $seed, question $round: and so is the snapshot") },
                )
                fromEngine.filterIsInstance<WaitExplanation>().mapTo(kinds) { it.kind }
                picked += fromEngine.take(2).sumOf { (it as List<*>).size }
            }
        }
        assertAll(
            { assertTrue(picked > SEEDS, "the previews picked something: $picked") },
            {
                assertTrue(
                    kinds.containsAll(
                        listOf(
                            WaitExplanation.Kind.NOT_WAITING, WaitExplanation.Kind.NOT_ELIGIBLE, WaitExplanation.Kind.NO_LIVE_LINEAGE,
                            WaitExplanation.Kind.SUPPLY_EXHAUSTED, WaitExplanation.Kind.BEHIND_AT_LEVEL, WaitExplanation.Kind.WOULD_BE_PICKED_NEXT,
                        ),
                    ),
                    "every explanation but a ceiling came up: $kinds",
                )
            },
            { assertTrue(memoryMattered > 0, "the control: forgetting the wake memory changes some answer: $memoryMattered") },
            { assertTrue(capacityMattered > 0, "the control: forgetting the capacity changes some answer: $capacityMattered") },
        )
    }

    /**
     * `pool` is a leaf the topology gives a template and declares no lane: a view turns
     * it away as the engine does, because it leases by the topology the snapshot kept. The rig: the
     * ledger alone reads `pool` as a leaf with a live lineage.
     */
    @Test
    fun aViewLeasesByTheTopologyTheSnapshotKept() {
        val policy = LocalPolicy(defaultReservation = 1L)
        val h = LocalHeddle.bootWhole(before, policy, epoch = 0L).also { it.reconcileCapacity(2L) }
        val pool = before.groupId("pool")
        val view = h.persist().view(policy)
        val ledger = h.snapshot().ledger
        assertAll(
            { assertTrue(ledger.isLeaf(pool) && ledger.lineageOf(pool) != null, "the rig: the ledger reads pool as a reachable leaf") },
            { assertEquals(WaitExplanation.NoLiveLineage(pool), view.explain(pool, setOf(pool), setOf(pool))) },
            { assertEquals(Rational.ZERO, view.entitlement(pool, setOf(pool), totalSlots = 4L)) },
            { assertEquals(emptyList(), view.previewPicks(mapOf(pool to 3), setOf(pool), listOf(1L, 1L, 1L))) },
            { assertEquals(h.explain(pool, setOf(pool), setOf(pool)), view.explain(pool, setOf(pool), setOf(pool))) },
        )
    }

    /** A view sizes the snapshot's capacity with the policy it is given, as a restore does, and refuses one that cannot. */
    @Test
    fun aViewRefusesAPolicyThatCannotSizeTheCapacity() {
        val h = LocalHeddle.bootWhole(before, LocalPolicy(defaultReservation = 1L), epoch = 0L).also { it.reconcileCapacity(1_000L) }
        val greedy = LocalPolicy(defaultReservation = LocalHeddle.MAX_RESERVATION, supply = SupplyPolicy(lowWatermarkSlots = 1L, targetSlots = 1L shl 20))
        val persisted = h.persist()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { persisted.view(greedy) } },
            { assertFailsWith<IllegalArgumentException> { LocalHeddle.restore(persisted, greedy, epoch = 1L) } },
        )
    }

    private companion object {
        const val SEEDS = 60
        const val QUESTIONS = 4
    }
}
