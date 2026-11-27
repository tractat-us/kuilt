package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.TopologyDocument.ChildTemplate
import us.tractat.kuilt.heddle.TopologyDocument.Group
import us.tractat.kuilt.heddle.TopologyDocument.Lane
import us.tractat.kuilt.heddle.TopologyDocument.Node
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An app's settings over seeded sequences of edits: every edit carries each node's settings with
 * the node, and [appSettingsChangesTo] reports exactly the settings that differ.
 *
 * Every node and every template level starts with settings no other carries: each value is a fresh
 * tag, so a value names the one map it was put in. After every edit of a sequence the tags are
 * accounted for: no tag appears twice, and the tags the edit lost and gained are exactly the ones
 * it should touch. A move and a rename lose and gain none, and the node lands with its own; a
 * removal loses the removed subtree's; an add gains the added child's; [setTemplate] swaps the old
 * template's for the new one's and keeps the group's; [setAppSettings] swaps the node's own and
 * nothing else. That is invariant 1 of §11.4 in `docs/heddle-design.md`, checked against the
 * documents alone rather than against a second implementation of the edits.
 *
 * [appSettingsChangesTo] is checked against an oracle that does not walk two trees side by side.
 * It flattens each document into one map from a setting's identity (the names from the root, each
 * with its occurrence among siblings of that name, the template depth and the key) to its value,
 * diffs the two maps, and addresses each difference in the document after unless its node is not
 * there. Both every single edit and the whole sequence, first document against last, are checked,
 * so a node moved twice or renamed and moved is compared too.
 *
 * Some trees start with two siblings of one name, so the n-th-of-a-name matching is reached.
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every target,
 * and every edit arm and every kind of change is counted and floored.
 */
class TopologyDocumentAppSettingsPropertyTest {

    private val seeds = 3000
    private val names = listOf("a", "b", "c", "d")
    private val weights = listOf("1", "2", "3/2")
    private val ceilings = listOf(null, 0L, 2L)

