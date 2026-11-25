package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TopologyChange.history] against the engine: over seeded pairs of trees, the changes that say
 * they lose history are exactly the ones whose groups get a new inbound edge, or none, when
 * [LocalHeddle.apply] lands the edited tree.
 *
 * The oracle is the ledger, not the classification. A group's history is its live inbound edge: a
 * new weight is a new edge seated at its parent's front, a removal retires the edge, and anything
 * else leaves the edge as it was. So for every seed this boots a real engine on the tree before,
 * attaches children under groups with a template, reads each group's live inbound edges, applies
 * the tree after, and reads them again. Then, for every group that had an edge before:
 *
 * - its edges changed exactly when some change that says [TopologyChange.History.LOST] speaks for
 *   it. One direction is that a lost change really costs its groups their edge; the other is that
 *   no change that says [TopologyChange.History.KEPT] costs anybody theirs, since a group whose edge
 *   changed with no lost change behind it fails. A child can carry a lost change and a kept one at
 *   once, a new weight beside a new ceiling, and its edge changes for the first;
 * - and every group an [TopologyChange.Added] change speaks for had no edge before and has one
 *   after.
 *
 * A [TopologyChange.RootRenamed] says [TopologyChange.History.NEW_TREE], and for it apply must
 * throw [IllegalArgumentException] rather than land anything.
 *
 * Which groups a change speaks for is read off the change: a removed node and everything beneath
 * it, attached children included; for a removed or changed template, the children attached
 * under that group, never the group; and for anything else, the node alone, since a new template
 * has no children attached under it yet. Each change is counted only where its
 * check could fail — a lost change with at least one group that had an edge, a kept change whose
 * own group had one and lost nothing to another change — and every arm and answer is floored, so
 * a generator that stopped reaching one reds instead of passing.
 *
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every target.
 */
class TopologyChangeHistoryPropertyTest {

    private val seeds = 1500
    private val names = listOf("a", "b", "c", "d")
    private val weights = listOf(Weight.ONE, Weight.of(2), Weight.of(3, 2))
    private val ceilings = listOf(null, 0L, 2L)
    private val attachNames = listOf("x", "y")

