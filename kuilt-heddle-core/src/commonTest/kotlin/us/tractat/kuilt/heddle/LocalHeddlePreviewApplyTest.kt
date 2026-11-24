package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.ViewHistories.answers
import us.tractat.kuilt.heddle.ViewHistories.history
import us.tractat.kuilt.heddle.ViewHistories.question
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalHeddle.previewApply] and [LocalHeddleSnapshot.previewApply] (#3636): what an apply would do,
 * worked out on a copy, held to the apply itself on a twin engine over generated histories.
 */
class LocalHeddlePreviewApplyTest {

    private fun encode(snapshot: LocalHeddleSnapshot): String = Json.encodeToString(LocalHeddleSnapshot.serializer(), snapshot)

    /** Lanes a candidate may add: [FRESH] fits, [FINE]'s weight is too fine for one full lease, so its add is refused. */
    private val added = Topology(
        "root",
        listOf(
            Topology.Lane(FRESH),
            Topology.Group("acme", Weight.ONE, listOf(Topology.Group("batch", Weight.ONE, listOf(Topology.Lane(FINE))))),
        ),
    )
    private val fresh = added.groupId(FRESH)
    private val fine = added.groupId("acme", "batch", FINE)

    /** Every name the questions ask about: the history's, and the two lanes a candidate may add. */
    private val names = (ViewHistories.names + fresh + fine).sorted()

    /**
     * [history], and on every fourth seed a restart part-way: restored under the next epoch, then a
     * few more leases opened, so the engine holds leases from two epochs. Deterministic, so two
     * calls give twins.
     */
    private fun story(seed: Int, policy: LocalPolicy): LocalHeddle {
        val h = history(seed, policy)
        if (seed % 4 != 1) return h
        val restored = LocalHeddle.restore(h.persist(), policy, epoch = h.epoch + 1)
        val rng = Random(seed * 31 + 7)
        repeat(3) { step ->
            val waiting = ViewHistories.names.filter { rng.nextBoolean() }.toSet()
            restored.acquire(waiting, waiting, startedAt = 100L + step, reservation = 1L + rng.nextInt(3))
        }
        return restored
    }

    /**
     * A candidate tree that differs from the running one in random ways at once: `acme` and `y`
     * reweighted (moving the leases under them), `x` and `solo` removed (draining theirs), a ceiling
     * on `acme` or `solo`, a new template weight for `tenants` (moving `t1`'s leases), a [FRESH] lane
     * added, and a [FINE] lane the weight budget refuses.
     */
    private fun candidate(rng: Random): Topology {
        val acmeWeight = listOf(Weight.of(3, 2), Weight.of(5, 2), Weight.of(2))[rng.nextInt(3)]
        val batch = buildList {
            if (rng.nextInt(3) != 0) add(Topology.Lane("x"))
            add(Topology.Lane("y", if (rng.nextBoolean()) Weight.of(2) else Weight.of(3)))
            if (rng.nextInt(4) == 0) add(Topology.Lane(FINE, Weight.of(1, 30_721)))
        }
        val ceiling = { listOf(null, null, 0L, 1L, 2L)[rng.nextInt(5)] }
        return Topology(
            "root",
            listOfNotNull(
                Topology.Group(
                    "acme",
                    acmeWeight,
                    listOf(Topology.Group("batch", Weight.ONE, batch), Topology.Lane("interactive")),
                    maxConcurrent = ceiling(),
                ),
                if (rng.nextInt(3) != 0) Topology.Lane("solo", maxConcurrent = ceiling()) else null,
                Topology.Group("pool", Weight.ONE, emptyList(), template = Topology.ChildTemplate(Weight.ONE)),
                Topology.Group(
                    "tenants",
                    Weight.of(1, 2),
                    emptyList(),
                    template = Topology.ChildTemplate(if (rng.nextInt(4) == 0) Weight.of(2) else Weight.ONE),
                ),
                if (rng.nextInt(3) == 0) Topology.Lane(FRESH) else null,
            ),
        )
    }

    /**
     * Across random histories and candidates, the engine's preview is the apply: [TopologyPreview.result]
     * equals what [LocalHeddle.apply] returns on a twin at the same generation, and
     * [TopologyPreview.after] answers every question as the twin does once applied. The preview is a
     * read: the engine's revision, its snapshot, its persisted JSON and its state are as they were,
     * and [TopologyPreview.basis] is the revision it was taken at. The snapshot's preview equals the
     * engine's, with no basis.
     *
     * The controls each count a state the proof must reach, with a floor:
     * - a lease **moved** by the twin's apply (its path changed), since a preview over no leases, or
     *   over leases nothing moves, cannot tell real leases from counts;
     * - a [StepRefusal.Draining] and a [StepRefusal.Refused] in the result;
     * - a candidate that **changed** something (an applied step), and an answer that **differs**
     *   between the engine before and the preview's `after`, so neither is the running tree again;
     * - an `after` answer of [WaitExplanation.AtCeiling], so the ceilings are read;
     * - a preview on an engine sized with **slots** (`totalSlots > 0`) and one on supply laid down ahead;
     * - a **lease-less** fork, the snapshot with its leases dropped, as a fork over lease counts
     *   would see it, that answers differently: the proof rests on the leases;
     * - a **memory-less** fork, the snapshot with its wake memory dropped, that answers differently;
     * - a preview on an engine holding a lease opened **before a restore**.
     */
    @Test
    fun aPreviewIsTheApplyAndChangesNothing() {
        var moved = 0
        var draining = 0
        var refused = 0
        var changed = 0
        var differs = 0
        var atCeiling = 0
        var sized = 0
        var unsized = 0
        var leaselessDiffers = 0
        var memorylessDiffers = 0
        var fromBeforeARestore = 0
        for (seed in 0 until SEEDS) {
            val policy = LocalPolicy(defaultReservation = 2L + seed % 3)
            val h = story(seed, policy)
            val twin = story(seed, policy)
            val rng = Random(seed * 104_729 + 3)
            val new = candidate(rng)

            val revision = h.revision
            val persisted = h.persist()
            val json = encode(persisted)
            val state = h.snapshot()
            val questions = List(QUESTIONS) { question(rng, names) }
            val answersBefore = questions.map { h.answers(it) }
            val paths = state.leases.associate { it.id to twin.capturedPath(it.id) }

            val preview = h.previewApply(new, GENERATION)
            val fromSnapshot = persisted.previewApply(policy, new, GENERATION)
            val applied = twin.apply(new, GENERATION)

            val leaseless = LocalHeddleSnapshot(
                persisted.epoch, persisted.ledger, persisted.allocatorMemory, persisted.totalSlots, emptyList(), persisted.lastApplied,
            ).previewApply(policy, new, GENERATION)
            val memoryless = LocalHeddleSnapshot(
                persisted.epoch, persisted.ledger, AllocatorMemory.EMPTY, persisted.totalSlots, persisted.open, persisted.lastApplied,
            ).previewApply(policy, new, GENERATION)

            val afterAnswers = questions.map { preview.after.answers(it) }
            assertAll(
                { assertEquals(applied, preview.result, "seed $seed: the preview's result is the apply's") },
                { assertEquals(questions.map { twin.answers(it) }, afterAnswers, "seed $seed: after answers as the engine does once applied") },
                { assertEquals(revision, preview.basis, "seed $seed: the basis is the revision the preview was taken at") },
                { assertEquals(revision, h.revision, "seed $seed: the revision has not moved") },
                { assertEquals(persisted, h.persist(), "seed $seed: the engine persists as it did") },
                { assertEquals(json, encode(h.persist()), "seed $seed: to the same JSON") },
                { assertEquals(state, h.snapshot(), "seed $seed: its state is as it was") },
                { assertEquals(answersBefore, questions.map { h.answers(it) }, "seed $seed: and it answers as it did") },
                { assertEquals(preview.result, fromSnapshot.result, "seed $seed: the snapshot's preview has the engine's result") },
                { assertEquals(afterAnswers, questions.map { fromSnapshot.after.answers(it) }, "seed $seed: and answers as the engine's does") },
                { assertNull(fromSnapshot.basis, "seed $seed: a snapshot's preview has no basis") },
                { assertEquals(json, encode(persisted), "seed $seed: the snapshot the preview was handed encodes as it did") },
            )

            if (state.leases.any { twin.capturedPath(it.id) != paths[it.id] }) moved++
            if (preview.result.refusals.any { it is StepRefusal.Draining }) draining++
            if (preview.result.refusals.any { it is StepRefusal.Refused }) refused++
            if (preview.result.applied.isNotEmpty()) changed++
            if (afterAnswers != answersBefore) differs++
            if (afterAnswers.flatten().any { it is WaitExplanation.AtCeiling }) atCeiling++
            if (persisted.totalSlots > 0L) sized++ else unsized++
            if (leaseless.result != applied || questions.map { leaseless.after.answers(it) } != afterAnswers) leaselessDiffers++
            if (questions.map { memoryless.after.answers(it) } != afterAnswers) memorylessDiffers++
            if (state.leases.any { it.id.epoch < h.epoch }) fromBeforeARestore++
        }
        val counts = "moved=$moved draining=$draining refused=$refused changed=$changed differs=$differs atCeiling=$atCeiling " +
            "sized=$sized unsized=$unsized leaseless=$leaselessDiffers memoryless=$memorylessDiffers fromBeforeARestore=$fromBeforeARestore"
        assertAll(
            { assertTrue(moved >= FLOOR, "a lease moved: $counts") },
            { assertTrue(draining >= FLOOR, "a change drained: $counts") },
            { assertTrue(refused >= FLOOR, "a change was refused: $counts") },
            { assertTrue(changed >= FLOOR, "a candidate changed something: $counts") },
            { assertTrue(differs >= FLOOR, "after answered unlike the engine before: $counts") },
            { assertTrue(atCeiling >= FLOOR, "a ceiling was read: $counts") },
            { assertTrue(sized >= FLOOR && unsized >= FLOOR, "engines with and without slots: $counts") },
            { assertTrue(leaselessDiffers >= FLOOR, "the control: a lease-less fork answers differently: $counts") },
            { assertTrue(memorylessDiffers >= FLOOR, "the control: a memory-less fork answers differently: $counts") },
            { assertTrue(fromBeforeARestore >= FLOOR, "a lease opened before a restore was open: $counts") },
        )
    }

    /**
     * The generation names the edges a reload adds, so a preview holds only for an apply at the same
     * one: at the same generation the two agree on every applied step, edge ids included, and at a
     * different one some seed's applied steps differ. The control is what makes the "same
     * generation" rule in [LocalHeddle.previewApply]'s KDoc a requirement rather than a courtesy.
     */
    @Test
    fun aPreviewHoldsForAnApplyAtTheSameGeneration() {
        var otherGenerationDiffers = 0
        for (seed in 0 until SEEDS) {
            val policy = LocalPolicy(defaultReservation = 2L + seed % 3)
            val new = candidate(Random(seed * 104_729 + 3))
            val preview = story(seed, policy).previewApply(new, GENERATION)
            assertEquals(story(seed, policy).apply(new, GENERATION).applied, preview.result.applied, "seed $seed: the same edges at the same generation")
            if (story(seed, policy).apply(new, GENERATION + 5L).applied != preview.result.applied) otherGenerationDiffers++
        }
        assertTrue(otherGenerationDiffers >= FLOOR, "the control: another generation names other edges on some seed: $otherGenerationDiffers")
    }

    private companion object {
        const val SEEDS = 60
        const val QUESTIONS = 3
        const val FLOOR = 10

        /** Above both generations [history] applies at. */
        const val GENERATION = 3L
        const val FRESH = "fresh"
        const val FINE = "fine"
    }
}
