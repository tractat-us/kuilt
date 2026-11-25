package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The edits on [TopologyDocument] over seeded trees, against a model that does the same edit its
 * own way.
 *
 * The model is a mutable tree of [M] nodes that this file builds, edits by moving node objects
 * between `MutableList`s, and turns into a [Topology] by calling the constructors directly. It
 * never calls an edit, never computes an index shift (a moved or added node's path is found by
 * looking for the object afterwards), and never goes through [TopologyDocument]. For every draw:
 *
 * - the edit's result is exactly the model's: the same refusal naming the same path, or an
 *   [TopologyEdit.Edited] whose document, path and history are the model's;
 * - `Topology.from` of the edited document is [TopologyParse.Valid] with the topology built from
 *   the edited model whenever that topology is valid, and [TopologyParse.Invalid] otherwise.
 *
 * Some trees start with two siblings of one name, a document that is invalid while it is being
 * edited, so an edit addressed by index still has to land on the right one of the pair.
 *
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every
 * target. Every outcome, every refusal arm and every [TopologyEdit.History] is counted and floored,
 * so a generator that stopped reaching one reds instead of passing.
 */
class TopologyDocumentEditsPropertyTest {

    private val seeds = 3000
    private val names = listOf("a", "b", "c", "d")
    private val weightOf = mapOf("1" to Weight.ONE, "2" to Weight.of(2), "3/2" to Weight.of(3, 2), "6/4" to Weight.of(3, 2))
    private val weights = weightOf.keys.toList()
    private val ceilings = listOf(null, 0L, 2L)
    private val templates = listOf(null, null, TopologyDocument.ChildTemplate(), TopologyDocument.ChildTemplate("2", 1L))

