package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Topology.changesTo] over seeded pairs of trees: a tree and one edit of it, of every kind an admin
 * makes, or two trees drawn independently from the same few names so they overlap. For every pair
 * `(a, b)`:
 *
 * - the list is empty exactly when `a` and `b` agree once siblings are sorted by name. A pair that
 *   differs in one field only is what catches a field the diff forgot, which no example test
 *   written by the same hand would;
 * - every change's address resolves: [TopologyChange.Removed] in `a`, every other change in `b`,
 *   to a node whose names from the root are the change's [TopologyChange.names];
 * - `b.changesTo(a)` is `a.changesTo(b)` with every change turned round — an addition for a
 *   removal, a lane-became-group for a group-became-lane, before and after swapped — compared as
 *   sorted lists with the indices left out, since each direction addresses its own tree.
 *
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every target.
 * Each kind of change and each kind of edit is counted and floored, so a generator that stopped
 * producing one reds instead of passing.
 */
class TopologyChangesPropertyTest {

    private val seeds = 400
    private val names = listOf("a", "b", "c", "d")
    private val weights = listOf(Weight.ONE, Weight.of(2), Weight.of(3, 2))
    private val ceilings = listOf(null, 0L, 2L)

    @Test
    fun changesAreCompleteAddressedAndReversible() {
        val failures = ArrayList<String>()
        val kinds = HashMap<String, Int>()
        val edits = HashMap<String, Int>()
        var equalPairs = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val a = Topology("root", randomChildren(r, depth = 0))
            val (editName, b) = if (r.nextInt(4) == 0) "independent" to Topology(if (r.nextInt(8) == 0) "other" else "root", randomChildren(r, 0)) else edit(a, r)
            edits[editName] = (edits[editName] ?: 0) + 1
            val forward = a.changesTo(b)
            val backward = b.changesTo(a)
            forward.forEach { kinds[it::class.simpleName ?: "?"] = (kinds[it::class.simpleName ?: "?"] ?: 0) + 1 }

            val same = canonical(a) == canonical(b)
            if (same) equalPairs++
            if (forward.isEmpty() != same) failures += "seed $seed ($editName): ${forward.size} changes, trees ${if (same) "agree" else "differ"}\n  $a\n  $b"

            for (change in forward) {
                val tree = if (change is TopologyChange.Removed) a else b
                val walked = namesAt(tree, change.path)
                if (walked != change.names) failures += "seed $seed ($editName): $change resolves to $walked"
            }

            val expected = backward.map { projection(it) }.sorted()
            val reversed = forward.map { projection(reverse(it)) }.sorted()
            if (expected != reversed) failures += "seed $seed ($editName): reversed $reversed, backward $expected"
        }
        val kindFloors = listOf(
            "RootRenamed", "Added", "Removed", "LaneBecameGroup", "GroupBecameLane", "WeightChanged",
            "CeilingChanged", "TemplateAdded", "TemplateRemoved", "TemplateChanged",
        )
        val editFloors = listOf("independent", "same", "reorder", "weight", "ceiling", "template", "add", "remove", "grow", "shrink", "rename", "move")
        assertAll(
            { assertEquals(emptyList(), failures.take(10), "${failures.size} failures") },
            { assertTrue(equalPairs >= 40, "equal pairs: $equalPairs") },
            { kindFloors.forEach { assertTrue((kinds[it] ?: 0) >= 5, "$it changes: ${kinds[it]}") } },
            { editFloors.forEach { assertTrue((edits[it] ?: 0) >= 10, "$it edits: ${edits[it]}") } },
        )
    }

    // ── reading a change ────────────────────────────────────────────────────────────────────────

    /** The names from the root along [path] in [t], or `null` if [path] leaves the tree. */
    private fun namesAt(t: Topology, path: List<Int>): List<String>? {
        val walked = arrayListOf(t.root)
        var nodes = t.children
        for (index in path) {
            val node = nodes.getOrNull(index) ?: return null
            walked += node.name
            nodes = (node as? Group)?.children.orEmpty()
        }
        return walked
    }

    /** The change from `b` to `a` that undoes [change], addressed nowhere: its indices are left out. */
    private fun reverse(change: TopologyChange): TopologyChange = when (change) {
        is TopologyChange.RootRenamed -> TopologyChange.RootRenamed(change.after, change.before)
        is TopologyChange.Added -> TopologyChange.Removed(change.path, change.names, change.node)
        is TopologyChange.Removed -> TopologyChange.Added(change.path, change.names, change.node)
        is TopologyChange.LaneBecameGroup -> TopologyChange.GroupBecameLane(change.path, change.names)
        is TopologyChange.GroupBecameLane -> TopologyChange.LaneBecameGroup(change.path, change.names)
        is TopologyChange.WeightChanged -> change.copy(before = change.after, after = change.before)
        is TopologyChange.CeilingChanged -> change.copy(before = change.after, after = change.before)
        is TopologyChange.TemplateAdded -> TopologyChange.TemplateRemoved(change.path, change.names, change.template)
        is TopologyChange.TemplateRemoved -> TopologyChange.TemplateAdded(change.path, change.names, change.template)
        is TopologyChange.TemplateChanged -> change.copy(before = change.after, after = change.before)
    }

    /** [change] without its indices, which differ between the two directions. */
    private fun projection(change: TopologyChange): String = when (change) {
        is TopologyChange.RootRenamed -> change.toString()
        is TopologyChange.Added -> "Added ${change.names} ${change.node}"
        is TopologyChange.Removed -> "Removed ${change.names} ${change.node}"
        is TopologyChange.LaneBecameGroup -> "LaneBecameGroup ${change.names}"
        is TopologyChange.GroupBecameLane -> "GroupBecameLane ${change.names}"
        is TopologyChange.WeightChanged -> "Weight ${change.names} ${change.before} ${change.after}"
        is TopologyChange.CeilingChanged -> "Ceiling ${change.names} ${change.before} ${change.after}"
        is TopologyChange.TemplateAdded -> "TemplateAdded ${change.names} ${change.template}"
        is TopologyChange.TemplateRemoved -> "TemplateRemoved ${change.names} ${change.template}"
        is TopologyChange.TemplateChanged -> "Template ${change.names} ${change.before} ${change.after}"
    }

    /** [t] with every group's children sorted by name, children inside nodes included. */
    private fun canonical(t: Topology): Topology = Topology(t.root, canonical(t.children))

    private fun canonical(nodes: List<Node>): List<Node> =
        nodes.sortedBy { it.name }.map { if (it is Group) it.copy(children = canonical(it.children)) else it }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun <T> List<T>.draw(r: Random): T = this[r.nextInt(size)]

    private fun randomChildren(r: Random, depth: Int): List<Node> =
        names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, it, depth) }

    private fun randomNode(r: Random, name: String, depth: Int): Node =
        if (depth < 2 && r.nextInt(2) == 0) {
            Group(name, weights.draw(r), randomChildren(r, depth + 1), randomTemplate(r), ceilings.draw(r))
        } else {
            Lane(name, weights.draw(r), ceilings.draw(r))
        }

    private fun randomTemplate(r: Random): ChildTemplate? =
        if (r.nextInt(3) == 0) ChildTemplate(weights.draw(r), ceilings.draw(r)) else null

    /** One edit of [t], named for the tally; a drawn edit that would make the tree invalid becomes `same`. */
    private fun edit(t: Topology, r: Random): Pair<String, Topology> {
        val paths = nodePaths(t.children, emptyList())
        val path = paths.draw(r)
        val node = nodeAt(t.children, path)
        val (name, children) = when (r.nextInt(12)) {
            0 -> "same" to t.children
            1 -> "reorder" to reorder(t.children, path.dropLast(1))
            2 -> "weight" to t.children.update(path) { it.with(weight = (weights - it.weight).draw(r)) }
            3 -> "ceiling" to t.children.update(path) { it.with(maxConcurrent = (ceilings - it.maxConcurrent).draw(r)) }
            4 -> "template" to t.children.update(path) {
                if (it is Group) it.copy(template = (listOf(null) + weights.map { w -> ChildTemplate(w) } - it.template).draw(r)) else it
            }
            5 -> {
                val parent = if (node is Group) path else path.dropLast(1)
                val free = names - childrenAt(t.children, parent).map { it.name }.toSet()
                "add" to if (free.isEmpty()) t.children else t.children.insert(parent, randomNode(r, free.draw(r), parent.size))
            }
            6 -> "remove" to t.children.update(path) { null }
            7 -> "grow" to t.children.update(path) { if (it is Lane) Group(it.name, it.weight, listOf(Lane(names.draw(r))), null, it.maxConcurrent) else it }
            8 -> "shrink" to t.children.update(path) { if (it is Group) Lane(it.name, it.weight, it.maxConcurrent) else it }
            9 -> {
                val free = names - childrenAt(t.children, path.dropLast(1)).map { it.name }.toSet()
                "rename" to if (free.isEmpty()) t.children else t.children.update(path) { it.with(name = free.draw(r)) }
            }
            10 -> {
                // Move to another group, or to the root, where the name is free.
                val targets = (listOf(emptyList<String>()) + paths.filter { nodeAt(t.children, it) is Group })
                    .filter { target -> target != path.dropLast(1) && !target.startsWith(path) }
                    .filter { target -> childrenAt(t.children, target).none { it.name == node.name } }
                "move" to if (targets.isEmpty()) t.children else t.children.update(path) { null }.insert(targets.draw(r), node)
            }
            else -> "same" to (Topology.from(t.toDocument()) as TopologyParse.Valid).topology.children
        }
        return if (Topology.problems(t.root, children).isEmpty()) name to Topology(t.root, children) else "same" to t
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

    private fun Node.with(name: String = this.name, weight: Weight = this.weight, maxConcurrent: Long? = this.maxConcurrent): Node =
        when (this) {
            is Group -> copy(name = name, weight = weight, maxConcurrent = maxConcurrent)
            is Lane -> copy(name = name, weight = weight, maxConcurrent = maxConcurrent)
        }

    private fun nodePaths(nodes: List<Node>, prefix: List<String>): List<List<String>> = nodes.flatMap { node ->
        val path = prefix + node.name
        listOf(path) + if (node is Group) nodePaths(node.children, path) else emptyList()
    }

    private fun nodeAt(nodes: List<Node>, path: List<String>): Node {
        val node = nodes.single { it.name == path.first() }
        return if (path.size == 1) node else nodeAt((node as Group).children, path.drop(1))
    }

    private fun childrenAt(nodes: List<Node>, path: List<String>): List<Node> =
        if (path.isEmpty()) nodes else (nodeAt(nodes, path) as Group).children

    /** The node at [path] replaced by [edit]'s result, or removed when it gives `null`. */
    private fun List<Node>.update(path: List<String>, edit: (Node) -> Node?): List<Node> = mapNotNull { node ->
        when {
            node.name != path.first() -> node
            path.size == 1 -> edit(node)
            else -> (node as Group).copy(children = node.children.update(path.drop(1), edit))
        }
    }

    /** [node] appended to the children of the group at [parent]; `[]` is the root. */
    private fun List<Node>.insert(parent: List<String>, node: Node): List<Node> =
        if (parent.isEmpty()) this + node else update(parent) { (it as Group).copy(children = it.children + node) }

    /** The children of the group at [parent] reversed; `[]` is the root. */
    private fun reorder(nodes: List<Node>, parent: List<String>): List<Node> =
        if (parent.isEmpty()) nodes.reversed() else nodes.update(parent) { (it as Group).copy(children = it.children.reversed()) }
}
