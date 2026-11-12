package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Properties of [Topology.diff] over seeded random trees: depth up to 3, up to 3 children per
 * group, names from a short list so that edits collide with earlier names, weights 1 to 4.
 *
 * The names are the awkward ones as well as the plain ones: `b/c` and `é` escape to `%XX`, a
 * quarter of the seeds use an escaped root, and `a`, `a1` and `a-b` as siblings sort one way by
 * child id and the other way by edge id (`-` < `/` < `1` < `@`), so the step order's tie-break is
 * exercised rather than assumed. A third of the groups are marked for runtime children, and every
 * history attaches and detaches some, under opaque names that collide with declared ones.
 *
 * Every draw is from a `List` this file built, never from a `Set` or a `Map` view, so a seed walks
 * the same trajectory on every target. Each property counts the cases it is meant to exercise and
 * asserts the counts, so a generator that drifted towards trivial histories reds instead of passing.
 */
class TopologyDiffPropertyTest {

    private val seeds = 200
    private val names = listOf("a", "a1", "a-b", "b/c", "é", "default")
    private val runtimeNames = listOf("a", "x", "c-7f3a", "a/b", "!x", "#3")

    /** A runtime child the history attached and has not detached: its group path and opaque name. */
    private data class Attached(val group: List<String>, val name: String)

