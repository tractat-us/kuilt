package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Properties of [Topology.diff] over seeded random trees: depth up to 3, up to 3 children per
 * group, names from a four-name list so that edits collide with earlier names, weights 1 to 4.
 *
 * Every draw is from a `List` this file built, never from a `Set` or a `Map` view, so a seed walks
 * the same trajectory on every target. Each property counts the cases it is meant to exercise and
 * asserts the counts, so a generator that drifted towards trivial histories reds instead of passing.
 */
class TopologyDiffPropertyTest {

    private val seeds = 200
    private val names = listOf("a", "b", "c", "default")

    // ── properties ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun applyingTheDiffMatchesBootingTheTarget() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var cuts = 0
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val from = randomTree(r)
            val to = if (r.nextInt(5) == 0) randomTree(r) else mutate(from, r)
            val ledger = bootFrom(from, generation = r.nextLong(0, 3))
            val generation = r.nextLong(0, 5)
            val steps = Topology.diff(to, ledger, generation)
            tally.count(steps, ledger, to)
            val applied = applyAll(steps, ledger)
            failures += checkConverged(seed, to, applied.ledger, applied.refused)
            // Re-entrance: any prefix applied, the diff is exactly the rest.
            for (cut in 0..steps.size) {
                val partial = applyAll(steps.take(cut), ledger)
                val rest = Topology.diff(to, partial.ledger, generation)
                if (partial.refused.isNotEmpty() || rest != steps.drop(cut)) failures += "seed $seed cut $cut: $rest != ${steps.drop(cut)}"
                cuts++
            }
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            { assertTrue(tally.reweights >= 50, "reweights ${tally.reweights}") },
            { assertTrue(tally.removals >= 150, "removals ${tally.removals}") },
            { assertTrue(tally.additions >= 200, "additions ${tally.additions}") },
            { assertTrue(cuts >= 1_000, "cuts $cuts") },
        )
    }

    @Test
    fun generationsNeverCollideAcrossAChain() {
        val failures = ArrayList<String>()
        var prepares = 0
        var reverts = 0
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val history = arrayListOf(randomTree(r))
            var ledger = bootFrom(history.last(), generation = r.nextLong(0, 3))
            val seen = ledger.allEdges().sorted().toMutableList()
            repeat(6) { link ->
                // A quarter of the links go back to an earlier tree, so old weights come round again.
                val next = if (history.size > 1 && r.nextInt(4) == 0) {
                    reverts++
                    history[r.nextInt(history.size - 1)]
                } else {
                    mutate(history.last(), r)
                }
                history += next
                // Usually behind the ledger: the floor, not the caller, has to keep ids fresh.
                val steps = Topology.diff(next, ledger, generation = r.nextLong(0, 3))
                for (prepare in steps.filterIsInstance<TopologyStep.Prepare>()) {
                    val id = prepare.record.id
                    if (ledger.lifecycle(id) != null || id in seen) failures += "seed $seed link $link reuses ${id.value}"
                    seen += id
                    prepares++
                }
                val applied = applyAll(steps, ledger)
                if (applied.refused.isNotEmpty()) failures += "seed $seed link $link refused ${applied.refused}"
                ledger = applied.ledger
            }
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            { assertTrue(prepares >= 1_200, "prepares $prepares") },
            { assertTrue(reverts >= 100, "reverts $reverts") },
        )
    }

    @Test
    fun withTrafficTheDiffStillEndsClean() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var midDrain = 0
        var refusals = 0
        var moved = 0
        var cuts = 0
        var cutsWithRefusal = 0
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            var target = randomTree(r)
            var ledger = bootFrom(target)
            repeat(3) {
                ledger = traffic(ledger, target, r)
                target = mutate(target, r)
                // A change that arrives while an earlier one is still draining.
                if (ledger.hasOutstandingClosingEdge()) midDrain++
                val generation = r.nextLong(0, 3)
                val steps = Topology.diff(target, ledger, generation)
                tally.count(steps, ledger, target)
                // Re-entrance with refusals: apply a prefix the way the interpreter does, skipping a
                // refused child's later steps, and the diff is exactly the steps it did not apply.
                for (cut in 0..steps.size) {
                    val partial = applyAll(steps.take(cut), ledger)
                    val rest = steps.filterIndexed { i, _ -> i !in partial.applied }
                    val again = Topology.diff(target, partial.ledger, generation)
                    if (again != rest) failures += "seed $seed cut $cut: $again != $rest"
                    if (partial.applied.size < cut) cutsWithRefusal++
                    cuts++
                }
                val applied = applyAll(steps, ledger)
                refusals += applied.refused.size
                moved += applied.reconciled.size
                ledger = applied.ledger
                if (r.nextBoolean()) ledger = drainAll(ledger)
            }
            // Drained, one diff finishes the job: nothing is refused and nothing is left over.
            ledger = drainAll(ledger)
            val last = applyAll(Topology.diff(target, ledger, generation = 0), ledger)
            moved += last.reconciled.size
            failures += checkConverged(seed, target, last.ledger, last.refused)
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            { assertTrue(tally.reweights >= 150, "reweights ${tally.reweights}") },
            { assertTrue(tally.removals >= 500, "removals ${tally.removals}") },
            { assertTrue(tally.additions >= 400, "additions ${tally.additions}") },
            { assertTrue(midDrain >= 60, "mid-drain second changes $midDrain") },
            { assertTrue(refusals >= 300, "refused children $refusals") },
            { assertTrue(moved >= 60, "reconciles that moved $moved") },
            { assertTrue(tally.laneToGroup >= 80, "lanes turned into groups ${tally.laneToGroup}") },
            { assertTrue(tally.groupToLane >= 40, "groups turned into lanes ${tally.groupToLane}") },
            { assertTrue(cuts >= 3_000, "cuts $cuts") },
            { assertTrue(cutsWithRefusal >= 700, "cuts that hit a refusal $cutsWithRefusal") },
        )
    }

    // ── checks ──────────────────────────────────────────────────────────────────────────────────

    /** Why [ledger] is not [target] booted from scratch, or nothing. */
    private fun checkConverged(seed: Int, target: Topology, ledger: EntitlementLedger, refused: List<GroupId>): List<String> = buildList {
        if (refused.isNotEmpty()) add("seed $seed refused $refused")
        val expected = bootFrom(target).activeTriples()
        val actual = ledger.activeTriples()
        if (actual != expected) add("seed $seed active $actual != $expected")
        val conflicts = ledger.validate()
        if (conflicts.isNotEmpty()) add("seed $seed conflicts $conflicts")
        val again = Topology.diff(target, ledger, generation = 0)
        if (again.isNotEmpty()) add("seed $seed re-diff $again")
        // A prepared edge nothing wants may stay; a closing one is a retire that never landed.
        val closing = ledger.allEdges().sorted().filter { ledger.lifecycle(it) == Lifecycle.CLOSING }
        if (closing.isNotEmpty()) add("seed $seed left closing ${closing.map { it.value }}")
    }

    private fun EntitlementLedger.hasOutstandingClosingEdge(): Boolean =
        allEdges().any { lifecycle(it) == Lifecycle.CLOSING && (edge(it)?.outstanding ?: 0L) > 0L }

    /** What the generated histories actually contained, counted from the diffs rather than the edits. */
    private class Tally {
        var reweights = 0
        var removals = 0
        var additions = 0
        var laneToGroup = 0
        var groupToLane = 0

        fun count(steps: List<TopologyStep>, ledger: EntitlementLedger, target: Topology) {
            val lowered = target.lower()
            val wanted = lowered.map { it.child }
            // A group with an active child edge now, against what the target declares.
            val activeParents = ledger.allEdges().filter { ledger.lifecycle(it) == Lifecycle.ACTIVE }
                .mapNotNull { ledger.record(it)?.parent }.distinct()
            val targetGroups = lowered.map { it.parent }.distinct()
            for (child in wanted.filter { c -> ledger.allEdges().any { ledger.record(it)?.child == c } }) {
                if (child in targetGroups && child !in activeParents) laneToGroup++
                if (child !in targetGroups && child in activeParents) groupToLane++
            }
            val children = steps.map { it.child }.distinct().sorted()
            for (child in children) {
                val mine = steps.filter { it.child == child }
                val prepares = mine.any { it is TopologyStep.Prepare }
                val known = ledger.allEdges().any { ledger.record(it)?.child == child }
                when {
                    child !in wanted && mine.any { it is TopologyStep.Retire } -> removals++
                    prepares && !known -> additions++
                    prepares && mine.any { it is TopologyStep.Retire } -> reweights++
                }
            }
        }
    }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun randomTree(r: Random): Topology = Topology("root", randomChildren(r, depth = 1))

    private fun randomChildren(r: Random, depth: Int): List<Node> =
        names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, it, depth) }

    private fun randomNode(r: Random, name: String, depth: Int): Node {
        val weight = Weight.of(1L + r.nextInt(4))
        return if (depth < 3 && r.nextInt(3) == 0) Group(name, weight, randomChildren(r, depth + 1)) else Lane(name, weight)
    }

    /** One to three edits, each a reweight, removal, addition, lane-to-group, group-to-lane or a fresh tree. */
    private fun mutate(t: Topology, r: Random): Topology {
        var current = t
        repeat(1 + r.nextInt(3)) { current = edit(current, r) }
        return current
    }

    private fun edit(t: Topology, r: Random): Topology {
        val paths = nodePaths(t.children, emptyList())
        val path = paths[r.nextInt(paths.size)]
        val node = nodeAt(t.children, path)
        return when (r.nextInt(6)) {
            // (w mod 4) + 1 differs from w for every w in 1..4.
            0 -> t.update(path) { it.withWeight(Weight.of(it.weight.numerator % 4 + 1)) }
            1 -> if (siblingsOf(t, path).size > 1) t.update(path) { null } else t
            2 -> {
                val parent = if (node is Group) path else path.dropLast(1)
                val taken = (if (parent.isEmpty()) t.children else (nodeAt(t.children, parent) as Group).children).map { it.name }
                val free = names.filter { it !in taken }
                if (free.isEmpty()) {
                    t
                } else {
                    val added = randomNode(r, free[r.nextInt(free.size)], parent.size + 1)
                    if (parent.isEmpty()) Topology(t.root, t.children + added) else t.update(parent) { (it as Group).copy(children = it.children + added) }
                }
            }
            3 -> if (node is Lane && path.size < 3) t.update(path) { Group(it.name, it.weight, listOf(Lane(names[r.nextInt(names.size)]))) } else t
            4 -> if (node is Group) t.update(path) { Lane(it.name, it.weight) } else t
            else -> randomTree(r)
        }
    }

    private fun Node.withWeight(weight: Weight): Node = when (this) {
        is Group -> copy(weight = weight)
        is Lane -> copy(weight = weight)
    }

    private fun nodePaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        nodes.flatMap { node ->
            val path = prefix + node.name
            listOf(path) + if (node is Group) nodePaths(node.children, path) else emptyList()
        }

    private fun nodeAt(nodes: List<Node>, path: List<String>): Node {
        val node = nodes.single { it.name == path.first() }
        return if (path.size == 1) node else nodeAt((node as Group).children, path.drop(1))
    }

    private fun siblingsOf(t: Topology, path: List<String>): List<Node> =
        if (path.size == 1) t.children else (nodeAt(t.children, path.dropLast(1)) as Group).children

    /** [t] with the node at [path] replaced by [f]'s result, or removed when it returns `null`. */
    private fun Topology.update(path: List<String>, f: (Node) -> Node?): Topology = Topology(root, children.update(path, f))

    private fun List<Node>.update(path: List<String>, f: (Node) -> Node?): List<Node> = mapNotNull { node ->
        when {
            node.name != path.first() -> node
            path.size == 1 -> f(node)
            else -> (node as Group).copy(children = node.children.update(path.drop(1), f))
        }
    }

    /**
     * One to four pieces of work at random lanes of [t]: delegated from the root down, and partly
     * spent. What is not spent stays outstanding, so a later retire along that path is refused.
     */
    private fun traffic(ledger: EntitlementLedger, t: Topology, r: Random): EntitlementLedger {
        var current = ledger
        val lanes = nodePaths(t.children, emptyList())
            .filter { nodeAt(t.children, it) is Lane }
            .map { t.groupId(*it.toTypedArray()) }
        repeat(1 + r.nextInt(4)) {
            val lane = lanes[r.nextInt(lanes.size)]
            val amount = 1L + r.nextInt(5)
            val spent = r.nextLong(0, amount + 1)
            // The ledger charges only leaves. A lane whose old children are closing or retired is one,
            // so this skips only a lane that still has an ACTIVE child mid-reload (#3191).
            if (!current.isLeaf(lane)) return@repeat
            val delegated = delegateDown(current, lane, amount) ?: return@repeat
            current = delegated
            if (spent > 0L) current.spend(BOOT_HOLDER, lane, spent)?.let { current = current.piece(it) }
        }
        return current
    }
}
