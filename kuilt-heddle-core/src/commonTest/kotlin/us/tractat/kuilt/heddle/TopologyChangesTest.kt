package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.TopologyChange.Added
import us.tractat.kuilt.heddle.TopologyChange.CeilingChanged
import us.tractat.kuilt.heddle.TopologyChange.GroupBecameLane
import us.tractat.kuilt.heddle.TopologyChange.LaneBecameGroup
import us.tractat.kuilt.heddle.TopologyChange.Removed
import us.tractat.kuilt.heddle.TopologyChange.RootRenamed
import us.tractat.kuilt.heddle.TopologyChange.TemplateAdded
import us.tractat.kuilt.heddle.TopologyChange.TemplateChanged
import us.tractat.kuilt.heddle.TopologyChange.TemplateRemoved
import us.tractat.kuilt.heddle.TopologyChange.WeightChanged
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Topology.changesTo], one kind of change at a time. */
class TopologyChangesTest {

    private val base = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive", Weight.of(3, 2))
            lane("batch", maxConcurrent = 2)
        }
        group("tenants") { template(Weight.of(2), maxConcurrent = 1) }
        lane("hobby")
    }

    @Test
    fun identicalTreesHaveNoChanges() {
        val readBack = (Topology.from(base.toDocument()) as TopologyParse.Valid).topology
        assertAll(
            { assertEquals(emptyList(), base.changesTo(base)) },
            { assertEquals(emptyList(), base.changesTo(readBack)) },
        )
    }

    /** Nothing in heddle reads sibling order, and the ledger diff agrees: it lists no step either. */
    @Test
    fun siblingOrderIsNotAChange() {
        val reordered = Topology(
            "pool",
            base.children.reversed().map { if (it is Topology.Group) it.copy(children = it.children.reversed()) else it },
        )
        assertAll(
            { assertTrue(reordered != base, "the rig reorders something") },
            { assertEquals(emptyList(), base.changesTo(reordered)) },
            { assertEquals(emptyList(), Topology.diff(reordered, bootFrom(base), generation = 1)) },
        )
    }

    @Test
    fun anAddedLaneIsAddressedInTheTreeAfter() {
        val after = draft("pool") {
            group("acme", Weight.of(3)) {
                lane("interactive", Weight.of(3, 2))
                lane("nightly")
                lane("batch", maxConcurrent = 2)
            }
            group("tenants") { template(Weight.of(2), maxConcurrent = 1) }
            lane("hobby")
        }
        assertEquals(listOf(Added(listOf(0, 1), listOf("pool", "acme", "nightly"), Lane("nightly"))), base.changesTo(after))
    }

    /** A whole new subtree is one change, carrying the subtree, not one per node. */
    @Test
    fun anAddedGroupIsOneChange() {
        val after = Topology("pool", base.children + draft("x") { group("new") { lane("a"); lane("b") } }.children)
        val added = after.children.last()
        assertEquals(listOf(Added(listOf(3), listOf("pool", "new"), added)), base.changesTo(after))
    }

    /** A removed child is not in the tree after, so it is addressed where it was. */
    @Test
    fun aRemovedChildIsAddressedInTheTreeBefore() {
        val after = Topology("pool", base.children.drop(1))
        assertEquals(listOf(Removed(listOf(0), listOf("pool", "acme"), base.children.first())), base.changesTo(after))
    }

    @Test
    fun aWeightChange() {
        val after = base.edited("acme", "interactive") { (it as Lane).copy(weight = Weight.of(2, 3)) }
        assertEquals(
            listOf(WeightChanged(listOf(0, 0), listOf("pool", "acme", "interactive"), Weight.of(3, 2), Weight.of(2, 3))),
            base.changesTo(after),
        )
    }

    @Test
    fun aCeilingChangeEachWay() {
        val at = listOf(0, 1)
        val names = listOf("pool", "acme", "batch")
        fun to(ceiling: Long?) = base.changesTo(base.edited("acme", "batch") { (it as Lane).copy(maxConcurrent = ceiling) })
        assertAll(
            { assertEquals(listOf(CeilingChanged(at, names, 2L, 5L)), to(5L)) },
            { assertEquals(listOf(CeilingChanged(at, names, 2L, 0L)), to(0L)) },
            { assertEquals(listOf(CeilingChanged(at, names, 2L, null)), to(null)) },
        )
    }

    @Test
    fun templateAddedRemovedAndChanged() {
        val template = ChildTemplate(Weight.of(2), maxConcurrent = 1)
        val withTemplate = base.edited("acme") { (it as Topology.Group).copy(template = ChildTemplate(Weight.ONE)) }
        // A group with a template may declare no children, so removing `tenants`' template means giving it one.
        val withoutTemplate = base.edited("tenants") { (it as Topology.Group).copy(children = listOf(Lane("x")), template = null) }
        val changed = base.edited("tenants") { (it as Topology.Group).copy(template = ChildTemplate(Weight.of(2), maxConcurrent = 3)) }
        assertAll(
            { assertEquals(listOf(TemplateAdded(listOf(0), listOf("pool", "acme"), ChildTemplate(Weight.ONE))), base.changesTo(withTemplate)) },
            {
                assertEquals(
                    listOf(
                        TemplateRemoved(listOf(1), listOf("pool", "tenants"), template),
                        Added(listOf(1, 0), listOf("pool", "tenants", "x"), Lane("x")),
                    ),
                    base.changesTo(withoutTemplate),
                )
            },
            {
                assertEquals(
                    listOf(TemplateChanged(listOf(1), listOf("pool", "tenants"), template, ChildTemplate(Weight.of(2), maxConcurrent = 3))),
                    base.changesTo(changed),
                )
            },
        )
    }

    /** A change of kind keeps the child: one change of kind, then what it gained or lost beneath it. */
    @Test
    fun aLaneBecomesAGroupAndBack() {
        val grown = base.edited("hobby") { Topology.Group("hobby", Weight.ONE, listOf(Lane("chess")), ChildTemplate(Weight.ONE)) }
        assertAll(
            {
                assertEquals(
                    listOf(
                        LaneBecameGroup(listOf(2), listOf("pool", "hobby")),
                        TemplateAdded(listOf(2), listOf("pool", "hobby"), ChildTemplate(Weight.ONE)),
                        Added(listOf(2, 0), listOf("pool", "hobby", "chess"), Lane("chess")),
                    ),
                    base.changesTo(grown),
                )
            },
            {
                assertEquals(
                    listOf(
                        GroupBecameLane(listOf(2), listOf("pool", "hobby")),
                        TemplateRemoved(listOf(2), listOf("pool", "hobby"), ChildTemplate(Weight.ONE)),
                        Removed(listOf(2, 0), listOf("pool", "hobby", "chess"), Lane("chess")),
                    ),
                    grown.changesTo(base),
                )
            },
        )
    }

    /**
     * A group is identified by its names from the root down, so moving `batch` from `acme` to
     * `hobby` leaves the old child behind with its history and starts a new one: the list says so,
     * rather than presenting a move as free.
     */
    @Test
    fun aMoveIsARemovalAndAnAddition() {
        val after = draft("pool") {
            group("acme", Weight.of(3)) { lane("interactive", Weight.of(3, 2)) }
            group("tenants") { template(Weight.of(2), maxConcurrent = 1) }
            group("hobby") { lane("batch", maxConcurrent = 2) }
        }
        assertEquals(
            listOf(
                Removed(listOf(0, 1), listOf("pool", "acme", "batch"), Lane("batch", maxConcurrent = 2)),
                LaneBecameGroup(listOf(2), listOf("pool", "hobby")),
                Added(listOf(2, 0), listOf("pool", "hobby", "batch"), Lane("batch", maxConcurrent = 2)),
            ),
            base.changesTo(after),
        )
    }

    @Test
    fun aRenameIsARemovalAndAnAddition() {
        val after = base.edited("hobby") { Lane("games") }
        assertEquals(
            listOf(Added(listOf(2), listOf("pool", "games"), Lane("games")), Removed(listOf(2), listOf("pool", "hobby"), Lane("hobby"))),
            base.changesTo(after),
        )
    }

    /** Every name below a renamed root changes, so nothing below it is the same child. */
    @Test
    fun aRenamedRootReplacesEveryChild() {
        val after = Topology("shared", base.children)
        val changes = base.changesTo(after)
        assertAll(
            { assertEquals(RootRenamed("pool", "shared"), changes.first()) },
            { assertEquals(base.children.indices.map { Added(listOf(it), listOf("shared", base.children[it].name), base.children[it]) }, changes.subList(1, 4)) },
            { assertEquals(base.children.indices.map { Removed(listOf(it), listOf("pool", base.children[it].name), base.children[it]) }, changes.subList(4, 7)) },
            { assertEquals(7, changes.size) },
        )
    }

    /** A change on a kept child is addressed in the tree after: an insertion ahead of it shifts its index. */
    @Test
    fun aKeptChildIsAddressedInTheTreeAfter() {
        val after = Topology("pool", listOf(Lane("first")) + base.edited("hobby") { Lane("hobby", Weight.of(5)) }.children)
        assertEquals(
            listOf(
                Added(listOf(0), listOf("pool", "first"), Lane("first")),
                WeightChanged(listOf(3), listOf("pool", "hobby"), Weight.ONE, Weight.of(5)),
            ),
            base.changesTo(after),
        )
    }

    /** A node's own changes, then its children's, children in the order written, removals last. */
    @Test
    fun changesComeInDocumentOrder() {
        val after = draft("pool") {
            group("acme", Weight.of(4), maxConcurrent = 9) {
                lane("batch", maxConcurrent = 3)
                lane("added")
            }
            group("tenants") { template(Weight.of(2), maxConcurrent = 1) }
            lane("hobby", Weight.of(2))
        }
        assertEquals(
            listOf(
                WeightChanged(listOf(0), listOf("pool", "acme"), Weight.of(3), Weight.of(4)),
                CeilingChanged(listOf(0), listOf("pool", "acme"), null, 9L),
                CeilingChanged(listOf(0, 0), listOf("pool", "acme", "batch"), 2L, 3L),
                Added(listOf(0, 1), listOf("pool", "acme", "added"), Lane("added")),
                Removed(listOf(0, 0), listOf("pool", "acme", "interactive"), Lane("interactive", Weight.of(3, 2))),
                WeightChanged(listOf(2), listOf("pool", "hobby"), Weight.ONE, Weight.of(2)),
            ),
            base.changesTo(after),
        )
    }

    /** A change that is no change, or an address that is no address, cannot be built. */
    @Test
    fun aChangeCannotBeBuiltEmpty() {
        val names = listOf("pool", "acme")
        assertAll(
            { assertFailsWith<IllegalArgumentException> { WeightChanged(listOf(0), names, Weight.ONE, Weight.ONE) } },
            { assertFailsWith<IllegalArgumentException> { CeilingChanged(listOf(0), names, 2L, 2L) } },
            { assertFailsWith<IllegalArgumentException> { CeilingChanged(listOf(0), names, null, -1L) } },
            { assertFailsWith<IllegalArgumentException> { TemplateChanged(listOf(0), names, ChildTemplate(Weight.ONE), ChildTemplate(Weight.ONE)) } },
            { assertFailsWith<IllegalArgumentException> { RootRenamed("pool", "pool") } },
            { assertFailsWith<IllegalArgumentException> { Added(emptyList(), listOf("pool"), Lane("pool")) } },
            { assertFailsWith<IllegalArgumentException> { Added(listOf(-1), names, Lane("acme")) } },
            { assertFailsWith<IllegalArgumentException> { Added(listOf(0), listOf("acme"), Lane("acme")) } },
            { assertFailsWith<IllegalArgumentException> { Added(listOf(0), names, Lane("other")) } },
            { assertFailsWith<IllegalArgumentException> { LaneBecameGroup(listOf(0), listOf("pool", "")) } },
        )
    }

    /** This tree with the node at [path] (names from just below the root) replaced by [edit]'s result. */
    private fun Topology.edited(vararg path: String, edit: (Topology.Node) -> Topology.Node): Topology =
        Topology(root, children.edited(path.asList(), edit))

    private fun List<Topology.Node>.edited(path: List<String>, edit: (Topology.Node) -> Topology.Node): List<Topology.Node> {
        check(any { it.name == path.first() }) { "no child ${path.first()}" }
        return map { node ->
            when {
                node.name != path.first() -> node
                path.size == 1 -> edit(node)
                else -> (node as Topology.Group).copy(children = node.children.edited(path.drop(1), edit))
            }
        }
    }
}