    // ── properties ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun applyingTheDiffMatchesBootingTheTarget() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var cuts = 0
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val root = randomRoot(r)
            tally.root(root)
            val from = randomTree(r, root)
            val to = if (r.nextInt(5) == 0) randomTree(r, root) else mutate(from, r)
            val churned = churn(from, bootFrom(from, generation = r.nextLong(0, 3)), emptyList(), r, tally, failures, "seed $seed")
            val ledger = churned.ledger
            val generation = r.nextLong(0, 5)
            val steps = Topology.diff(to, ledger, generation)
            tally.count(steps, ledger, to)
            val applied = applyAll(steps, ledger)
            failures += checkConverged(seed, to, applied.ledger, applied.refused, churned.attached)
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
            *tally.awkwardFloors(escapedRoots = 30, escaped = 500, tieBreaks = 80),
            { assertTrue(tally.attaches >= 100, "runtime attaches ${tally.attaches}") },
            { assertTrue(tally.runtimeClosed >= 20, "runtime children closed by a reload ${tally.runtimeClosed}") },
        )
    }

    @Test
    fun generationsNeverCollideAcrossAChain() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var prepares = 0
        var reverts = 0
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val root = randomRoot(r)
            tally.root(root)
            val history = arrayListOf(randomTree(r, root))
            var ledger = bootFrom(history.last(), generation = r.nextLong(0, 3))
            var attached = emptyList<Attached>()
            val seen = ledger.allEdges().sorted().toMutableList()
            fun checkFresh(steps: List<TopologyStep>, before: EntitlementLedger, where: String) {
                for (prepare in steps.filterIsInstance<TopologyStep.Prepare>()) {
                    val id = prepare.record.id
                    if (before.lifecycle(id) != null || id in seen) failures += "seed $seed $where reuses ${id.value}"
                    seen += id
                    prepares++
                }
            }
            repeat(6) { link ->
                // Attaches and detaches between reloads, behind the ledger: their ids must be fresh too.
                val churned = churn(history.last(), ledger, attached, r, tally, failures, "seed $seed link $link", ::checkFresh)
                ledger = churned.ledger
                attached = churned.attached
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
                tally.count(steps, ledger, next)
                checkFresh(steps, ledger, "link $link")
                val applied = applyAll(steps, ledger)
                if (applied.refused.isNotEmpty()) failures += "seed $seed link $link refused ${applied.refused}"
                ledger = applied.ledger
                attached = attached.filter { markerAt(next, it.group) != null }
            }
            failures += checkConverged(seed, history.last(), ledger, emptyList(), attached)
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            { assertTrue(prepares >= 1_200, "prepares $prepares") },
            { assertTrue(reverts >= 100, "reverts $reverts") },
            { assertTrue(tally.reattaches >= 50, "re-attaches after a detach ${tally.reattaches}") },
            { assertTrue(tally.runtimeRegenerated >= 12, "runtime children regenerated by a marker reweight ${tally.runtimeRegenerated}") },
            { assertTrue(tally.runtimeClosed >= 150, "runtime children closed by a reload ${tally.runtimeClosed}") },
            *tally.awkwardFloors(escapedRoots = 30, escaped = 3_000, tieBreaks = 300),
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
            val root = randomRoot(r)
            tally.root(root)
            var target = randomTree(r, root)
            var ledger = bootFrom(target)
            var attached = emptyList<Attached>()
            repeat(3) {
                val churned = churn(target, ledger, attached, r, tally, failures = null, where = "seed $seed")
                attached = churned.attached
                ledger = traffic(churned.ledger, target, attached, r, tally)
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
                attached = attached.filter { markerAt(target, it.group) != null }
                if (r.nextBoolean()) ledger = drainAll(ledger)
            }
            // Drained, one diff finishes the job: nothing is refused and nothing is left over.
            ledger = drainAll(ledger)
            val last = applyAll(Topology.diff(target, ledger, generation = 0), ledger)
            moved += last.reconciled.size
            failures += checkConverged(seed, target, last.ledger, last.refused, attached)
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
            *tally.awkwardFloors(escapedRoots = 30, escaped = 2_000, tieBreaks = 150),
            { assertTrue(tally.runtimeServed >= 100, "pieces of work served at a runtime child ${tally.runtimeServed}") },
            { assertTrue(tally.runtimeRegenerated >= 8, "runtime children regenerated by a marker reweight ${tally.runtimeRegenerated}") },
            { assertTrue(tally.runtimeCarried >= 100, "served runtime children a reload then had to carry ${tally.runtimeCarried}") },
        )
    }

    // ── checks ──────────────────────────────────────────────────────────────────────────────────

    /** Why [ledger] is not [target] booted from scratch with [attached] attached, or nothing. */
    private fun checkConverged(
        seed: Int,
        target: Topology,
        ledger: EntitlementLedger,
        refused: List<GroupId>,
        attached: List<Attached>,
    ): List<String> = buildList {
        if (refused.isNotEmpty()) add("seed $seed refused $refused")
        val runtime = attached.mapNotNull { a ->
            val marker = markerAt(target, a.group) ?: return@mapNotNull null
            ActiveTriple(target.groupId(*a.group.toTypedArray()), target.runtimeChildId(a.group, a.name), marker.weight)
        }
        val expected = (bootFrom(target).activeTriples() + runtime).sortedWith(compareBy<ActiveTriple> { it.child }.thenBy { it.parent })
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
        var escapedRoots = 0
        var escaped = 0
        var tieBreaks = 0
        var attaches = 0
        var reattaches = 0
        var runtimeRegenerated = 0
        var runtimeClosed = 0
        var runtimeServed = 0
        var runtimeCarried = 0

        /** Every runtime child some history detached; cleared at each seed's [root]. */
        val detached = ArrayList<Attached>()

        fun root(root: String) {
            detached.clear()
            if (escapeSegment(root) != root) escapedRoots++
        }

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
                if (pathOf(child)?.runtime == true) {
                    when {
                        prepares -> runtimeRegenerated++
                        mine.any { it is TopologyStep.Close } -> runtimeClosed++
                    }
                    continue
                }
                when {
                    child !in wanted && mine.any { it is TopologyStep.Retire } -> removals++
                    prepares && !known -> additions++
                    prepares && mine.any { it is TopologyStep.Retire } -> reweights++
                }
            }
            escaped += steps.count { '%' in it.child.value }
            // Neighbours in one phase at one depth that sort one way by child and the other by edge:
            // only the child-then-edge tie-break puts them in the order the list has. Activates are
            // left out, since two of them side by side may sit in different phases.
            for ((a, b) in steps.zipWithNext()) {
                val onePhase = a::class == b::class && a !is TopologyStep.Activate
                val sameRun = onePhase && depth(a) == depth(b) && a.child != b.child
                if (sameRun && a.child < b.child && edgeOf(a) > edgeOf(b)) tieBreaks++
            }
        }

        fun awkwardFloors(escapedRoots: Int, escaped: Int, tieBreaks: Int): Array<() -> Unit> = arrayOf(
            { assertTrue(this.escapedRoots >= escapedRoots, "seeds under an escaped root ${this.escapedRoots}") },
            { assertTrue(this.escaped >= escaped, "steps naming an escaped segment ${this.escaped}") },
            { assertTrue(this.tieBreaks >= tieBreaks, "neighbours the child-then-edge tie-break ordered ${this.tieBreaks}") },
        )

        private fun depth(step: TopologyStep): Int = step.child.value.count { it == '/' }

        private fun edgeOf(step: TopologyStep): String = when (step) {
            is TopologyStep.Prepare -> step.record.id.value
            is TopologyStep.Activate -> step.edge.value
            is TopologyStep.Close -> step.edge.value
            is TopologyStep.Retire -> step.edge.value
            is TopologyStep.Reconcile -> ""
        }
    }

    // ── runtime children ────────────────────────────────────────────────────────────────────────

    private class Churned(val ledger: EntitlementLedger, val attached: List<Attached>)

    /**
     * One to four attaches and detaches under [t]'s marked groups, applied as the interpreter
     * would. With no traffic in the history ([failures] given) nothing may be refused; with traffic
     * a detach waits for its drain, and an attach behind it waits too. [Tally.detached] remembers
     * every child a history detached, so a re-attach is counted however far apart the two are.
     */
    private fun churn(
        t: Topology,
        ledger: EntitlementLedger,
        attached: List<Attached>,
        r: Random,
        tally: Tally,
        failures: MutableList<String>?,
        where: String,
        checkFresh: (List<TopologyStep>, EntitlementLedger, String) -> Unit = { _, _, _ -> },
    ): Churned {
        val marked = groupPaths(t.children, emptyList()).filter { markerAt(t, it) != null }
        if (marked.isEmpty()) return Churned(ledger, attached)
        var current = ledger
        val now = attached.toMutableList()
        repeat(1 + r.nextInt(4)) {
            val detach = now.isNotEmpty() && r.nextInt(3) == 0
            val steps: List<TopologyStep>
            if (detach) {
                val gone = now.removeAt(r.nextInt(now.size))
                tally.detached += gone
                steps = t.detachRuntimeChild(gone.group, gone.name, current)
            } else {
                val child = Attached(marked[r.nextInt(marked.size)], runtimeNames[r.nextInt(runtimeNames.size)])
                if (child in tally.detached && child !in now) tally.reattaches++
                if (child !in now) now += child
                steps = t.attachRuntimeChild(child.group, child.name, current, generation = r.nextLong(0, 3))
                tally.attaches++
            }
            checkFresh(steps, current, "$where churn")
            val applied = applyAll(steps, current)
            if (failures != null && applied.refused.isNotEmpty()) failures += "$where churn refused ${applied.refused}"
            current = applied.ledger
        }
        return Churned(current, now)
    }

    private fun markerAt(t: Topology, path: List<String>): RuntimeChildren? {
        var nodes = t.children
        var node: Node? = null
        for (name in path) {
            node = nodes.firstOrNull { it.name == name } ?: return null
            nodes = (node as? Group)?.children.orEmpty()
        }
        return (node as? Group)?.runtimeChildren
    }

    private fun groupPaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        nodes.filterIsInstance<Group>().flatMap { g -> listOf(prefix + g.name) + groupPaths(g.children, prefix + g.name) }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun randomRoot(r: Random): String = if (r.nextInt(4) == 0) "r/t é" else "root"

    private fun randomTree(r: Random, root: String): Topology = Topology(root, randomChildren(r, depth = 1))

    private fun randomChildren(r: Random, depth: Int): List<Node> =
        names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, it, depth) }

    private fun randomNode(r: Random, name: String, depth: Int): Node {
        val weight = Weight.of(1L + r.nextInt(4))
        return if (depth < 3 && r.nextInt(3) == 0) {
            Group(name, weight, randomChildren(r, depth + 1), runtimeChildren = randomMarker(r))
        } else {
            Lane(name, weight)
        }
    }

    private fun randomMarker(r: Random): RuntimeChildren? = if (r.nextBoolean()) RuntimeChildren(Weight.of(1L + r.nextInt(3))) else null

    /** One to three edits: a reweight, removal, addition, lane-to-group, group-to-lane, marker change or a fresh tree. */
    private fun mutate(t: Topology, r: Random): Topology {
        var current = t
        repeat(1 + r.nextInt(3)) { current = edit(current, r) }
        return current
    }

    private fun edit(t: Topology, r: Random): Topology {
        val paths = nodePaths(t.children, emptyList())
        val path = paths[r.nextInt(paths.size)]
        val node = nodeAt(t.children, path)
        return when (r.nextInt(8)) {
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
            // Mark or unmark the group, or reweight its runtime children.
            5 -> if (node is Group) t.update(path) { (it as Group).copy(runtimeChildren = if (it.runtimeChildren == null) randomMarker(r) else null) } else t
            6 -> if (node is Group && node.runtimeChildren != null) {
                t.update(path) { (it as Group).copy(runtimeChildren = RuntimeChildren(Weight.of(node.runtimeChildren.weight.numerator % 3 + 1))) }
            } else {
                t
            }
            else -> randomTree(r, t.root)
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
     * One to four pieces of work at random lanes of [t] and at the runtime children [attached]:
     * delegated from the root down, and partly spent. What is not spent stays outstanding, so a
     * later retire along that path is refused.
     */
    private fun traffic(ledger: EntitlementLedger, t: Topology, attached: List<Attached>, r: Random, tally: Tally): EntitlementLedger {
        var current = ledger
        val runtime = attached.map { t.runtimeChildId(it.group, it.name) }
        val lanes = nodePaths(t.children, emptyList())
            .filter { nodeAt(t.children, it) is Lane }
            .map { t.groupId(*it.toTypedArray()) } + runtime
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
            if (lane in runtime) tally.runtimeServed++
        }
        // Served runtime children a reload will carry over: still attached, with history on their edge.
        tally.runtimeCarried += runtime.count { child -> current.liveInto(child).any { (current.edge(it)?.issued ?: 0L) > 0L } }
        return current
    }
}
