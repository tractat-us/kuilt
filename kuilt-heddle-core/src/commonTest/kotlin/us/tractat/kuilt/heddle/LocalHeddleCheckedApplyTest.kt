package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ViewHistories.history
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The checked [LocalHeddle.apply] and [LocalHeddle.previewApply] (#3647): an edit saves only while
 * the running tree is still the one it started from, compared as trees, never as revisions.
 */
class LocalHeddleCheckedApplyTest {

    /**
     * Two admins load one tree from a live engine and each saves an edit of it. Leases, charges,
     * attaches and detaches run between the load and each save. The first save applies; the second
     * is refused as [ApplyOutcome.TreeChanged] naming the first admin's tree, and changes nothing.
     * Each checked preview agrees with the save after it.
     *
     * The controls each count a state the proof must reach, with a floor:
     * - **refused**: the second save reached the refusing arm, so the property is not green by
     *   never reaching it;
     * - **revisionMoved**: the engine's revision moved between the load and the first save, which
     *   is where a guard keyed on [LocalHeddle.revision] refuses a save it should apply;
     * - **leased** and **attached**: a lease was granted, and an attach or detach applied, in that
     *   window, so neither is merely absent;
     * - **wouldUndo**: the second edit, applied unchecked, would have removed the first admin's
     *   lane, so the refusal is what keeps it.
     */
    @Test
    fun ofTwoEditsFromOneTreeTheFirstAppliesAndTheSecondIsRefusedWithNothingLost() {
        var refused = 0
        var revisionMoved = 0
        var leased = 0
        var attached = 0
        var wouldUndo = 0
        for (seed in 0 until SEEDS) {
            val policy = LocalPolicy(defaultReservation = 2L + seed % 3)
            val h = history(seed, policy)
            val rng = Random(seed * 7_919 + 11)

            val startedFrom = h.topology
            val loadedAt = h.revision
            val first = edit(rng, startedFrom, lane = "first")
            val second = edit(rng, startedFrom, lane = "second")

            val pooled = mutableSetOf<String>()
            val before = churn(h, rng, pooled)
            if (before.leased > 0) leased++
            if (before.attached > 0) attached++
            if (h.revision != loadedAt) revisionMoved++

            val preview = assertIs<PreviewOutcome.Previewed>(h.previewApply(first, GENERATION, startedFrom), "seed $seed: the first preview goes ahead")
            val saved = assertIs<ApplyOutcome.Applied>(h.apply(first, GENERATION, startedFrom), "seed $seed: the first save applies")
            assertAll(
                { assertEquals(preview.preview.result, saved.result, "seed $seed: the save does what the preview showed") },
                { assertEquals(first, h.topology, "seed $seed: the first edit is the running tree") },
            )

            churn(h, rng, pooled)
            val persisted = h.persist()
            val state = h.snapshot()
            val revision = h.revision
            val previewed = h.previewApply(second, GENERATION + 1L, startedFrom)
            val outcome = h.apply(second, GENERATION + 1L, startedFrom)
            assertAll(
                { assertEquals(ApplyOutcome.TreeChanged(first), previewed, "seed $seed: the second preview refuses as the save will") },
                { assertEquals(ApplyOutcome.TreeChanged(first), outcome, "seed $seed: the second save is refused, naming the first edit") },
                { assertEquals(first, h.topology, "seed $seed: the first edit still runs") },
                { assertEquals(persisted, h.persist(), "seed $seed: the refusal persists nothing new") },
                { assertEquals(state, h.snapshot(), "seed $seed: its state is as it was") },
                { assertEquals(revision, h.revision, "seed $seed: the revision has not moved") },
            )
            if (outcome is ApplyOutcome.TreeChanged) refused++
            if (first.changesTo(second).any { it is TopologyChange.Removed && it.node.name == "first" }) wouldUndo++

            // Saved again from the tree it was refused with, the second edit applies.
            assertIs<ApplyOutcome.Applied>(h.apply(second, GENERATION + 1L, startedFrom = first), "seed $seed: saved from the running tree, it applies")
            assertEquals(second, h.topology, "seed $seed")
        }
        val counts = "refused=$refused revisionMoved=$revisionMoved leased=$leased attached=$attached wouldUndo=$wouldUndo"
        assertAll(
            { assertEquals(SEEDS, refused, "every second save reached the refusing arm: $counts") },
            { assertTrue(revisionMoved >= FLOOR, "the revision moved before a save that still applied: $counts") },
            { assertTrue(leased >= FLOOR, "a lease was granted between load and save: $counts") },
            { assertTrue(attached >= FLOOR, "an attach or detach applied between load and save: $counts") },
            { assertEquals(SEEDS, wouldUndo, "the control: unchecked, the second edit would undo the first: $counts") },
        )
    }

    /** [LocalHeddle.attach] and [LocalHeddle.detach] change the engine but not its tree, so a save after them applies. */
    @Test
    fun attachAndDetachDoNotRefuseASave() {
        val heddle = LocalHeddle.fromTopology(ViewHistories.before, LocalPolicy(defaultReservation = 3L), epoch = 0L).heddle
        val startedFrom = heddle.topology
        val loadedAt = heddle.revision
        val attach = heddle.attach(listOf("pool"), "ana", generation = 1L)
        val detach = heddle.detach(listOf("pool"), "ana")
        val outcome = heddle.apply(ViewHistories.after, generation = 2L, startedFrom = startedFrom)
        assertAll(
            { assertTrue(attach.applied.isNotEmpty() && detach.applied.isNotEmpty(), "the rig: both applied a step") },
            { assertNotEquals(loadedAt, heddle.revision, "the rig: the revision moved") },
            { assertIs<ApplyOutcome.Applied>(outcome) },
            { assertEquals(ViewHistories.after, heddle.topology) },
        )
    }

    /** The unchecked apply moves nothing a checked one reads: a save checked against a tree some unchecked apply replaced is refused. */
    @Test
    fun anUncheckedApplyBetweenRefusesTheCheckedSave() {
        val heddle = LocalHeddle.fromTopology(ViewHistories.before, LocalPolicy(defaultReservation = 3L), epoch = 0L).heddle
        val startedFrom = heddle.topology
        heddle.apply(ViewHistories.after, generation = 1L)
        assertEquals(ApplyOutcome.TreeChanged(ViewHistories.after), heddle.apply(startedFrom, generation = 2L, startedFrom = startedFrom))
    }

    /**
     * A tree is compared whole and in the order written, so reordering siblings refuses a save, as
     * [ApplyOutcome.TreeChanged] documents, though [Topology.changesTo] lists no change for it.
     */
    @Test
    fun aReorderRefusesASaveThoughItListsNoChange() {
        val tree = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(2)), Topology.Lane("light")))
        val reordered = Topology("pool", tree.children.reversed())
        val heddle = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L).heddle
        heddle.apply(reordered, generation = 1L)
        assertAll(
            { assertEquals(emptyList(), tree.changesTo(reordered), "the rig: nothing heddle reads changed") },
            { assertEquals(ApplyOutcome.TreeChanged(reordered), heddle.apply(tree, generation = 2L, startedFrom = tree)) },
        )
    }

    /** A caller's mistake throws whether or not the tree changed: a stale tree never hides it. */
    @Test
    fun aWrongRootOrGenerationThrowsEvenFromAStaleTree() {
        val heddle = LocalHeddle.fromTopology(ViewHistories.before, LocalPolicy(defaultReservation = 3L), epoch = 0L).heddle
        val stale = ViewHistories.after
        val otherRoot = Topology("elsewhere", listOf(Topology.Lane("x")))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { heddle.apply(otherRoot, generation = 1L, startedFrom = stale) } },
            { assertFailsWith<IllegalArgumentException> { heddle.apply(ViewHistories.after, generation = -1L, startedFrom = stale) } },
            { assertFailsWith<IllegalArgumentException> { heddle.previewApply(otherRoot, generation = 1L, startedFrom = stale) } },
            { assertFailsWith<IllegalArgumentException> { heddle.previewApply(ViewHistories.after, generation = -1L, startedFrom = stale) } },
            { assertEquals(ViewHistories.before, heddle.topology) },
        )
    }

    /**
     * An admin's edit of [from]: [from] with `acme` reweighted at random, `solo` removed on some
     * seeds, and a lane named [lane] added, so it is never [from] and never another admin's edit.
     * `pool` and `tenants` keep their templates, so the churn can still attach under `pool`.
     */
    private fun edit(rng: Random, from: Topology, lane: String): Topology {
        val acmeWeight = listOf(Weight.of(3, 2), Weight.of(5, 2), Weight.of(2))[rng.nextInt(3)]
        val children = from.children.mapNotNull { node ->
            when {
                node.name == "acme" -> (node as Topology.Group).copy(weight = acmeWeight)
                node.name == "solo" && rng.nextInt(3) == 0 -> null
                else -> node
            }
        }
        return Topology(from.root, children + Topology.Lane(lane))
    }

    private class Churn(val leased: Int, val attached: Int)

    /**
     * A few leases opened, extended, completed and cancelled, and children attached and detached
     * under `pool`; [pooled] is the names attached there, carried from one call to the next.
     */
    private fun churn(h: LocalHeddle, rng: Random, pooled: MutableSet<String>): Churn {
        var leased = 0
        var attached = 0
        repeat(CHURN) { step ->
            val open = h.snapshot().leases
            when {
                rng.nextInt(5) == 0 -> {
                    val result = if (pooled.isNotEmpty() && rng.nextBoolean()) {
                        val name = pooled.sorted()[rng.nextInt(pooled.size)]
                        pooled -= name
                        h.detach(listOf("pool"), name)
                    } else {
                        val name = "p${rng.nextInt(4)}"
                        pooled += name
                        h.attach(listOf("pool"), name, generation = 10L + step)
                    }
                    if (result.applied.isNotEmpty()) attached++
                }
                open.size < 3 || rng.nextInt(3) == 0 -> {
                    val waiting = ViewHistories.names.filter { rng.nextBoolean() }.toSet()
                    if (h.acquire(waiting, waiting, startedAt = 200L + step, reservation = 1L + rng.nextInt(3)) is AcquireOutcome.Acquired) leased++
                }
                rng.nextInt(5) == 0 -> h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(3), waiting = emptySet())
                rng.nextInt(6) == 0 -> h.cancel(open[rng.nextInt(open.size)].id)
                else -> h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(6).toLong(), waiting = emptySet())
            }
        }
        return Churn(leased, attached)
    }

    private companion object {
        const val SEEDS = 60
        const val CHURN = 12
        const val FLOOR = 10

        /** Above both generations [history] applies at. */
        const val GENERATION = 3L
    }
}