    @Test
    fun aChangeLosesHistoryExactlyWhenApplyGivesItsGroupsANewEdge() {
        val failures = ArrayList<String>()
        val reached = HashMap<String, Int>()
        var applied = 0
        var refused = 0
        var newTreesThrew = 0
        var newTreesApplied = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val before = Topology("root", randomChildren(r, depth = 0))
            val edited = (0..r.nextInt(3)).fold(before) { tree, _ -> edit(tree, r) }
            val after = if (r.nextInt(12) == 0) Topology("other", edited.children) else edited
            val changes = before.changesTo(after)
            val heddle = LocalHeddle.bootWhole(before, POLICY, epoch = 0L)
            val attached = attachSome(heddle, before, r)

            if (changes.any { it.history == TopologyChange.History.NEW_TREE }) {
                val threw = runCatching { heddle.apply(after, generation = 1L) }.exceptionOrNull()
                if (threw is IllegalArgumentException) newTreesThrew++ else newTreesApplied++
                continue
            }

            val existing = declaredIds(before) + attached.map { (group, name) -> before.attachedChildId(group, name) }
            val edgesBefore = edgesOf(heddle, existing + declaredIds(after))
            val result = heddle.apply(after, generation = 1L)
            if (result.refusals.isNotEmpty()) {
                refused++
                continue
            }
            applied++
            val edgesAfter = edgesOf(heddle, edgesBefore.keys)

            val lost = changes.filter { it.history == TopologyChange.History.LOST }
                .flatMapTo(HashSet()) { speaksFor(it, before, after, attached) }
            for (group in existing) {
                if (edgesBefore.getValue(group).isEmpty()) {
                    failures += "seed $seed: $group had no live edge before the apply"
                    continue
                }
                val changed = edgesAfter.getValue(group) != edgesBefore.getValue(group)
                if (changed != (group in lost)) {
                    val about = changes.filter { group in speaksFor(it, before, after, attached) }
                    failures += "seed $seed: ${group.value} ${if (changed) "got a new edge" else "kept its edge"}, " +
                        "${edgesBefore[group]} -> ${edgesAfter[group]}, from ${about.map { "$it: ${it.history}" }}"
                }
            }
            for (change in changes) {
                val groups = speaksFor(change, before, after, attached)
                if (change is TopologyChange.Added) {
                    reached.count("Added:${change.history}")
                    groups.filter { edgesBefore.getValue(it).isNotEmpty() || edgesAfter.getValue(it).isEmpty() }.forEach {
                        failures += "seed $seed: added ${it.value}, ${edgesBefore[it]} -> ${edgesAfter[it]}"
                    }
                    continue
                }
                val withEdges = groups.filter { it in existing }
                val decisive = when (change.history) {
                    TopologyChange.History.LOST -> withEdges.isNotEmpty()
                    else -> withEdges.isNotEmpty() && withEdges.none { it in lost }
                }
                if (decisive) reached.count("${change::class.simpleName}:${change.history}")
            }
        }
        // Each floor is about half what the generator reaches on the JVM (727, 569, 428, 206, 143,
        // 50, 107, 63, 36, 41; 1392 applied, 108 new trees), so a generator that stopped reaching an
        // arm reds, and one that drifted a little does not.
        val floors = listOf(
            "Added:KEPT" to 360,
            "Removed:LOST" to 280,
            "WeightChanged:LOST" to 210,
            "CeilingChanged:KEPT" to 100,
            "LaneBecameGroup:KEPT" to 70,
            "GroupBecameLane:KEPT" to 25,
            "TemplateAdded:KEPT" to 50,
            "TemplateRemoved:LOST" to 30,
            "TemplateChanged:LOST" to 18,
            "TemplateChanged:KEPT" to 20,
        )
        val counts = floors.map { (arm, floor) -> Triple(arm, reached[arm] ?: 0, floor) } +
            listOf(Triple("applied", applied, 700), Triple("new trees threw", newTreesThrew, 50))
        println("CHANGE-HISTORY ${counts.joinToString { "${it.first}=${it.second}" }}, refused=$refused")
        val short = counts.filter { (_, counted, floor) -> counted < floor }.map { (arm, counted, floor) -> "$arm: $counted < $floor" }
        assertAll(
            { assertEquals(emptyList(), failures.take(5), "${failures.size} failures") },
            { assertEquals(emptyList(), short, "every count: ${counts.joinToString { "${it.first}=${it.second}" }}, refused=$refused") },
            { assertEquals(0, newTreesApplied, "new trees apply did not refuse") },
        )
    }

    // ── the oracle ──────────────────────────────────────────────────────────────────────────────

    /** Every group's live inbound edges, as the ledger has them now. */
    private fun edgesOf(heddle: LocalHeddle, groups: Collection<GroupId>): Map<GroupId, List<AttachmentId>> {
        val ledger = heddle.snapshot().ledger
        return groups.associateWith { ledger.liveInboundEdges(it) }
    }

    /**
     * The groups whose history [change] is about: for an addition or a removal, the node and
     * everything beneath it, attached children included; for a removed or changed template, the
     * children attached under that group; for anything else, the node alone.
     */
    private fun speaksFor(change: TopologyChange, before: Topology, after: Topology, attached: List<Pair<List<String>, String>>): Set<GroupId> {
        val at = change.names.drop(1)
        val attachedBelow = { exact: Boolean ->
            attached.filter { (group, _) -> if (exact) group == at else group.startsWith(at) }
                .mapTo(HashSet()) { (group, name) -> before.attachedChildId(group, name) }
        }
        return when (change) {
            is TopologyChange.RootRenamed -> emptySet()
            is TopologyChange.Removed -> pathsBelow(change.node, at).mapTo(HashSet()) { before.groupId(*it.toTypedArray()) } + attachedBelow(false)
            is TopologyChange.Added -> pathsBelow(change.node, at).mapTo(HashSet()) { after.groupId(*it.toTypedArray()) }
            is TopologyChange.TemplateRemoved, is TopologyChange.TemplateChanged -> attachedBelow(true)
            is TopologyChange.WeightChanged, is TopologyChange.CeilingChanged, is TopologyChange.TemplateAdded,
            is TopologyChange.LaneBecameGroup, is TopologyChange.GroupBecameLane,
            -> setOf(after.groupId(*at.toTypedArray()))
        }
    }

    /** One or two children attached under some of the groups that have a template, in path order. */
    private fun attachSome(heddle: LocalHeddle, tree: Topology, r: Random): List<Pair<List<String>, String>> =
        nodePaths(tree.children, emptyList())
            .filter { (nodeAt(tree.children, it) as? Group)?.template != null && r.nextInt(4) != 0 }
            .flatMap { group -> attachNames.take(1 + r.nextInt(attachNames.size)).map { group to it } }
            .onEach { (group, name) -> check(heddle.attach(group, name, generation = 0L).refusals.isEmpty()) { "attach $group $name" } }

    private fun declaredIds(tree: Topology): List<GroupId> = nodePaths(tree.children, emptyList()).map { tree.groupId(*it.toTypedArray()) }

    private fun pathsBelow(node: Node, at: List<String>): List<List<String>> =
        listOf(at) + if (node is Group) nodePaths(node.children, at) else emptyList()

    private fun HashMap<String, Int>.count(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

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
        if (r.nextBoolean()) ChildTemplate(weights.draw(r), ceilings.draw(r)) else null

    /** One edit of [t]; a drawn edit that would make the tree invalid leaves it as it was. */
    private fun edit(t: Topology, r: Random): Topology {
        val paths = nodePaths(t.children, emptyList())
        val path = paths.draw(r)
        val node = nodeAt(t.children, path)
        // Half the template edits go to a group that has one, which is where children are attached.
        val templated = paths.filter { (nodeAt(t.children, it) as? Group)?.template != null }
        val children = when (r.nextInt(11)) {
            0 -> t.children.update(path) { it.with(weight = (weights - it.weight).draw(r)) }
            1 -> t.children.update(path) { it.with(maxConcurrent = (ceilings - it.maxConcurrent).draw(r)) }
            2, 10 -> {
                val at = if (templated.isNotEmpty() && r.nextBoolean()) templated.draw(r) else path
                t.children.update(at) { if (it is Group) it.copy(template = retemplate(it.template, r)) else it }
            }
            3 -> {
                val parent = if (node is Group) path else path.dropLast(1)
                val free = names - childrenAt(t.children, parent).map { it.name }.toSet()
                if (free.isEmpty()) t.children else t.children.insert(parent, randomNode(r, free.draw(r), parent.size))
            }
            4 -> t.children.update(path) { null }
            5 -> t.children.update(path) { if (it is Lane) Group(it.name, it.weight, listOf(Lane(names.draw(r))), randomTemplate(r), it.maxConcurrent) else it }
            6 -> t.children.update(path) { if (it is Group) Lane(it.name, it.weight, it.maxConcurrent) else it }
            7 -> {
                val free = names - childrenAt(t.children, path.dropLast(1)).map { it.name }.toSet()
                if (free.isEmpty()) t.children else t.children.update(path) { it.with(name = free.draw(r)) }
            }
            8 -> {
                val targets = (listOf(emptyList<String>()) + paths.filter { nodeAt(t.children, it) is Group })
                    .filter { target -> target != path.dropLast(1) && !target.startsWith(path) }
                    .filter { target -> childrenAt(t.children, target).none { it.name == node.name } }
                if (targets.isEmpty()) t.children else t.children.update(path) { null }.insert(targets.draw(r), node)
            }
            else -> t.children.update(path) { it.with(weight = (weights - it.weight).draw(r), maxConcurrent = (ceilings - it.maxConcurrent).draw(r)) }
        }
        return if (Topology.problems(t.root, children).isEmpty()) Topology(t.root, children) else t
    }

    /** A new template for a group: none, a new weight, or the same weight with a new ceiling. */
    private fun retemplate(template: ChildTemplate?, r: Random): ChildTemplate? = when {
        template == null -> ChildTemplate(weights.draw(r), ceilings.draw(r))
        else -> listOf(
            null,
            template.copy(weight = (weights - template.weight).draw(r)),
            template.copy(maxConcurrent = (ceilings - template.maxConcurrent).draw(r)),
        ).draw(r)
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

    private companion object {
        val POLICY = LocalPolicy(defaultReservation = 2L)
    }
}
