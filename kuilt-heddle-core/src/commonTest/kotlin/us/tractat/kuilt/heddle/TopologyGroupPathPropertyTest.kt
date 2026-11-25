package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Topology.findGroupId] and [Topology.findPath] are exact inverses, over seeded random trees whose
 * names are built from the awkward pieces [TopologyLoweringTest.flatteningIsInjectiveOverAwkwardNames]
 * pins: a slash, a pre-escaped slash, the template, the id grammar's punctuation, a non-ASCII
 * letter, both lone surrogates and an emoji. Joining one or two pieces makes names that a naive
 * encoding would fold together, such as `a/b` beside a group `a` holding `b`.
 *
 * Each tree is checked against every path it has a group for, attached children included, under
 * names drawn from the same pieces so that they collide with declared siblings; and against paths
 * and ids it has no group for, taken from a second tree with the same root and from edits of real
 * ids. For every path `p` and id `i` tried:
 *
 * - `findGroupId(p)` is `null` or an id `i` with `findPath(i) == p`;
 * - `findPath(i)` is `null` or a path `p` with `findGroupId(p) == i`;
 * - either is non-null exactly when the tree has that group, judged by this file's own walk;
 *
 * and every path the tree declares or can attach resolves, to the id [Topology.groupId] or
 * [Topology.attachedChildId] gives it. The third law is not implied by the first two: a pair that
 * resolved every path in the id grammar, this tree's or not, would satisfy both.
 *
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every target,
 * and the cases each law is meant to exercise are counted and floored, so a generator that drifted
 * towards trees with no awkward collisions reds instead of passing.
 */
class TopologyGroupPathPropertyTest {

    private val seeds = 200
    private val pieces = listOf("a", "b", "x", "/", "%2F", "!", "!x", "@1:1#0", "#3", "%", "?", "é", "\uD800", "\uDC00", "😀")

    @Test
    fun findPathAndFindGroupIdAreExactInverses() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            // Nesting draws from its own generator, so the trajectory `r` walks is the one it walked
            // before templates could nest (#3314), and so are the floors measured on it.
            val nest = Random(seed + NEST_SEED)
            val root = if (r.nextBoolean()) "root" else randomName(r)
            val t = randomTree(r, root, nest)
            val other = randomTree(r, root, nest)

            val attachedHere = runtimePaths(t, r)
            val (nestedHere, nestedNowhere) = nestedPaths(t, attachedHere, nest)
            val resolvable = declaredPaths(t.children, emptyList()).map { GroupPath.Declared(it) to t.groupId(*it.toTypedArray()) } +
                attachedHere.map { GroupPath.Attached(it.first, it.second) to t.attachedChildId(it.first, it.second) } +
                nestedHere.map { path -> path to nestedId(t, path) }
            for ((path, id) in resolvable) {
                if (t.findGroupId(path) != id) failures += "seed $seed: findGroupId($path) = ${t.findGroupId(path)}, groupId gives ${id.value}"
                if (t.findPath(id) != path) failures += "seed $seed: findPath(${id.value}) = ${t.findPath(id)}, expected $path"
                tally.resolved(path)
            }
            val ids = resolvable.map { it.second }
            if (ids.toSet().size != ids.size) failures += "seed $seed: ids collide ${ids.map { it.value }}"
            tally.collisions += resolvable.count { (path, _) ->
                path is GroupPath.Attached && GroupPath.Declared(path.names) in resolvable.map { it.first }
            }

            // Which paths this tree has a group for, read off the tree by this file's own walk: the
            // laws alone hold for a pair that resolves every path in the grammar, here or not.
            val declaredHere = declaredPaths(t.children, emptyList())
            val marked = markedGroups(t.children, emptyList())
            val markedHere = marked.map { it.first }
            fun here(path: GroupPath): Boolean = when (path) {
                is GroupPath.Declared -> path.names in declaredHere
                is GroupPath.Attached -> path.group in markedHere
                // Generated one level deep: the group's template must carry a template.
                is GroupPath.Nested -> (path.parent as? GroupPath.Attached)?.let { parent ->
                    marked.firstOrNull { it.first == parent.group }?.second?.template?.template != null
                } == true
            }

