package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.TopologyStep.Activate
import us.tractat.kuilt.heddle.TopologyStep.Close
import us.tractat.kuilt.heddle.TopologyStep.Prepare
import us.tractat.kuilt.heddle.TopologyStep.Retire
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A template that carries a template (#3314): everything below an attached child is attached. The
 * consumer's shape is `kind → repository → author`, with repositories and authors both discovered
 * while running: `ci` is declared, each repository is attached under it, and each author is
 * attached under its repository.
 */
class TopologyNestedTemplateTest {

    private val authors = ChildTemplate(Weight.of(3, 2), maxConcurrent = 1L)
    private val repos = ChildTemplate(Weight.of(2), template = authors)

    /** `root → ci {manual, template(repos → authors)}`. */
    private val t = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = repos)))

    private val kuilt = GroupPath.Attached(listOf("ci"), "kuilt")
    private val alice = GroupPath.Nested(kuilt, "alice")
    private val bob = GroupPath.Nested(kuilt, "bob")

    private val kuiltId = GroupId("root/ci/!kuilt")
    private val aliceId = GroupId("root/ci/!kuilt/!alice")
    private val bobId = GroupId("root/ci/!kuilt/!bob")

    private fun applyClean(steps: List<TopologyStep>, ledger: EntitlementLedger): EntitlementLedger {
        val applied = applyAll(steps, ledger)
        assertEquals(emptyList(), applied.refused, "refused applying $steps")
        return applied.ledger
    }

    // ── the grammar ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNestedIdNamesEveryAttachedSegmentAndReadsBack() {
        assertAll(
            { assertEquals(kuiltId, t.findGroupId(kuilt), "a repository prints as it did in v0.7.6") },
            { assertEquals(aliceId, t.findGroupId(alice)) },
            { assertEquals(alice, t.findPath(aliceId)) },
            { assertEquals(kuilt, t.findPath(kuiltId)) },
            { assertEquals(listOf("ci", "kuilt", "alice"), alice.names) },
            { assertEquals(ParsedGroupId("root", alice), pathOf(aliceId)) },
            {
                val deep = GroupPath.Nested(alice, "x")
                assertEquals(ParsedGroupId("root", deep), pathOf(GroupId("root/ci/!kuilt/!alice/!x")), "the grammar reads any depth")
            },
            { assertNull(t.findGroupId(GroupPath.Nested(alice, "x")), "the author template carries no template") },
            { assertNull(t.findPath(GroupId("root/ci/!kuilt/!alice/!x"))) },
            // A declared segment never follows an attached one, and `!` never starts at depth one.
            *listOf("root/ci/!kuilt/alice", "root/!ci/!kuilt", "root/ci/!kuilt/alice/!x", "root/ci/!kuilt/!", "root/ci/!kuilt//!x")
                .map { text -> { assertNull(pathOf(GroupId(text)), "pathOf(\"$text\")") } }.toTypedArray(),
        )
    }

    @Test
    fun aNestedPathHasAnAttachedParent() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { GroupPath.Nested(GroupPath.Declared(listOf("ci")), "kuilt") } },
            { assertFailsWith<IllegalArgumentException> { GroupPath.Nested(GroupPath.Declared(emptyList()), "kuilt") } },
            { assertFailsWith<IllegalArgumentException> { GroupPath.Nested(kuilt, "") } },
            { assertEquals(GroupPath.Nested(GroupPath.Nested(kuilt, "a"), "b").names, listOf("ci", "kuilt", "a", "b")) },
        )
    }

    // ── the steps ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun attachingAnAuthorAttachesItsRepositoryInTheSameList() {
        val ledger = bootFrom(t)
        val steps = t.attach(alice, ledger, generation = 0L)
        val after = applyClean(steps, ledger)
        val sibling = t.attach(bob, after, generation = 0L)
        assertAll(
            {
                assertEquals(
                    listOf(Prepare::class, Prepare::class, Activate::class, Activate::class) to listOf(kuiltId, aliceId, kuiltId, aliceId),
                    steps.map { it::class } to steps.map { it.child },
                    "prepares top-down, then activations top-down",
                )
            },
            { assertEquals(listOf(Weight.of(2), Weight.of(3, 2)), steps.filterIsInstance<Prepare>().map { it.record.weight }, "a weight per template") },
            { assertTrue(after.lineageOf(aliceId) != null && after.lineageOf(kuiltId) != null) },
            { assertEquals(listOf(bobId, bobId), sibling.map { it.child }, "the repository is attached already") },
            { assertEquals(emptyList(), t.attach(alice, after, generation = 0L), "asking again is harmless") },
            { assertEquals(emptyList(), Topology.diff(t, after, generation = 0L), "a reload keeps both") },
            { assertEquals(t.attach(listOf("ci"), "kuilt", ledger, 0L), t.attach(kuilt, ledger, 0L), "the old call is the one-level path") },
        )
    }

    @Test
    fun detachingARepositoryClosesItsAuthorsAndRetiresThemFirst() {
        var ledger = bootFrom(t)
        ledger = applyClean(t.attach(alice, ledger, generation = 0L), ledger)
        ledger = applyClean(t.attach(bob, ledger, generation = 0L), ledger)
        val steps = t.detach(kuilt, ledger)
        val after = applyClean(steps, ledger)
        val back = applyClean(t.attach(kuilt, after, generation = 0L), after)
        assertAll(
            {
                assertEquals(
                    listOf(Close::class to kuiltId, Close::class to aliceId, Close::class to bobId, Retire::class to aliceId, Retire::class to bobId, Retire::class to kuiltId),
                    steps.map { it::class to it.child },
                    "closes top-down, retires bottom-up",
                )
            },
            { assertTrue(listOf(kuiltId, aliceId, bobId).all { after.lineageOf(it) == null }, "the whole subtree is gone") },
            { assertTrue(back.lineageOf(kuiltId) != null, "the repository comes back") },
            { assertTrue(back.lineageOf(aliceId) == null && back.lineageOf(bobId) == null, "and brings no author back") },
            { assertEquals(emptyList(), Topology.diff(t, back, generation = 0L), "nor does a reload") },
            { assertEquals(listOf(Close::class to aliceId, Retire::class to aliceId), t.detach(alice, applyClean(t.attach(alice, back, 0L), back)).map { it::class to it.child }, "a leaf detaches alone") },
        )
    }

    /**
     * A detach that landed only in part, its repository's close and nothing else, as an interpreter
     * that stopped part-way leaves it: the author is still attached beneath a repository nothing
     * wants. The next diff closes her too, because a nested child is wanted only while its parent
     * is. Without that rule the diff keeps her, live beneath a closed edge.
     */
    @Test
    fun aDiffAfterAPartialDetachClosesWhatIsLeftBeneath() {
        var ledger = bootFrom(t)
        ledger = applyClean(t.attach(alice, ledger, generation = 0L), ledger)
        val detach = t.detach(kuilt, ledger)
        val prefix = applyClean(detach.take(1), ledger)
        val rest = Topology.diff(t, prefix, generation = 1L)
        val after = applyClean(rest, prefix)
        assertAll(
            { assertEquals(Close::class to kuiltId, detach.first().let { it::class to it.child }, "the rig: the prefix is the repository's close") },
            { assertTrue(prefix.liveInboundEdges(aliceId).isNotEmpty(), "the rig: alice is still attached after the prefix") },
            { assertTrue(Close::class to aliceId in rest.map { it::class to it.child }, "the diff closes alice: $rest") },
            // Her lineage is gone either way once the repository retires; her own edge is what tells.
            { assertEquals(emptyList(), after.liveInboundEdges(aliceId), "alice's own edge is not left live beneath a retired repository") },
            { assertTrue(after.lineageOf(aliceId) == null && after.lineageOf(kuiltId) == null, "and nothing beneath the repository is left") },
        )
    }

    @Test
    fun aReloadThatDropsTheNestedTemplateClosesTheAuthorsAndKeepsTheRepositories() {
        var ledger = bootFrom(t)
        ledger = applyClean(t.attach(alice, ledger, generation = 0L), ledger)
        val flat = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = repos.copy(template = null))))
        val steps = Topology.diff(flat, ledger, generation = 1L)
        val after = applyClean(steps, ledger)
        val gone = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")))))
        val none = applyClean(Topology.diff(gone, ledger, generation = 1L), ledger)
        assertAll(
            { assertEquals(setOf(aliceId), steps.map { it.child }.toSet(), "only the author moves") },
            { assertTrue(after.lineageOf(kuiltId) != null && after.lineageOf(aliceId) == null) },
            { assertTrue(none.lineageOf(kuiltId) == null && none.lineageOf(aliceId) == null, "a template going takes everything below it") },
        )
    }

    @Test
    fun attachAndDetachRefuseAPathNoTemplateReaches() {
        val ledger = applyClean(t.attach(alice, bootFrom(t), generation = 0L), bootFrom(t))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { t.attach(GroupPath.Declared(listOf("ci", "manual")), ledger, 0L) } },
            { assertFailsWith<IllegalArgumentException> { t.attach(GroupPath.Nested(alice, "x"), ledger, 0L) } },
            { assertFailsWith<IllegalArgumentException> { t.attach(GroupPath.Attached(listOf("ci", "manual"), "x"), ledger, 0L) } },
            { assertFailsWith<IllegalArgumentException> { t.detach(GroupPath.Declared(listOf("ci")), ledger) } },
            { assertFailsWith<IllegalArgumentException> { t.detach(GroupPath.Nested(alice, "x"), ledger) } },
            { assertFailsWith<IllegalArgumentException> { t.attach(alice, ledger, -1L) } },
        )
    }

    // ── the document ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aDocumentDeclaresANestedTemplate() {
        val text = """{"root":"root","children":[{"type":"group","name":"ci","template":{"weight":"2","template":{"weight":"3/2","maxConcurrent":1}}}]}"""
        val document = Json.decodeFromString(TopologyDocument.serializer(), text)
        val parsed = assertIs<TopologyParse.Valid>(Topology.from(document)).topology
        val ci = assertIs<Group>(parsed.children.single())
        assertAll(
            { assertEquals(repos, ci.template) },
            { assertEquals(document, parsed.toDocument(), "the round trip") },
            { assertEquals(Topology.from(parsed.toDocument()), TopologyParse.Valid(parsed), "toDocument and from are inverses") },
            {
                val encoded = Json.encodeToString(Topology.serializer(), parsed)
                assertTrue("\"template\":{\"weight\":\"2\",\"template\":{" in encoded, "the nested template is stored: $encoded")
            },
        )
    }

    @Test
    fun aTemplateWithoutATemplateEncodesAsBefore() {
        val flat = Topology("root", listOf(Group("tenants", Weight.ONE, emptyList(), ChildTemplate(Weight.ONE, maxConcurrent = 2L))))
        val stored = """{"root":"root","children":[{"type":"group","name":"tenants","weight":"1","children":[],"template":{"weight":"1","maxConcurrent":2}}]}"""
        assertAll(
            { assertEquals(stored, Json.encodeToString(Topology.serializer(), flat)) },
            { assertEquals(flat, Json.decodeFromString(Topology.serializer(), stored)) },
        )
    }

    @Test
    fun aNestedTemplatesProblemsAreReportedAtItsGroup() {
        val document = TopologyDocument(
            "root",
            listOf(
                TopologyDocument.Group(
                    "ci",
                    template = TopologyDocument.ChildTemplate("2", template = TopologyDocument.ChildTemplate("x", maxConcurrent = -1L)),
                ),
            ),
        )
        val problems = assertIs<TopologyParse.Invalid>(Topology.from(document)).problems
        assertAll(
            { assertEquals(listOf(TopologyProblem.Kind.INVALID_WEIGHT, TopologyProblem.Kind.NEGATIVE_MAX_CONCURRENT), problems.map { it.kind }) },
            { assertEquals(listOf(listOf(0), listOf(0)), problems.map { it.path }, "addressed at the group") },
            { assertTrue(problems.all { "nested template" in it.detail }, "and named as the nested one: ${problems.map { it.detail }}") },
            { assertFailsWith<TopologyException> { Topology("root", listOf(Group("ci", Weight.ONE, emptyList(), ChildTemplate(Weight.ONE, template = ChildTemplate(Weight.ONE, maxConcurrent = -1L))))) } },
        )
    }

    // ── what a change costs ─────────────────────────────────────────────────────────────────────

    @Test
    fun aNestedTemplatesWeightIsHistoryAndItsArrivalIsNot() {
        fun with(template: ChildTemplate?) = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = template)))
        fun history(before: ChildTemplate, after: ChildTemplate) =
            assertIs<TopologyChange.TemplateChanged>(with(before).changesTo(with(after)).single()).history
        assertAll(
            { assertEquals(TopologyChange.SaveEffect.LOST, history(repos, repos.copy(template = authors.copy(weight = Weight.of(5)))), "an author weight restarts every author") },
            { assertEquals(TopologyChange.SaveEffect.LOST, history(repos, repos.copy(template = null)), "dropping it closes every author") },
            { assertEquals(TopologyChange.SaveEffect.KEPT, history(repos.copy(template = null), repos), "adding it keeps every repository") },
            { assertEquals(TopologyChange.SaveEffect.KEPT, history(repos, repos.copy(template = authors.copy(maxConcurrent = 4L))), "a ceiling costs nothing") },
            { assertEquals(TopologyChange.SaveEffect.LOST, history(repos, repos.copy(weight = Weight.of(7))), "a repository weight, as before") },
        )
    }
}
