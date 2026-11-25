package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.TopologyDocument.Group
import us.tractat.kuilt.heddle.TopologyDocument.Lane
import us.tractat.kuilt.heddle.TopologyDocument.ChildTemplate as Template
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
    fun aTemplateIsReplacedWholeAndNullRemovesIt() {
        val nested = Template("2", 4L, Template("1"))
        val added = assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(1), nested))
        val flat = assertIs<TopologyEdit.Edited>(added.document.setTemplate(listOf(1), Template("2", 4L)))
        val removed = assertIs<TopologyEdit.Edited>(added.document.setTemplate(listOf(1), null))
        val expected = TopologyDocument("pool", listOf(doc.children[0], Group("hobby", children = listOf(Lane("batch")), template = nested)))
        assertAll(
            { assertEquals(TopologyEdit.Edited(expected, listOf(1), TopologyEdit.History.KEPT), added) },
            // Passing a template without its nested one removes the nested one: the whole template is replaced.
            { assertEquals(Template("2", 4L), (flat.document.children[1] as Group).template) },
            { assertEquals(TopologyEdit.History.DRAINS, flat.history) },
            { assertEquals(TopologyEdit.Edited(doc, listOf(1), TopologyEdit.History.DRAINS), removed) },
        )
    }

    @Test
    fun aTemplateEditReadsWhatItDoesToTheAttachedChildren() {
        val before = Template("3/2", 1L, Template("1"))
        val start = assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(1), before)).document
        fun historyOf(after: Template?) = assertIs<TopologyEdit.Edited>(start.setTemplate(listOf(1), after)).history
        assertAll(
            { assertEquals(TopologyEdit.History.KEPT, historyOf(before), "the same template") },
            { assertEquals(TopologyEdit.History.KEPT, historyOf(Template("6/4", 1L, Template("1"))), "an equal ratio") },
            { assertEquals(TopologyEdit.History.KEPT, historyOf(Template("3/2", 5L, Template("1", 2L))), "ceilings at both levels") },
            { assertEquals(TopologyEdit.History.KEPT, historyOf(Template("3/2", 1L, Template("1", template = Template()))), "a level added") },
            { assertEquals(TopologyEdit.History.SHARE_RESTARTS, historyOf(Template("2", 1L, Template("1"))), "the attached weight") },
            { assertEquals(TopologyEdit.History.SHARE_RESTARTS, historyOf(Template("3/2", 1L, Template("2"))), "the nested weight") },
            { assertEquals(TopologyEdit.History.DRAINS, historyOf(Template("2", 1L)), "a new weight and a level gone: the drain wins") },
            { assertEquals(TopologyEdit.History.DRAINS, historyOf(null), "the template gone") },
            { assertEquals(TopologyEdit.History.KEPT, assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(0), null)).history, "none, and none again") },
        )
    }

    @Test
    fun aNestedTemplateRoundTripsThroughTopology() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(0), Template("2", 3L, Template("3/2", template = Template()))))
        val read = assertIs<TopologyParse.Valid>(Topology.from(edited.document))
        val expected = Topology.ChildTemplate(Weight.of(2), 3L, Topology.ChildTemplate(Weight.of(3, 2), template = Topology.ChildTemplate(Weight.ONE)))
        assertAll(
            { assertEquals(expected, (read.topology.children[0] as Topology.Group).template) },
            { assertEquals(edited.document, read.topology.toDocument()) },
        )
    }

    @Test
    fun aTemplateIsRefusedAtTheRootOnALaneAndWhereNothingIs() {
        assertAll(
            // A document's root has no template to set: TopologyDocument holds only its name and children.
            { assertEquals(TopologyEdit.AtRoot, doc.setTemplate(emptyList(), Template())) },
            { assertEquals(TopologyEdit.NotAGroup(listOf(0, 0)), doc.setTemplate(listOf(0, 0), Template())) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(0, 0, 0)), doc.setTemplate(listOf(0, 0, 0), Template())) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(2)), doc.setTemplate(listOf(2), null)) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(-1)), doc.setTemplate(listOf(-1), null)) },
        )
    }

    @Test
    fun aTemplateIsCheckedForShapeNotValues() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(1), Template("three", -1L)))
        val problems = (Topology.from(edited.document) as TopologyParse.Invalid).problems
        assertAll(
            { assertEquals(setOf(TopologyProblem.Kind.INVALID_WEIGHT, TopologyProblem.Kind.NEGATIVE_MAX_CONCURRENT), problems.map { it.kind }.toSet()) },
            { assertEquals(setOf(listOf(1)), problems.map { it.path }.toSet()) },
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
