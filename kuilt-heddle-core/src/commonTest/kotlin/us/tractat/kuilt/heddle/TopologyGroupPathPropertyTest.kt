package us.tractat.kuilt.heddle

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
 * [Topology.findGroupId] and [Topology.findPath] are exact inverses, over seeded random trees whose
 * names are built from the awkward pieces [TopologyLoweringTest.flatteningIsInjectiveOverAwkwardNames]
 * pins: a slash, a pre-escaped slash, the runtime marker, the id grammar's punctuation, a non-ASCII
 * letter, both lone surrogates and an emoji. Joining one or two pieces makes names that a naive
 * encoding would fold together, such as `a/b` beside a group `a` holding `b`.
 *
 * Each tree is checked against every path it has a group for, runtime children included, under
 * names drawn from the same pieces so that they collide with declared siblings; and against paths
 * and ids it has no group for, taken from a second tree with the same root and from edits of real
 * ids. The two laws, for every path `p` and id `i` tried:
 *
 * - `findGroupId(p)` is `null` or an id `i` with `findPath(i) == p`;
 * - `findPath(i)` is `null` or a path `p` with `findGroupId(p) == i`.
 *
 * and every path the tree declares or can attach resolves, to the id [Topology.groupId] or
 * [Topology.runtimeChildId] gives it.
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
            val root = if (r.nextBoolean()) "root" else randomName(r)
            val t = randomTree(r, root)
            val other = randomTree(r, root)

            val resolvable = declaredPaths(t.children, emptyList()).map { GroupPath.Declared(it) to t.groupId(*it.toTypedArray()) } +
                runtimePaths(t, r).map { GroupPath.RuntimeChild(it.first, it.second) to t.runtimeChildId(it.first, it.second) }
            for ((path, id) in resolvable) {
                if (t.findGroupId(path) != id) failures += "seed $seed: findGroupId($path) = ${t.findGroupId(path)}, groupId gives ${id.value}"
                if (t.findPath(id) != path) failures += "seed $seed: findPath(${id.value}) = ${t.findPath(id)}, expected $path"
                tally.resolved(path)
            }
            val ids = resolvable.map { it.second }
            if (ids.toSet().size != ids.size) failures += "seed $seed: ids collide ${ids.map { it.value }}"
            tally.collisions += resolvable.count { (path, _) ->
                path is GroupPath.RuntimeChild && GroupPath.Declared(path.names) in resolvable.map { it.first }
            }

            // Paths and ids from a sibling tree, and edits of real ids: most are not this tree's.
            val foreignPaths = declaredPaths(other.children, emptyList()).map { GroupPath.Declared(it) } +
                runtimePaths(other, r).map { GroupPath.RuntimeChild(it.first, it.second) }
            for (path in foreignPaths) {
                val id = t.findGroupId(path)
                if (id == null) tally.unresolvedPaths++ else if (t.findPath(id) != path) failures += "seed $seed: findPath(findGroupId($path)) = ${t.findPath(id)}"
            }
            val foreignIds = foreignPaths.mapNotNull { other.findGroupId(it) } + ids.flatMap { edits(it, r) }
            for (id in foreignIds) {
                val path = t.findPath(id)
                if (path == null) tally.unresolvedIds++ else if (t.findGroupId(path) != id) failures += "seed $seed: findGroupId(findPath(${id.value})) = ${t.findGroupId(path)}"
            }
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures) },
            // Floors set below the counts measured on every target, so a drifting generator reds.
            { assertTrue(tally.declared >= 2_000, "declared ${tally.declared}") },
            { assertTrue(tally.runtime >= 500, "runtime ${tally.runtime}") },
            { assertTrue(tally.collisions >= 50, "runtime children named like a declared sibling ${tally.collisions}") },
            { assertTrue(tally.unresolvedPaths >= 500, "unresolved paths ${tally.unresolvedPaths}") },
            { assertTrue(tally.unresolvedIds >= 2_000, "unresolved ids ${tally.unresolvedIds}") },
        )
    }

    private class Tally {
        var declared = 0
        var runtime = 0
        var collisions = 0
        var unresolvedPaths = 0
        var unresolvedIds = 0

        fun resolved(path: GroupPath) {
            when (path) {
                is GroupPath.Declared -> declared++
                is GroupPath.RuntimeChild -> runtime++
            }
        }
    }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun randomName(r: Random): String =
        (1..1 + r.nextInt(2)).joinToString("") { pieces[r.nextInt(pieces.size)] }

    /** Depth up to 3, up to 4 children per group, names unique among siblings; a third of the groups marked. */
    private fun randomTree(r: Random, root: String): Topology = Topology(root, randomChildren(r, depth = 0))

    private fun randomChildren(r: Random, depth: Int): List<Node> {
        val names = ArrayList<String>()
        repeat(1 + r.nextInt(4)) {
            val name = randomName(r)
            if (name !in names) names += name
        }
        return names.map { name ->
            if (depth < 2 && r.nextInt(2) == 0) {
                val marked = r.nextInt(3) == 0
                Group(name, Weight.ONE, randomChildren(r, depth + 1), if (marked) RuntimeChildren(Weight.ONE) else null)
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
     * Two runtime children under each marked group of [t]: one named like a declared child of that
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

    private fun markedGroups(nodes: List<Node>, prefix: List<String>): List<Pair<List<String>, Group>> =
        nodes.filterIsInstance<Group>().flatMap { group ->
            val path = prefix + group.name
            (if (group.runtimeChildren != null) listOf(path to group) else emptyList()) + markedGroups(group.children, path)
        }

    /** Ids one edit away from [id]: a segment dropped or added, the runtime marker moved, a byte changed. */
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