    @Test
    fun everyEditCarriesEachNodesOwnSettingsAndTheReportMatchesTheOracle() {
        val failures = ArrayList<String>()
        val counts = HashMap<String, Int>()
        fun count(what: String) {
            counts[what] = (counts[what] ?: 0) + 1
        }
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val tags = Tags()
            val start = TopologyDocument("root", randomChildren(r, tags, depth = 0))
                .let { if (r.nextInt(5) == 0) plantDuplicate(it).also { d -> if (d != it) count("started with a duplicate") } else it }
            var document = start
            repeat(1 + r.nextInt(6)) { step ->
                val drawn = drawEdit(document, r, tags)
                val edit = drawn.edit
                if (edit !is TopologyEdit.Edited) {
                    count("refused")
                    return@repeat
                }
                count("${drawn.label}:edited")
                if (edit.history == TopologyEdit.History.REPLACED && drawn.carried) {
                    count(if (drawn.label == "rename") "renamed with its own settings" else "moved with its own settings")
                }
                drawn.check(edit)?.let { failures += "seed $seed step $step ${drawn.label}: $it\n  before $document\n  after  ${edit.document}" }
                checkReport(document, edit.document, ::count)?.let { failures += "seed $seed step $step ${drawn.label} report: $it" }
                document = edit.document
            }
            checkReport(start, document, ::count)?.let { failures += "seed $seed whole sequence report: $it" }
        }
        // Each floor is about half what the generator reaches on the JVM.
        val floors = listOf(
            "weight:edited" to 250,
            "ceiling:edited" to 250,
            "add:edited" to 130,
            "remove:edited" to 250,
            "rename:edited" to 250,
            "move:edited" to 140,
            "move at:edited" to 160,
            "template:edited" to 85,
            "settings:edited" to 250,
            "refused" to 3000,
            "started with a duplicate" to 190,
            "moved with its own settings" to 70,
            "renamed with its own settings" to 130,
            "report Added" to 2800,
            "report Removed of a gone node" to 2900,
            "report Removed of a node that stays" to 380,
            "report Changed" to 450,
            "report Changed at a template depth" to 170,
            "report across a renamed root" to 190,
            "report between duplicate siblings" to 240,
            "report empty" to 1600,
        )
        val short = floors.filter { (what, floor) -> (counts[what] ?: 0) < floor }.map { (what, floor) -> "$what: ${counts[what] ?: 0} < $floor" }
        assertAll(
            { assertEquals(emptyList(), failures.take(5), "${failures.size} failures") },
            { assertEquals(emptyList(), short, "every count: ${counts.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}") },
        )
    }

    // ── one edit ────────────────────────────────────────────────────────────────────────────────

    /**
     * One drawn edit, with [check] saying what is wrong with the tags it left, or `null`. [carried]
     * is whether the node it addresses had settings to carry before the edit.
     */
    private class Drawn(val label: String, val edit: TopologyEdit, val carried: Boolean, val check: (TopologyEdit.Edited) -> String?)

    private fun drawEdit(document: TopologyDocument, r: Random, tags: Tags): Drawn {
        val path = drawPath(document, r)
        val node = document.at(path)
        val subtree = node?.tags().orEmpty()
        val carried = subtree.isNotEmpty()

        // An edit that changes no settings: the subtree lands at the edited path with every tag it had.
        fun keeps(label: String, edit: TopologyEdit) = Drawn(label, edit, carried) { edited ->
            tagsMoved(document, edited, lost = emptySet(), gained = emptySet())
                ?: edited.document.at(edited.path)?.tags().orEmpty().let { if (it == subtree) null else "landed with $it, had $subtree" }
        }
        // Moves are drawn twice as often as the rest: most land on a name the target already has.
        return when (r.nextInt(11)) {
            0 -> keeps("weight", document.setWeight(path, weights.draw(r)))
            1 -> keeps("ceiling", document.setMaxConcurrent(path, ceilings.draw(r)))
            2 -> {
                val child = randomNode(r, tags, names.draw(r), depth = path.size)
                Drawn("add", document.addChild(path, child), carried) { edited ->
                    tagsMoved(document, edited, lost = emptySet(), gained = child.tags().toSet())
                        ?: edited.document.at(edited.path).let { if (it == child) null else "landed as $it, added $child" }
                }
            }
            3 -> Drawn("remove", document.remove(path), carried) { edited -> tagsMoved(document, edited, lost = subtree.toSet(), gained = emptySet()) }
            4 -> keeps("rename", document.rename(path, names.draw(r)))
            5, 6 -> keeps("move", document.move(path, drawPath(document, r)))
            7, 8 -> {
                // Half the time into the parent the child already has, so reorders are reached.
                val target = if (path.isNotEmpty() && r.nextBoolean()) path.dropLast(1) else drawPath(document, r)
                val size = if (target.isEmpty()) document.children.size else (document.at(target) as? Group)?.children?.size ?: 0
                keeps("move at", document.move(path, target, r.nextInt(-1, size + 1)))
            }
            9 -> {
                val template = randomTemplate(r, tags)
                Drawn("template", document.setTemplate(path, template), carried) { edited ->
                    tagsMoved(document, edited, lost = node?.templateTags().orEmpty().toSet(), gained = template.chainTags().toSet())
                        ?: ownAt(edited, node?.own().orEmpty())
                }
            }
            else -> {
                val settings = randomSettings(r, tags)
                Drawn("settings", document.setAppSettings(path, settings), carried) { edited ->
                    tagsMoved(document, edited, lost = node?.own().orEmpty().toSet(), gained = settings.values.toSet())
                        ?: edited.document.at(edited.path)?.levels()?.first().let { if (it == settings) null else "set $settings, holds $it" }
                }
            }
        }
    }

    /**
     * Whether the tags of [edited] are those of [before] less exactly [lost] and plus exactly
     * [gained], with none appearing twice; `null` when they are.
     */
    private fun tagsMoved(before: TopologyDocument, edited: TopologyEdit.Edited, lost: Set<String>, gained: Set<String>): String? {
        val all = edited.document.tags()
        if (all.size != all.toSet().size) return "a tag appears twice: $all"
        val was = before.tags().toSet()
        val now = all.toSet()
        val actuallyLost = was - now
        val actuallyGained = now - was
        return if (actuallyLost == lost && actuallyGained == gained) {
            null
        } else {
            "lost $actuallyLost, gained $actuallyGained; expected to lose $lost and gain $gained"
        }
    }

    private fun ownAt(edited: TopologyEdit.Edited, own: List<String>): String? {
        val now = edited.document.at(edited.path)?.own().orEmpty()
        return if (now == own) null else "the node's own settings went from $own to $now"
    }

    // ── the report against an oracle ────────────────────────────────────────────────────────────

    /** A node, by its names from the root down, each with how many earlier siblings had that name. */
    private data class NodeKey(val steps: List<Pair<String, Int>>)

    private data class SettingKey(val node: NodeKey, val depth: Int, val key: String)

    private class Flat(val values: Map<SettingKey, String>, val addresses: Map<NodeKey, String>, val duplicated: Boolean)

    private fun TopologyDocument.flatten(): Flat {
        val values = LinkedHashMap<SettingKey, String>()
        val addresses = LinkedHashMap<NodeKey, String>()
        var duplicated = false
        fun walk(nodes: List<Node>, key: List<Pair<String, Int>>, path: List<Int>, names: List<String>) {
            nodes.forEachIndexed { index, node ->
                val occurrence = nodes.subList(0, index).count { it.name == node.name }
                if (occurrence > 0) duplicated = true
                val here = NodeKey(key + (node.name to occurrence))
                addresses[here] = "${path + index} ${names + node.name}"
                node.levels().forEachIndexed { depth, settings -> settings.forEach { (k, v) -> values[SettingKey(here, depth, k)] = v } }
                walk((node as? Group)?.children.orEmpty(), here.steps, path + index, names + node.name)
            }
        }
        walk(children, listOf(root to 0), emptyList(), listOf(root))
        return Flat(values, addresses, duplicated)
    }

    /** The node's own settings, then each template level's. */
    private fun Node.levels(): List<Map<String, String>> = when (this) {
        is Lane -> listOf(appSettings)
        is Group -> listOf(appSettings) + generateSequence(template) { it.template }.map { it.appSettings }
    }

    private fun AppSettingsChange.rendered(): String = when (this) {
        is AppSettingsChange.Added -> "Added $path $names $templateDepth $key=$value"
        is AppSettingsChange.Removed -> "Removed $path $names $templateDepth $key=$value"
        is AppSettingsChange.Changed -> "Changed $path $names $templateDepth $key=$before->$after"
    }

    /**
     * What is wrong with `before.appSettingsChangesTo(after)` against the flattened diff, or `null`.
     * Compared as sorted lists, so the order is checked on its own: within one node and depth the
     * keys come sorted. Only two changes addressed in the same document are compared, since a
     * gone node's [AppSettingsChange.Removed] may share its path and names with a node of the
     * document after: with two siblings named `a`, renaming the first leaves the second matched to
     * the first's settings at the same address as its own, which are removed.
     */
    private fun checkReport(before: TopologyDocument, after: TopologyDocument, count: (String) -> Unit): String? {
        val was = before.flatten()
        val now = after.flatten()
        val goneValues = HashSet<String>()
        val expected = (was.values.keys + now.values.keys).mapNotNull { setting ->
            val old = was.values[setting]
            val new = now.values[setting]
            when {
                old == null -> "Added ${now.addresses[setting.node]} ${setting.depth} ${setting.key}=$new"
                new == null -> {
                    val stays = now.addresses[setting.node]
                    count(if (stays == null) "report Removed of a gone node" else "report Removed of a node that stays")
                    if (stays == null) goneValues += old
                    "Removed ${stays ?: was.addresses[setting.node]} ${setting.depth} ${setting.key}=$old"
                }
                old != new -> {
                    count("report Changed")
                    if (setting.depth > 0) count("report Changed at a template depth")
                    "Changed ${now.addresses[setting.node]} ${setting.depth} ${setting.key}=$old->$new"
                }
                else -> null
            }
        }.sorted()
        val changes = before.appSettingsChangesTo(after)
        changes.forEach { if (it is AppSettingsChange.Added) count("report Added") }
        if (changes.isEmpty()) count("report empty")
        if (changes.isNotEmpty() && before.root != after.root) count("report across a renamed root")
        if (changes.isNotEmpty() && (was.duplicated || now.duplicated)) count("report between duplicate siblings")
        // Every value is a unique tag, so a value says which removal is of a gone node.
        val gone = changes.filter { it is AppSettingsChange.Removed && it.value in goneValues }.toSet()
        val actual = changes.map { it.rendered() }.sorted()
        if (actual != expected) return "listed $actual, expected $expected"
        val unsorted = changes.zipWithNext().firstOrNull { (a, b) ->
            val sameDocument = (a in gone) == (b in gone)
            sameDocument && a.path == b.path && a.names == b.names && a.templateDepth == b.templateDepth && a.key >= b.key
        }
        return unsorted?.let { "keys out of order: $it" }
    }

    // ── documents ───────────────────────────────────────────────────────────────────────────────

    /** Hands out values no other map has, so a value names the one map it was put in. */
    private class Tags {
        private var next = 0
        fun fresh(): String = "t${next++}"
    }

    private fun TopologyDocument.at(path: List<Int>): Node? = path.fold(null as Node?) { node, index ->
        val siblings = if (node == null) children else (node as? Group)?.children ?: return null
        siblings.getOrNull(index) ?: return null
    }.takeIf { path.isNotEmpty() }

    private fun <T> List<T>.draw(r: Random): T = this[r.nextInt(size)]

    private fun randomChildren(r: Random, tags: Tags, depth: Int): List<Node> =
        names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, tags, it, depth) }

    private fun randomNode(r: Random, tags: Tags, name: String, depth: Int): Node =
        if (depth < 2 && r.nextInt(2) == 0) {
            val template = randomTemplate(r, tags)
            val children = if (template != null && r.nextInt(3) == 0) emptyList() else randomChildren(r, tags, depth + 1)
            Group(name, weights.draw(r), children, template, ceilings.draw(r), mapOf("own" to tags.fresh()))
        } else {
            Lane(name, weights.draw(r), ceilings.draw(r), mapOf("own" to tags.fresh()))
        }

    /** No template a third of the time, else a chain of one to three levels, each with its own tag. */
    private fun randomTemplate(r: Random, tags: Tags): ChildTemplate? {
        val levels = r.nextInt(4)
        if (levels == 0) return null
        return (1 until levels).fold(ChildTemplate(weights.draw(r), appSettings = mapOf("split" to tags.fresh()))) { inner, _ ->
            ChildTemplate(weights.draw(r), ceilings.draw(r), inner, mapOf("split" to tags.fresh()))
        }
    }

    /** Settings for [setAppSettings]: none, the same key, an extra key, or another key altogether. */
    private fun randomSettings(r: Random, tags: Tags): Map<String, String> = when (r.nextInt(4)) {
        0 -> emptyMap()
        1 -> mapOf("own" to tags.fresh())
        2 -> mapOf("own" to tags.fresh(), "extra" to tags.fresh())
        else -> mapOf("other" to tags.fresh())
    }

    private fun drawPath(document: TopologyDocument, r: Random): List<Int> {
        val paths = document.paths()
        if (r.nextInt(5) != 0) return paths.draw(r)
        return paths.draw(r) + 9
    }

    /** Every path, the root's `[]` first, in walk order. */
    private fun TopologyDocument.paths(): List<List<Int>> {
        fun walk(nodes: List<Node>, prefix: List<Int>): List<List<Int>> = nodes.flatMapIndexed { index, node ->
            listOf(prefix + index) + walk((node as? Group)?.children.orEmpty(), prefix + index)
        }
        return listOf(emptyList<Int>()) + walk(children, emptyList())
    }

    /** Every settings value in the document, in walk order: a node's own, its template levels', then its children's. */
    private fun TopologyDocument.tags(): List<String> = children.flatMap { it.tags() }

    private fun Node.tags(): List<String> = own() + templateTags() + ((this as? Group)?.children.orEmpty()).flatMap { it.tags() }

    private fun Node.own(): List<String> = when (this) {
        is Group -> appSettings.values.toList()
        is Lane -> appSettings.values.toList()
    }

    private fun Node.templateTags(): List<String> = (this as? Group)?.template.chainTags()

    private fun ChildTemplate?.chainTags(): List<String> = generateSequence(this) { it.template }.flatMap { it.appSettings.values }.toList()

    /** Gives some group's second child its first child's name, or returns the document as it was. */
    private fun plantDuplicate(document: TopologyDocument): TopologyDocument {
        fun plant(nodes: List<Node>): List<Node>? {
            if (nodes.size >= 2) return listOf(nodes[0], nodes[1].renamed(nodes[0].name)) + nodes.drop(2)
            return null
        }
        return plant(document.children)?.let { document.copy(children = it) } ?: document
    }

    private fun Node.renamed(name: String): Node = when (this) {
        is Group -> copy(name = name)
        is Lane -> copy(name = name)
    }
}