    @Test
    fun anEditIsTheModelsEditAndReadsBackAsTheModelsTree() {
        val failures = ArrayList<String>()
        val outcomes = HashMap<String, Int>()
        val histories = HashMap<TopologyEdit.History, Int>()
        var validTrees = 0
        var invalidTrees = 0
        var startedWithDuplicate = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val model = M.root("root", randomChildren(r, depth = 0))
            if (r.nextInt(6) == 0 && plantDuplicate(model, r)) startedWithDuplicate++
            val before = model.toDocument()
            val (label, actual, expected) = drawAndApply(before, model, r)
            val outcome = if (expected is TopologyEdit.Edited) "$label:edited" else expected::class.simpleName ?: "?"
            outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
            if (actual != expected) {
                failures += "seed $seed $label\n  before   $before\n  actual   $actual\n  expected $expected"
                continue
            }
            if (expected !is TopologyEdit.Edited) continue
            histories[expected.history] = (histories[expected.history] ?: 0) + 1

            val direct = model.toTopologyNodes()
            val read = Topology.from(expected.document)
            if (Topology.problems(model.name, direct).isEmpty()) {
                validTrees++
                val built = TopologyParse.Valid(Topology(model.name, direct))
                if (read != built) failures += "seed $seed $label: read back $read, built $built"
            } else {
                invalidTrees++
                if (read !is TopologyParse.Invalid) failures += "seed $seed $label: read back $read from a tree with problems"
            }
        }
        val editedFloors = listOf("weight", "ceiling", "add", "remove", "rename", "move").map { "$it:edited" }
        val refusalFloors = listOf("NoSuchNode", "NotAGroup", "DuplicateName", "AtRoot", "IntoItself")
        assertAll(
            { assertEquals(emptyList(), failures.take(5), "${failures.size} failures") },
            { editedFloors.forEach { assertTrue((outcomes[it] ?: 0) >= 100, "$it: ${outcomes[it]}") } },
            { refusalFloors.forEach { assertTrue((outcomes[it] ?: 0) >= 50, "$it refusals: ${outcomes[it]}") } },
            { TopologyEdit.History.entries.forEach { assertTrue((histories[it] ?: 0) >= 20, "$it: ${histories[it]}") } },
            { assertTrue(validTrees >= 1000, "valid trees read back: $validTrees") },
            { assertTrue(invalidTrees >= 50, "invalid trees read back: $invalidTrees") },
            { assertTrue(startedWithDuplicate >= 100, "trees that started with a duplicate: $startedWithDuplicate") },
        )
    }

    // ── one edit, both ways ─────────────────────────────────────────────────────────────────────

    private data class Drawn(val label: String, val actual: TopologyEdit, val expected: TopologyEdit)

    /** Draws one edit, applies it to [before] with the edit code and to [model] by hand. */
    private fun drawAndApply(before: TopologyDocument, model: M, r: Random): Drawn {
        val path = drawPath(model, r)
        return when (r.nextInt(6)) {
            0 -> {
                val weight = weights.draw(r)
                Drawn("weight", before.setWeight(path, weight), model.expectChild(path) { node ->
                    val same = weightOf.getValue(node.weight) == weightOf.getValue(weight)
                    node.weight = weight
                    if (same) TopologyEdit.History.KEPT else TopologyEdit.History.SHARE_RESTARTS
                })
            }
            1 -> {
                val ceiling = ceilings.draw(r)
                Drawn("ceiling", before.setMaxConcurrent(path, ceiling), model.expectChild(path) { node ->
                    node.max = ceiling
                    TopologyEdit.History.KEPT
                })
            }
            2 -> {
                val child = randomNode(r, names.draw(r), depth = path.size)
                Drawn("add", before.addChild(path, child.toNode()), model.expectAdd(path, child))
            }
            3 -> Drawn("remove", before.remove(path), model.expectRemove(path))
            4 -> {
                val name = names.draw(r)
                Drawn("rename", before.rename(path, name), model.expectRename(path, name))
            }
            else -> {
                val target = drawPath(model, r)
                Drawn("move", before.move(path, target), model.expectMove(path, target))
            }
        }
    }

    /** A path in [model] three times in four, else one that names nothing. The root is among the paths. */
    private fun drawPath(model: M, r: Random): List<Int> {
        val paths = model.paths()
        if (r.nextInt(4) != 0) return paths.draw(r)
        val anywhere = paths.draw(r)
        return when (r.nextInt(3)) {
            0 -> anywhere + 7
            1 -> anywhere + -1
            else -> if (anywhere.isEmpty()) listOf(model.children.size) else anywhere.dropLast(1) + (model.at(anywhere.dropLast(1))?.children?.size ?: 0)
        }
    }

    // ── the model ───────────────────────────────────────────────────────────────────────────────

    /** A node of the model: the root, a group or a lane. Edited in place. */
    private class M(
        var name: String,
        var weight: String,
        var max: Long?,
        val group: Boolean,
        val children: MutableList<M>,
        val template: TopologyDocument.ChildTemplate?,
    ) {
        companion object {
            fun root(name: String, children: List<M>) = M(name, "1", null, group = true, children.toMutableList(), template = null)
        }
    }

    /** Every path in this tree, the root's `[]` first, in walk order. */
    private fun M.paths(prefix: List<Int> = emptyList()): List<List<Int>> =
        listOf(prefix) + children.flatMapIndexed { index, child -> child.paths(prefix + index) }

    /** The node at [path], or `null`: walked one index at a time, nothing below a lane. */
    private fun M.at(path: List<Int>): M? = path.fold(this as M?) { node, index ->
        if (node == null || !node.group || index !in node.children.indices) null else node.children[index]
    }

    /** The path of exactly this object in the tree, found by looking. */
    private fun M.pathOf(target: M, prefix: List<Int> = emptyList()): List<Int>? {
        if (this === target) return prefix
        children.forEachIndexed { index, child -> child.pathOf(target, prefix + index)?.let { return it } }
        return null
    }

    private fun M.parentOf(target: M): M? =
        if (children.any { it === target }) this else children.firstNotNullOfOrNull { it.parentOf(target) }

    private fun duplicate(parentPath: List<Int>, parent: M, name: String, except: M?): TopologyEdit.DuplicateName? {
        val index = parent.children.indexOfFirst { it !== except && it.name == name }
        return if (index < 0) null else TopologyEdit.DuplicateName(parentPath + index, name)
    }

    private fun M.edited(path: List<Int>, history: TopologyEdit.History) = TopologyEdit.Edited(toDocument(), path, history)

    private fun M.expectChild(path: List<Int>, edit: (M) -> TopologyEdit.History): TopologyEdit {
        if (path.isEmpty()) return TopologyEdit.AtRoot
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        val history = edit(node)
        return edited(path, history)
    }

    private fun M.expectAdd(parentPath: List<Int>, child: M): TopologyEdit {
        val parent = at(parentPath) ?: return TopologyEdit.NoSuchNode(parentPath)
        if (!parent.group) return TopologyEdit.NotAGroup(parentPath)
        duplicate(parentPath, parent, child.name, except = null)?.let { return it }
        parent.children += child
        return edited(pathOf(child) ?: error("the added child is in the tree"), TopologyEdit.History.STARTS_EMPTY)
    }

    private fun M.expectRemove(path: List<Int>): TopologyEdit {
        if (path.isEmpty()) return TopologyEdit.AtRoot
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        val parent = parentOf(node) ?: error("a child has a parent")
        parent.children.removeAll { it === node }
        return edited(path.dropLast(1), TopologyEdit.History.DRAINS)
    }

    private fun M.expectRename(path: List<Int>, newName: String): TopologyEdit {
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        if (path.isEmpty()) {
            val history = if (name == newName) TopologyEdit.History.KEPT else TopologyEdit.History.NEW_TREE
            name = newName
            return edited(path, history)
        }
        val parent = parentOf(node) ?: error("a child has a parent")
        duplicate(path.dropLast(1), parent, newName, except = node)?.let { return it }
        val history = if (node.name == newName) TopologyEdit.History.KEPT else TopologyEdit.History.REPLACED
        node.name = newName
        return edited(path, history)
    }

    private fun M.expectMove(path: List<Int>, targetPath: List<Int>): TopologyEdit {
        if (path.isEmpty()) return TopologyEdit.AtRoot
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        val target = at(targetPath) ?: return TopologyEdit.NoSuchNode(targetPath)
        if (node.pathOf(target) != null) return TopologyEdit.IntoItself(path, targetPath)
        if (!target.group) return TopologyEdit.NotAGroup(targetPath)
        val parent = parentOf(node) ?: error("a child has a parent")
        duplicate(targetPath, target, node.name, except = node)?.let { return it }
        parent.children.removeAll { it === node }
        target.children += node
        val history = if (parent === target) TopologyEdit.History.KEPT else TopologyEdit.History.REPLACED
        return edited(pathOf(node) ?: error("the moved child is in the tree"), history)
    }

    // ── the model, written out two ways ─────────────────────────────────────────────────────────

    private fun M.toDocument(): TopologyDocument = TopologyDocument(name, children.map { it.toNode() })

    private fun M.toNode(): TopologyDocument.Node =
        if (group) {
            TopologyDocument.Group(name, weight, children.map { it.toNode() }, template, max)
        } else {
            TopologyDocument.Lane(name, weight, max)
        }

    /** The root's children as [Topology] nodes, built with the constructors, not read from a document. */
    private fun M.toTopologyNodes(): List<Topology.Node> = children.map { it.toTopologyNode() }

    private fun M.toTopologyNode(): Topology.Node =
        if (group) {
            Topology.Group(
                name,
                weightOf.getValue(weight),
                children.map { it.toTopologyNode() },
                template?.let { Topology.ChildTemplate(weightOf.getValue(it.weight), it.maxConcurrent) },
                max,
            )
        } else {
            Topology.Lane(name, weightOf.getValue(weight), max)
        }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun <T> List<T>.draw(r: Random): T = this[r.nextInt(size)]

    private fun randomChildren(r: Random, depth: Int): List<M> =
        names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, it, depth) }

    private fun randomNode(r: Random, name: String, depth: Int): M =
        if (depth < 2 && r.nextInt(2) == 0) {
            val template = templates.draw(r)
            // A group with a template may declare no children.
            val children = if (template != null && r.nextInt(3) == 0) emptyList() else randomChildren(r, depth + 1)
            M(name, weights.draw(r), ceilings.draw(r), group = true, children.toMutableList(), template)
        } else {
            M(name, weights.draw(r), ceilings.draw(r), group = false, mutableListOf(), template = null)
        }

    /** Gives some group's second child its first child's name; `false` when no group has two. */
    private fun plantDuplicate(model: M, r: Random): Boolean {
        val crowded = model.paths().mapNotNull { model.at(it) }.filter { it.children.size >= 2 }
        if (crowded.isEmpty()) return false
        val group = crowded.draw(r)
        group.children[1].name = group.children[0].name
        return true
    }
}
