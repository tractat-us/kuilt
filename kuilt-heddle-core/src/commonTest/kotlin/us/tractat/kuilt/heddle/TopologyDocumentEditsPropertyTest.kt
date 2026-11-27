package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
 *   the edited model whenever that topology is valid, and [TopologyParse.Invalid] otherwise;
 * - when the trees before and after are both valid, the edit's [TopologyEdit.History] has the
 *   shape [Topology.changesTo] gives between them, since the model copies the history rules and
 *   so cannot catch one both copies got wrong.
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

    /** Seeds after [seeds] that each draw a move to an index, so every edit above keeps its own trajectory. */
    private val moveAtSeeds = 1500

    /**
     * Seeds after those that draw a move to an index whose target, when it is not the child's own
     * parent, is a group outside that parent and the child's own subtree. Unbiased, a move to
     * another parent at an interior index is about one draw in three hundred, too few to floor.
     * Biased, it is about one in forty: most draws still land on a refusal or at the end.
     */
    private val moveElsewhereSeeds = 1500
    private val names = listOf("a", "b", "c", "d")
    private val weightOf = mapOf("1" to Weight.ONE, "2" to Weight.of(2), "3/2" to Weight.of(3, 2), "6/4" to Weight.of(3, 2))
    private val weights = weightOf.keys.toList()
    private val ceilings = listOf(null, 0L, 2L)
    private val templates = listOf(null, null, TopologyDocument.ChildTemplate(), TopologyDocument.ChildTemplate("2", 1L)) + nestedTemplates()

    /**
     * Nested templates, which trees start with and [setTemplate] draws from beside the flat ones.
     * They differ in every way that tells the histories apart: `"3/2"` beside `"6/4"` is a weight
     * that reads the same, `1L` beside `null` a ceiling alone, and the one-, two- and three-level
     * forms of one chain a level added or gone.
     */
    private fun nestedTemplates() = listOf(
        TopologyDocument.ChildTemplate("3/2", template = TopologyDocument.ChildTemplate("1")),
        TopologyDocument.ChildTemplate("6/4", 1L, TopologyDocument.ChildTemplate("1", 2L)),
        TopologyDocument.ChildTemplate("3/2"),
        TopologyDocument.ChildTemplate("3/2", template = TopologyDocument.ChildTemplate("2", template = TopologyDocument.ChildTemplate())),
        TopologyDocument.ChildTemplate("2", template = TopologyDocument.ChildTemplate("1")),
    )

    @Test
    fun anEditIsTheModelsEditAndReadsBackAsTheModelsTree() {
        val failures = ArrayList<String>()
        val outcomes = HashMap<String, Int>()
        val histories = HashMap<TopologyEdit.History, Int>()
        val agreements = HashMap<TopologyEdit.History, Int>()
        val templateAgreements = HashMap<TopologyEdit.History, Int>()
        var validTrees = 0
        var invalidTrees = 0
        var startedWithDuplicate = 0
        for (seed in 0 until seeds + moveAtSeeds + moveElsewhereSeeds) {
            val r = Random(seed)
            val model = M.root("root", randomChildren(r, depth = 0))
            if (r.nextInt(6) == 0 && plantDuplicate(model, r)) startedWithDuplicate++
            val before = model.toDocument()
            val (label, actual, expected) = when {
                seed < seeds -> drawAndApply(before, model, r)
                seed < seeds + moveAtSeeds -> drawMoveAt(before, model, r, elsewhere = false)
                else -> drawMoveAt(before, model, r, elsewhere = true)
            }
            val outcome = if (expected is TopologyEdit.Edited) "$label:edited" else expected::class.simpleName ?: "?"
            outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
            if ((label == "template" || label.startsWith("move at")) && expected !is TopologyEdit.Edited) outcomes["$label:$outcome"] = (outcomes["$label:$outcome"] ?: 0) + 1
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
                val readBefore = Topology.from(before)
                if (readBefore is TopologyParse.Valid && read is TopologyParse.Valid) {
                    val changes = readBefore.topology.changesTo(read.topology)
                    agreements[expected.history] = (agreements[expected.history] ?: 0) + 1
                    if (label == "template") templateAgreements[expected.history] = (templateAgreements[expected.history] ?: 0) + 1
                    if (!expected.history.agreesWith(changes)) {
                        failures += "seed $seed $label: history ${expected.history}, but changesTo lists $changes"
                    }
                }
            } else {
                invalidTrees++
                if (read !is TopologyParse.Invalid) failures += "seed $seed $label: read back $read from a tree with problems"
            }
        }
        // Each floor is about half what the generator reaches on the JVM, so a generator that
        // stopped reaching an arm reds, and one that drifted a little does not.
        val tally = listOf("weight", "ceiling", "add", "remove", "rename", "move", "move at", "move at reorder", "template").map { "$it:edited" to (outcomes["$it:edited"] ?: 0) to 50 } +
            // The one arm a move that appended under another parent would pass, so it is floored on
            // its own. It is reached 40 times on every target, 35 of them by the biased seeds;
            // with the bias taken off those seeds it is reached 9 times, below the floor.
            listOf("move at another parent:edited" to (outcomes["move at another parent:edited"] ?: 0) to 20) +
            listOf("NoSuchNode", "NotAGroup", "DuplicateName", "AtRoot", "IntoItself").map { it to (outcomes[it] ?: 0) to 50 } +
            listOf("NoSuchNode", "NotAGroup", "AtRoot").map { "template:$it" to (outcomes["template:$it"] ?: 0) to 50 } +
            listOf("move at index:NoSuchNode" to (outcomes["move at index:NoSuchNode"] ?: 0) to 50) +
            templateHistories.map { "template ${it.name} against changesTo" to (templateAgreements[it] ?: 0) to 20 } +
            TopologyEdit.History.entries.map { it.name to (histories[it] ?: 0) to 30 } +
            TopologyEdit.History.entries.map { "${it.name} against changesTo" to (agreements[it] ?: 0) to 30 } +
            listOf("against changesTo" to agreements.values.sum() to 500) +
            listOf("valid trees" to validTrees to 500, "invalid trees" to invalidTrees to 80, "started with a duplicate" to startedWithDuplicate to 150)
        val short = tally.filter { (counted, floor) -> counted.second < floor }.map { (counted, floor) -> "${counted.first}: ${counted.second} < $floor" }
        assertAll(
            { assertEquals(emptyList(), failures.take(5), "${failures.size} failures") },
            { assertEquals(emptyList(), short, "every count: ${tally.joinToString { (counted, _) -> "${counted.first}=${counted.second}" }}") },
        )
    }

    // ── one edit, both ways ─────────────────────────────────────────────────────────────────────

    private data class Drawn(val label: String, val actual: TopologyEdit, val expected: TopologyEdit)

    /** Draws one edit, applies it to [before] with the edit code and to [model] by hand. */
    private fun drawAndApply(before: TopologyDocument, model: M, r: Random): Drawn {
        val path = drawPath(model, r)
        // A template edit lands only on a group, and loses history only on one that has a template, so
        // it is drawn twice as often as the others to reach every history against changesTo.
        return when (r.nextInt(9)) {
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
            5, 6 -> {
                val template = templates.draw(r)
                Drawn("template", before.setTemplate(path, template), model.expectTemplate(path, template))
            }
            else -> {
                val target = drawPath(model, r)
                Drawn("move", before.move(path, target), model.expectMove(path, target) { end -> end })
            }
        }
    }

    /**
     * Draws one move to an index, half the time into the parent the child already has so that
     * reorders are reached, and applies it both ways. With [elsewhere], the other half draws a
     * group outside that parent and the child's own subtree when the tree has one, so that moves
     * to another parent at an interior index are reached often enough to floor.
     */
    private fun drawMoveAt(before: TopologyDocument, model: M, r: Random, elsewhere: Boolean): Drawn {
        val path = drawPath(model, r)
        val target = when {
            path.isNotEmpty() && r.nextBoolean() -> path.dropLast(1)
            elsewhere -> otherGroups(model, path).takeIf { it.isNotEmpty() }?.draw(r) ?: drawPath(model, r)
            else -> drawPath(model, r)
        }
        // From one before the first child to one past another parent's end, so both ends are
        // reached and refused, as is the end of a reorder, which is the child count less one.
        val end = model.at(target)?.children?.size ?: 0
        val index = r.nextInt(-1, end + 2)
        val found = model.at(path) != null
        val expected = model.expectMove(path, target) { index }
        return Drawn(moveAtLabel(before, found, target + index, index < end, expected), before.move(path, target, index), expected)
    }

    /** Every group in [model], in walk order, that is neither the parent of [path] nor at or below it. */
    private fun otherGroups(model: M, path: List<Int>): List<List<Int>> = model.paths().filter { candidate ->
        model.at(candidate)?.group == true && candidate != path.dropLast(1) &&
            !(candidate.size >= path.size && candidate.subList(0, path.size) == path)
    }

    /**
     * Which arm a move to an index took: refused for the index itself, a reorder that changed the
     * order, a move to another parent that landed [beforeTheEnd] of its children, or anything else.
     * Once the child to move was [found], the index refusal is the only one naming the [place] it
     * was to land, and a move to another parent is the only one that replaces the child's history.
     */
    private fun moveAtLabel(before: TopologyDocument, found: Boolean, place: List<Int>, beforeTheEnd: Boolean, expected: TopologyEdit): String = when {
        found && expected == TopologyEdit.NoSuchNode(place) -> "move at index"
        expected is TopologyEdit.Edited && expected.history == TopologyEdit.History.KEPT && expected.document != before -> "move at reorder"
        expected is TopologyEdit.Edited && expected.history == TopologyEdit.History.REPLACED && beforeTheEnd -> "move at another parent"
        else -> "move at"
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

    // ── History against changesTo ───────────────────────────────────────────────────────────────

    /**
     * Whether [changes], what [Topology.changesTo] lists between the trees before and after one
     * edit, has the shape this history names. The model above writes the history rules a second
     * time, so a rule both copies got wrong agrees with itself; this is the one check of them
     * against the code that reads what a save will cost. Sibling order is not a change, so a
     * reorder lists nothing.
     */
    private fun TopologyEdit.History.agreesWith(changes: List<TopologyChange>): Boolean {
        val kinds = changes.map { it.kind }
        val template = changes.singleOrNull()?.takeIf { it.kind.startsWith("template") }
        return when (this) {
            TopologyEdit.History.KEPT -> kinds.isEmpty() || kinds == listOf("ceiling") || template?.saveEffect == TopologyChange.SaveEffect.KEPT
            // A template edit that loses history either restarts the attached children's shares,
            // keeping every level, or drains the children of a level it takes away.
            TopologyEdit.History.SHARE_RESTARTS -> kinds == listOf("weight") ||
                (template is TopologyChange.TemplateChanged && template.saveEffect == TopologyChange.SaveEffect.LOST && template.after.levels >= template.before.levels)
            TopologyEdit.History.STARTS_EMPTY -> kinds == listOf("added")
            TopologyEdit.History.DRAINS -> kinds == listOf("removed") || kinds == listOf("template removed") ||
                (template is TopologyChange.TemplateChanged && template.after.levels < template.before.levels)
            TopologyEdit.History.REPLACED -> kinds.sorted() == listOf("added", "removed")
            TopologyEdit.History.NEW_TREE -> kinds.firstOrNull() == "root" && kinds.drop(1).all { it == "added" || it == "removed" }
        }
    }

    /** How many templates deep this chain goes: `1` for a template with no nested one. */
    private val Topology.ChildTemplate.levels: Int get() = 1 + (template?.levels ?: 0)

    private val TopologyChange.kind: String
        get() = when (this) {
            is TopologyChange.RootRenamed -> "root"
            is TopologyChange.Added -> "added"
            is TopologyChange.Removed -> "removed"
            is TopologyChange.WeightChanged -> "weight"
            is TopologyChange.CeilingChanged -> "ceiling"
            is TopologyChange.LaneBecameGroup -> "lane to group"
            is TopologyChange.GroupBecameLane -> "group to lane"
            is TopologyChange.TemplateAdded -> "template added"
            is TopologyChange.TemplateRemoved -> "template removed"
            is TopologyChange.TemplateChanged -> "template changed"
        }

    // ── the model ───────────────────────────────────────────────────────────────────────────────

    /** A node of the model: the root, a group or a lane. Edited in place. */
    private class M(
        var name: String,
        var weight: String,
        var max: Long?,
        val group: Boolean,
        val children: MutableList<M>,
        var template: TopologyDocument.ChildTemplate?,
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

    /**
     * The history of the node's template going to [template], read off the two chains as lists
     * rather than recursively: a level gone drains, else a weight that reads differently at a level
     * both have restarts shares, else nothing is lost.
     */
    private fun M.expectTemplate(path: List<Int>, template: TopologyDocument.ChildTemplate?): TopologyEdit {
        if (path.isEmpty()) return TopologyEdit.AtRoot
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        if (!node.group) return TopologyEdit.NotAGroup(path)
        val before = generateSequence(node.template) { it.template }.toList()
        val after = generateSequence(template) { it.template }.toList()
        node.template = template
        val history = when {
            after.size < before.size -> TopologyEdit.History.DRAINS
            before.indices.any { weightOf.getValue(before[it].weight) != weightOf.getValue(after[it].weight) } -> TopologyEdit.History.SHARE_RESTARTS
            else -> TopologyEdit.History.KEPT
        }
        return edited(path, history)
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

    /**
     * A move, with [place] answering where to put the child given the end. The end is read off the
     * target with the child actually taken out, and a refused move puts the child back where it
     * was, so no index shift is computed here either.
     */
    private fun M.expectMove(path: List<Int>, targetPath: List<Int>, place: (end: Int) -> Int): TopologyEdit {
        if (path.isEmpty()) return TopologyEdit.AtRoot
        val node = at(path) ?: return TopologyEdit.NoSuchNode(path)
        val target = at(targetPath) ?: return TopologyEdit.NoSuchNode(targetPath)
        if (node.pathOf(target) != null) return TopologyEdit.IntoItself(path, targetPath)
        if (!target.group) return TopologyEdit.NotAGroup(targetPath)
        val parent = parentOf(node) ?: error("a child has a parent")
        // Named before the child is out, so the clash is at its index in the document asked of.
        val clash = duplicate(targetPath, target, node.name, except = node)
        val was = parent.children.indexOfFirst { it === node }
        parent.children.removeAt(was)
        val index = place(target.children.size)
        val refusal = if (index !in 0..target.children.size) TopologyEdit.NoSuchNode(targetPath + index) else clash
        if (refusal != null) {
            parent.children.add(was, node)
            return refusal
        }
        target.children.add(index, node)
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
                template?.toTopologyTemplate(),
                max,
            )
        } else {
            Topology.Lane(name, weightOf.getValue(weight), max)
        }

    private fun TopologyDocument.ChildTemplate.toTopologyTemplate(): Topology.ChildTemplate =
        Topology.ChildTemplate(weightOf.getValue(weight), maxConcurrent, template?.toTopologyTemplate())

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private val templateHistories = listOf(TopologyEdit.History.KEPT, TopologyEdit.History.SHARE_RESTARTS, TopologyEdit.History.DRAINS)

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