            // Paths and ids from a sibling tree, and edits of real ids: most are not this tree's.
            val attachedThere = runtimePaths(other, r)
            val (nestedThere, nestedNotThere) = nestedPaths(other, attachedThere, nest)
            val siblingPaths = declaredPaths(other.children, emptyList()).map { GroupPath.Declared(it) } +
                attachedThere.map { GroupPath.Attached(it.first, it.second) } + nestedThere
            val foreignPaths = siblingPaths + nestedNotThere + nestedNowhere
            for (path in foreignPaths) {
                val id = t.findGroupId(path)
                if ((id != null) != here(path)) failures += "seed $seed: findGroupId($path) = $id, but the tree ${if (here(path)) "has" else "has no"} such group"
                if (id == null) tally.unresolvedPaths++ else if (t.findPath(id) != path) failures += "seed $seed: findPath(findGroupId($path)) = ${t.findPath(id)}"
            }
            // Same root, so a sibling's id is this tree's exactly when its path is.
            for (path in siblingPaths) {
                val id = checkNotNull(other.findGroupId(path)) { "seed $seed: the sibling resolves its own $path" }
                val expected = path.takeIf { here(it) }
                if (t.findPath(id) != expected) failures += "seed $seed: findPath(${id.value}) = ${t.findPath(id)}, expected $expected"
            }
            val foreignIds = foreignPaths.mapNotNull { other.findGroupId(it) } + ids.flatMap { edits(it, r) }
            for (id in foreignIds) {
                val path = t.findPath(id)
                when {
                    path == null -> tally.unresolvedIds++
                    !here(path) -> failures += "seed $seed: findPath(${id.value}) = $path, which the tree has no group for"
                    t.findGroupId(path) != id -> failures += "seed $seed: findGroupId(findPath(${id.value})) = ${t.findGroupId(path)}"
                }
            }
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            // Floors set below the counts measured on every target, so a drifting generator reds.
            { assertTrue(tally.declared >= 1_500, "declared ${tally.declared}") },
            { assertTrue(tally.runtime >= 250, "runtime ${tally.runtime}") },
            { assertTrue(tally.nested >= 280, "nested ${tally.nested}") },
            { assertTrue(tally.collisions >= 120, "attached children named like a declared sibling ${tally.collisions}") },
            { assertTrue(tally.unresolvedPaths >= 1_500, "unresolved paths ${tally.unresolvedPaths}") },
            { assertTrue(tally.unresolvedIds >= 10_000, "unresolved ids ${tally.unresolvedIds}") },
        )
    }

    private companion object {
        const val NEST_SEED = 1_000_003
    }

    private class Tally {
        var declared = 0
        var runtime = 0
        var nested = 0
        var collisions = 0
        var unresolvedPaths = 0
        var unresolvedIds = 0

        fun resolved(path: GroupPath) {
            when (path) {
                is GroupPath.Declared -> declared++
                is GroupPath.Attached -> runtime++
                is GroupPath.Nested -> nested++
            }
        }
    }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun randomName(r: Random): String =
        (1..1 + r.nextInt(2)).joinToString("") { pieces[r.nextInt(pieces.size)] }

    /** Depth up to 3, up to 4 children per group, names unique among siblings; a third of the groups with a template. */
    private fun randomTree(r: Random, root: String, nest: Random): Topology = Topology(root, randomChildren(r, depth = 0, nest))

    private fun randomChildren(r: Random, depth: Int, nest: Random): List<Node> {
        val names = ArrayList<String>()
        repeat(1 + r.nextInt(4)) {
            val name = randomName(r)
            if (name !in names) names += name
        }
        return names.map { name ->
            if (depth < 2 && r.nextInt(2) == 0) {
                val withTemplate = r.nextInt(3) == 0
                val inner = if (nest.nextBoolean()) ChildTemplate(Weight.ONE) else null
                Group(name, Weight.ONE, randomChildren(r, depth + 1, nest), if (withTemplate) ChildTemplate(Weight.ONE, template = inner) else null)
            } else {
                Lane(name)
            }
        }
    }

    /** Every declared path beneath [prefix], the root's own `[]` first, in written order. */
    private fun declaredPaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        listOf(prefix) + nodes.flatMap { node ->
            val path = prefix + node.name
            if (node is Group) declaredPaths(node.children, path) else listOf(path)
        }

    /**
     * Two attached children under each group with a template of [t]: one named like a declared child of that
     * group when it has one, and one drawn from the pieces.
     */
    private fun runtimePaths(t: Topology, r: Random): List<Pair<List<String>, String>> =
        markedGroups(t.children, emptyList()).flatMap { (path, group) ->
            val named = group.children.map { it.name }
            listOfNotNull(
                named.takeIf { it.isNotEmpty() }?.let { path to it[r.nextInt(it.size)] },
                path to randomName(r),
            ).distinct()
        }

    /**
     * Nested children of the attached children in [attached]: under each one whose group's template
     * carries a template, one named like the attached child itself and one drawn from the pieces,
     * which the tree has; under each one whose group's template carries none, one drawn from the
     * pieces, which it has not.
     */
    private fun nestedPaths(t: Topology, attached: List<Pair<List<String>, String>>, nest: Random): Pair<List<GroupPath.Nested>, List<GroupPath.Nested>> {
        val templates = markedGroups(t.children, emptyList()).associate { (path, group) -> path to group.template }
        val (deep, flat) = attached.partition { (group, _) -> templates[group]?.template != null }
        val resolved = deep.flatMap { (group, outer) -> listOf(outer, randomName(nest)).distinct().map { GroupPath.Nested(GroupPath.Attached(group, outer), it) } }
        val unresolved = flat.map { (group, outer) -> GroupPath.Nested(GroupPath.Attached(group, outer), randomName(nest)) }
        return resolved to unresolved
    }

    /** The id of [path], spelt out from its parent's id rather than by the lookup under test. */
    private fun nestedId(t: Topology, path: GroupPath.Nested): GroupId {
        val parent = path.parent as GroupPath.Attached
        return GroupId(t.attachedChildId(parent.group, parent.name).value + "/!" + escapeSegment(path.name))
    }

    private fun markedGroups(nodes: List<Node>, prefix: List<String>): List<Pair<List<String>, Group>> =
        nodes.filterIsInstance<Group>().flatMap { group ->
            val path = prefix + group.name
            (if (group.template != null) listOf(path to group) else emptyList()) + markedGroups(group.children, path)
        }

    /** Ids one edit away from [id]: a segment dropped or added, the template moved, a byte changed. */
    private fun edits(id: GroupId, r: Random): List<GroupId> {
        val segments = id.value.split('/')
        val last = segments.last()
        val flipped = if (last.startsWith("!")) last.drop(1) else "!$last"
        return listOf(
            segments.dropLast(1).joinToString("/"),
            "${id.value}/${escapeSegment(randomName(r))}",
            "${id.value}/!${escapeSegment(randomName(r))}",
            (segments.dropLast(1) + flipped).joinToString("/"),
            id.value.replaceFirst('%', '!'),
            id.value.lowercase(),
        ).filter { it != id.value }.map { GroupId(it) }
    }
}
