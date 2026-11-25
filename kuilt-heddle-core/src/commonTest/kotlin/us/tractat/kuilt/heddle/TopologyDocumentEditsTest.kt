package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.TopologyDocument.Group
import us.tractat.kuilt.heddle.TopologyDocument.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The edits on [TopologyDocument], one case at a time. The seeded comparison against an
 * independent model, with every arm counted, is [TopologyDocumentEditsPropertyTest].
 */
class TopologyDocumentEditsTest {

    private val doc = TopologyDocument(
        "pool",
        listOf(
            Group("acme", "3", listOf(Lane("interactive", "3/2"), Lane("batch"))),
            Group("hobby", children = listOf(Lane("batch"))),
        ),
    )

    @Test
    fun aWeightThreeLevelsDownChangesThatNodeAlone() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setWeight(listOf(0, 1), "2"))
        val expected = TopologyDocument(
            "pool",
            listOf(
                Group("acme", "3", listOf(Lane("interactive", "3/2"), Lane("batch", "2"))),
                Group("hobby", children = listOf(Lane("batch"))),
            ),
        )
        assertEquals(TopologyEdit.Edited(expected, listOf(0, 1), TopologyEdit.History.SHARE_RESTARTS), edited)
    }

    @Test
    fun anEqualRatioKeepsTheShare() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setWeight(listOf(0, 0), "6/4"))
        assertEquals(TopologyEdit.History.KEPT, edited.history)
    }

    @Test
    fun aCeilingIsSetAndCleared() {
        val set = assertIs<TopologyEdit.Edited>(doc.setMaxConcurrent(listOf(1), 0L))
        val cleared = assertIs<TopologyEdit.Edited>(set.document.setMaxConcurrent(listOf(1), null))
        assertAll(
            { assertEquals(0L, set.document.children[1].maxConcurrent) },
            { assertEquals(TopologyEdit.History.KEPT, set.history) },
            { assertEquals(doc, cleared.document) },
        )
    }

    @Test
    fun anAddedChildGoesLastAndNamesItsPath() {
        val edited = assertIs<TopologyEdit.Edited>(doc.addChild(listOf(1), Lane("nightly")))
        assertAll(
            { assertEquals(listOf(1, 1), edited.path) },
            { assertEquals(Lane("nightly"), (edited.document.children[1] as Group).children[1]) },
            { assertEquals(TopologyEdit.History.STARTS_EMPTY, edited.history) },
        )
    }

    @Test
    fun aMoveSaysWhereTheChildLandedAfterTheSiblingsShift() {
        // `hobby` is [1] before the move and [0] after it, since `acme` leaves the root.
        val doc = TopologyDocument("pool", listOf(Group("acme", children = listOf(Lane("a"))), Group("hobby", children = listOf(Lane("b")))))
        val edited = assertIs<TopologyEdit.Edited>(doc.move(listOf(0), parent = listOf(1)))
        assertAll(
            { assertEquals(listOf(0, 1), edited.path) },
            { assertEquals(TopologyDocument("pool", listOf(Group("hobby", children = listOf(Lane("b"), Group("acme", children = listOf(Lane("a"))))))), edited.document) },
            { assertEquals(TopologyEdit.History.REPLACED, edited.history) },
        )
    }

    @Test
    fun aRenameIsADeletePlusAnAdd() {
        val renamed = assertIs<TopologyEdit.Edited>(doc.rename(listOf(0), "acme-corp"))
        val root = assertIs<TopologyEdit.Edited>(doc.rename(emptyList(), "farm"))
        assertAll(
            { assertEquals(TopologyEdit.History.REPLACED, renamed.history) },
            { assertEquals(TopologyEdit.History.NEW_TREE, root.history) },
            { assertEquals("farm", root.document.root) },
        )
    }

    @Test
    fun eachRefusalNamesThePathToBlame() {
        assertAll(
            { assertEquals(TopologyEdit.NoSuchNode(listOf(0, 7)), doc.setWeight(listOf(0, 7), "2")) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(0, 0, 0)), doc.remove(listOf(0, 0, 0))) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(-1)), doc.rename(listOf(-1), "x")) },
            { assertEquals(TopologyEdit.NotAGroup(listOf(0, 0)), doc.addChild(listOf(0, 0), Lane("x"))) },
            { assertEquals(TopologyEdit.DuplicateName(listOf(0, 1), "batch"), doc.addChild(listOf(0), Lane("batch"))) },
            { assertEquals(TopologyEdit.DuplicateName(listOf(0, 1), "batch"), doc.rename(listOf(0, 0), "batch")) },
            { assertEquals(TopologyEdit.DuplicateName(listOf(1, 0), "batch"), doc.move(listOf(0, 1), parent = listOf(1))) },
            { assertEquals(TopologyEdit.AtRoot, doc.setMaxConcurrent(emptyList(), 1L)) },
            { assertEquals(TopologyEdit.AtRoot, doc.move(emptyList(), parent = listOf(0))) },
            { assertEquals(TopologyEdit.IntoItself(listOf(0), listOf(0)), doc.move(listOf(0), parent = listOf(0))) },
            { assertEquals(TopologyEdit.IntoItself(listOf(0), listOf(0, 1)), doc.move(listOf(0), parent = listOf(0, 1))) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(0, 9)), doc.move(listOf(0), parent = listOf(0, 9))) },
        )
    }

    @Test
    fun aDocumentWithTwoChildrenOfOneNameIsStillEditedByIndex() {
        // Mid-edit, before Topology.from has been asked: the second `batch` is the one to fix.
        val doc = TopologyDocument("pool", listOf(Lane("batch"), Lane("batch")))
        val problem = (Topology.from(doc) as TopologyParse.Invalid).problems.single()
        val fixed = assertIs<TopologyEdit.Edited>(doc.rename(problem.path, "nightly"))
        assertAll(
            { assertEquals(TopologyProblem.Kind.DUPLICATE_NAME, problem.kind) },
            { assertEquals(TopologyDocument("pool", listOf(Lane("batch"), Lane("nightly"))), fixed.document) },
            { assertIs<TopologyParse.Valid>(Topology.from(fixed.document)) },
        )
    }

    @Test
    fun anEditChecksShapeNotValues() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setWeight(listOf(1), "three"))
        val problem = (Topology.from(edited.document) as TopologyParse.Invalid).problems.single()
        assertAll(
            { assertEquals(TopologyProblem.Kind.INVALID_WEIGHT, problem.kind) },
            { assertEquals(listOf(1), problem.path) },
        )
    }
}
