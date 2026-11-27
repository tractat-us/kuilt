package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.TopologyDocument.Group
import us.tractat.kuilt.heddle.TopologyDocument.Lane
import us.tractat.kuilt.heddle.TopologyDocument.ChildTemplate as Template
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * An app's own settings on a [TopologyDocument], one case at a time: the encoding, the edits, the
 * change report, and the line where [Topology] stops carrying them. The seeded sequences of edits,
 * with every arm counted, are [TopologyDocumentAppSettingsPropertyTest].
 */
class TopologyDocumentAppSettingsTest {

    private val match = mapOf("match" to "repo:*")

    private val doc = TopologyDocument(
        "pool",
        listOf(
            Group(
                "ci",
                "3",
                listOf(Lane("main", appSettings = mapOf("match" to "branch:main")), Lane("pr")),
                Template("2", appSettings = mapOf("split" to "repository"), template = Template(appSettings = mapOf("split" to "author"))),
                appSettings = match,
            ),
            Group("hobby", children = listOf(Lane("batch", appSettings = mapOf("match" to "nightly")))),
        ),
    )

    // ── the encoding ────────────────────────────────────────────────────────────────────────────

    /**
     * Every field a 0.7.7 document can hold, written by hand before settings existed. It decodes
     * with no settings anywhere and encodes to the same bytes, so a document that has none reads
     * and writes as it always did.
     */
    @Test
    fun aDocumentWithNoSettingsEncodesByteForByteAsBefore() {
        val golden = """{"root":"pool","children":[{"type":"group","name":"ci","weight":"3","children":[{"type":"lane","name":"main","weight":"3/2","maxConcurrent":0},""" +
            """{"type":"lane","name":"pr"}],"template":{"weight":"2","maxConcurrent":4,"template":{"maxConcurrent":1}},"maxConcurrent":8},""" +
            """{"type":"group","name":"idle","template":{}}]}"""
        val decoded = Json.decodeFromString(TopologyDocument.serializer(), golden)
        val ci = assertIs<Group>(decoded.children[0])
        assertAll(
            { assertEquals(golden, Json.encodeToString(TopologyDocument.serializer(), decoded)) },
            { assertEquals(emptyMap(), ci.appSettings) },
            { assertEquals(emptyMap(), assertIs<Lane>(ci.children[0]).appSettings) },
            { assertEquals(emptyMap(), ci.template?.appSettings) },
            { assertEquals(emptyMap(), ci.template?.template?.appSettings) },
        )
    }

    /**
     * Under `encodeDefaults = true` every other default is written out, and an empty settings map
     * still is not: a 0.7.7 reader decoding strictly refuses an `appSettings` key it does not know,
     * even an empty one, so a document with no settings must not carry the key in any `Json`.
     */
    @Test
    fun emptySettingsAreLeftOutEvenWhenDefaultsAreEncoded() {
        val withDefaults = Json { encodeDefaults = true }
        val bare = TopologyDocument("pool", listOf(Group("ci", children = listOf(Lane("main")), template = Template(template = Template())), Lane("web")))
        val golden = """{"root":"pool","children":[{"type":"group","name":"ci","weight":"1","children":[{"type":"lane","name":"main","weight":"1","maxConcurrent":null}],""" +
            """"template":{"weight":"1","maxConcurrent":null,"template":{"weight":"1","maxConcurrent":null,"template":null}},"maxConcurrent":null},""" +
            """{"type":"lane","name":"web","weight":"1","maxConcurrent":null}]}"""
        val set = TopologyDocument("pool", listOf(Group("ci", template = Template(appSettings = mapOf("split" to "repository")), appSettings = match)))
        val setGolden = """{"root":"pool","children":[{"type":"group","name":"ci","weight":"1","children":[],""" +
            """"template":{"weight":"1","maxConcurrent":null,"template":null,"appSettings":{"split":"repository"}},"maxConcurrent":null,"appSettings":{"match":"repo:*"}}]}"""
        assertAll(
            { assertEquals(golden, withDefaults.encodeToString(TopologyDocument.serializer(), bare)) },
            { assertEquals(bare, Json.decodeFromString(TopologyDocument.serializer(), golden)) },
            { assertEquals(setGolden, withDefaults.encodeToString(TopologyDocument.serializer(), set), "a map with keys is written as ever") },
            { assertEquals(set, Json.decodeFromString(TopologyDocument.serializer(), setGolden)) },
        )
    }

    @Test
    fun settingsEncodeBesideTheNodeTheyBelongTo() {
        val document = TopologyDocument(
            "pool",
            listOf(Group("ci", template = Template("2", appSettings = mapOf("split" to "repository")), appSettings = match), Lane("web", appSettings = mapOf("match" to "web"))),
        )
        val text = """{"root":"pool","children":[{"type":"group","name":"ci","template":{"weight":"2","appSettings":{"split":"repository"}},"appSettings":{"match":"repo:*"}},""" +
            """{"type":"lane","name":"web","appSettings":{"match":"web"}}]}"""
        assertAll(
            { assertEquals(text, Json.encodeToString(TopologyDocument.serializer(), document)) },
            { assertEquals(document, Json.decodeFromString(TopologyDocument.serializer(), text)) },
        )
    }

    // ── the edits ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun setAppSettingsReplacesTheNodesOwnAndNothingElse() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setAppSettings(listOf(0), mapOf("match" to "repo:kuilt")))
        val ci = assertIs<Group>(edited.document.children[0])
        val before = assertIs<Group>(doc.children[0])
        assertAll(
            { assertEquals(TopologyEdit.History.KEPT, edited.history) },
            { assertEquals(listOf(0), edited.path) },
            { assertEquals(mapOf("match" to "repo:kuilt"), ci.appSettings) },
            { assertEquals(before.copy(appSettings = mapOf("match" to "repo:kuilt")), ci, "the children and the template are untouched") },
            { assertEquals(doc.children[1], edited.document.children[1]) },
        )
    }

    @Test
    fun setAppSettingsWithAnEmptyMapClearsThem() {
        val edited = assertIs<TopologyEdit.Edited>(doc.setAppSettings(listOf(0, 0), emptyMap()))
        assertEquals(Lane("main"), assertIs<Group>(edited.document.children[0]).children[0])
    }

    @Test
    fun setAppSettingsIsRefusedAtTheRootAndWhereThereIsNoNode() {
        assertAll(
            { assertEquals(TopologyEdit.AtRoot, doc.setAppSettings(emptyList(), match)) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(2)), doc.setAppSettings(listOf(2), match)) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(-1)), doc.setAppSettings(listOf(-1), match)) },
            { assertEquals(TopologyEdit.NoSuchNode(listOf(0, 0, 0)), doc.setAppSettings(listOf(0, 0, 0), match), "nothing is below a lane") },
        )
    }

    @Test
    fun aRenameCopiesTheSettingsOntoTheNewName() {
        val edited = assertIs<TopologyEdit.Edited>(doc.rename(listOf(0, 0), "trunk"))
        assertAll(
            { assertEquals(TopologyEdit.History.REPLACED, edited.history) },
            { assertEquals(Lane("trunk", appSettings = mapOf("match" to "branch:main")), assertIs<Group>(edited.document.children[0]).children[0]) },
        )
    }

    @Test
    fun bothMovesKeepTheSettingsOfTheWholeSubtree() {
        val last = assertIs<TopologyEdit.Edited>(doc.move(listOf(1, 0), listOf(0)))
        val first = assertIs<TopologyEdit.Edited>(doc.move(listOf(1, 0), listOf(0), 0))
        val subtree = assertIs<TopologyEdit.Edited>(doc.move(listOf(0), listOf(1), 0))
        val batch = Lane("batch", appSettings = mapOf("match" to "nightly"))
        assertAll(
            { assertEquals(listOf(Lane("main", appSettings = mapOf("match" to "branch:main")), Lane("pr"), batch), assertIs<Group>(last.document.children[0]).children) },
            { assertEquals(batch, assertIs<Group>(first.document.children[0]).children[0]) },
            { assertEquals(doc.children[0], assertIs<Group>(subtree.document.children[0]).children[0], "the group, its children and its template levels") },
        )
    }

    @Test
    fun aRemovalTakesTheSettingsWithTheNode() {
        val edited = assertIs<TopologyEdit.Edited>(doc.remove(listOf(1, 0)))
        assertEquals(emptyList(), assertIs<Group>(edited.document.children[1]).children)
    }

    @Test
    fun anAddedChildCarriesItsOwnSettings() {
        val nightly = Lane("nightly", appSettings = mapOf("match" to "cron"))
        val edited = assertIs<TopologyEdit.Edited>(doc.addChild(listOf(1), nightly))
        assertEquals(nightly, assertIs<Group>(edited.document.children[1]).children[1])
    }

    @Test
    fun setTemplateKeepsTheGroupsSettingsAndReplacesTheTemplates() {
        val replacement = Template("2", appSettings = mapOf("split" to "team"))
        val edited = assertIs<TopologyEdit.Edited>(doc.setTemplate(listOf(0), replacement))
        val ci = assertIs<Group>(edited.document.children[0])
        assertAll(
            { assertEquals(match, ci.appSettings) },
            { assertEquals(replacement, ci.template, "the nested level and its split key go with the old template") },
        )
    }

    @Test
    fun weightAndCeilingEditsKeepTheSettings() {
        val weighed = assertIs<TopologyEdit.Edited>(doc.setWeight(listOf(0), "5"))
        val capped = assertIs<TopologyEdit.Edited>(weighed.document.setMaxConcurrent(listOf(0), 2L))
        assertEquals(assertIs<Group>(doc.children[0]).copy(weight = "5", maxConcurrent = 2L), capped.document.children[0])
    }

    // ── where Topology stops ────────────────────────────────────────────────────────────────────

    @Test
    fun topologyFromIgnoresTheSettingsAndToDocumentWritesNone() {
        val bare = TopologyDocument(
            "pool",
            listOf(
                Group("ci", "3", listOf(Lane("main"), Lane("pr")), Template("2", template = Template())),
                Group("hobby", children = listOf(Lane("batch"))),
            ),
        )
        val withSettings = assertIs<TopologyParse.Valid>(Topology.from(doc)).topology
        assertAll(
            { assertEquals(Topology.from(bare), TopologyParse.Valid(withSettings)) },
            { assertEquals(bare, withSettings.toDocument()) },
        )
    }

    // ── the change report ───────────────────────────────────────────────────────────────────────

    @Test
    fun equalSettingsReportNothingWhateverElseChanged() {
        val reweighed = assertIs<TopologyEdit.Edited>(doc.setWeight(listOf(0), "5")).document
        val reordered = assertIs<TopologyEdit.Edited>(reweighed.move(listOf(0, 1), listOf(0), 0)).document
        assertEquals(emptyList(), doc.appSettingsChangesTo(reordered))
    }

    @Test
    fun aKeyIsAddedRemovedOrChangedAtTheNodeAndDepthItBelongsTo() {
        val after = doc.copy(
            children = listOf(
                assertIs<Group>(doc.children[0]).copy(
                    appSettings = mapOf("match" to "repo:kuilt", "owner" to "infra"),
                    template = Template("2", appSettings = emptyMap(), template = Template(appSettings = mapOf("split" to "author"))),
                ),
                doc.children[1],
            ),
        )
        assertEquals(
            listOf(
                AppSettingsChange.Changed(listOf(0), listOf("pool", "ci"), 0, "match", "repo:*", "repo:kuilt"),
                AppSettingsChange.Added(listOf(0), listOf("pool", "ci"), 0, "owner", "infra"),
                AppSettingsChange.Removed(listOf(0), listOf("pool", "ci"), 1, "split", "repository"),
            ),
            doc.appSettingsChangesTo(after),
            "keys sorted within a map, the node's own before its template's",
        )
    }

    @Test
    fun aMovedNodesSettingsAreRemovedAtTheOldAddressAndAddedAtTheNew() {
        val moved = assertIs<TopologyEdit.Edited>(doc.move(listOf(1, 0), listOf(0), 0)).document
        assertEquals(
            listOf(
                AppSettingsChange.Added(listOf(0, 0), listOf("pool", "ci", "batch"), 0, "match", "nightly"),
                AppSettingsChange.Removed(listOf(1, 0), listOf("pool", "hobby", "batch"), 0, "match", "nightly"),
            ),
            doc.appSettingsChangesTo(moved),
            "matched by names from the root, as changesTo matches: the removal is addressed in the document before",
        )
    }

    @Test
    fun aRemovalIsAddressedInTheDocumentBeforeWhetherOrNotItsNodeStays() {
        val shifted = assertIs<TopologyEdit.Edited>(doc.remove(listOf(0, 0))).document
        val cleared = assertIs<TopologyEdit.Edited>(shifted.setAppSettings(listOf(0, 0), emptyMap())).document
        val pr = TopologyDocument("pool", listOf(Group("ci", children = listOf(Lane("x"), Lane("pr", appSettings = match)))))
        val prAfter = TopologyDocument("pool", listOf(Group("ci", children = listOf(Lane("pr")))))
        assertAll(
            {
                assertEquals(
                    listOf(AppSettingsChange.Removed(listOf(0, 0), listOf("pool", "ci", "main"), 0, "match", "branch:main")),
                    doc.appSettingsChangesTo(cleared),
                    "main is gone",
                )
            },
            {
                assertEquals(
                    listOf(AppSettingsChange.Removed(listOf(0, 1), listOf("pool", "ci", "pr"), 0, "match", "repo:*")),
                    pr.appSettingsChangesTo(prAfter),
                    "pr stays, at index 1 before and 0 after: addressed before",
                )
            },
        )
    }

    /**
     * Before `[a, b]`, after `[b, c]`: a's removal and b's change both have path `[0]`, and the arm
     * says which document that is. Were the removal addressed in the document after, a marker for
     * it would land on b.
     */
    @Test
    fun theArmSaysWhichDocumentAPathIsIn() {
        val before = TopologyDocument("r", listOf(Lane("a", appSettings = mapOf("k" to "1")), Lane("b", appSettings = mapOf("k" to "1"))))
        val after = TopologyDocument("r", listOf(Lane("b", appSettings = mapOf("k" to "2")), Lane("c")))
        assertEquals(
            listOf(
                AppSettingsChange.Changed(listOf(0), listOf("r", "b"), 0, "k", "1", "2"),
                AppSettingsChange.Removed(listOf(0), listOf("r", "a"), 0, "k", "1"),
            ),
            before.appSettingsChangesTo(after),
            "the change is at after[0], which is b; the removal at before[0], which is a",
        )
    }

    /**
     * The documented order, under one parent that has every kind of child: the parent's own
     * change, then its template level's, then its children in the order written after (a kept
     * child, a kept group's changed grandchild, an added child), then the child it lost. The kept
     * child moved from index 1 to 0, so its removed key and its changed one carry different paths
     * while sitting together, keys sorted.
     */
    @Test
    fun changesComeInDocumentOrder() {
        val before = TopologyDocument(
            "pool",
            listOf(
                Group(
                    "ci",
                    children = listOf(
                        Lane("old", appSettings = mapOf("match" to "o")),
                        Lane("main", appSettings = mapOf("extra" to "e", "match" to "m1")),
                        Group("web", children = listOf(Lane("x", appSettings = mapOf("match" to "x1")))),
                    ),
                    template = Template(appSettings = mapOf("split" to "repository")),
                    appSettings = mapOf("owner" to "a"),
                ),
            ),
        )
        val after = TopologyDocument(
            "pool",
            listOf(
                Group(
                    "ci",
                    children = listOf(
                        Lane("main", appSettings = mapOf("match" to "m2")),
                        Group("web", children = listOf(Lane("x", appSettings = mapOf("match" to "x2")))),
                        Lane("new", appSettings = mapOf("match" to "n")),
                    ),
                    template = Template(appSettings = mapOf("split" to "team")),
                    appSettings = mapOf("owner" to "b"),
                ),
            ),
        )
        assertEquals(
            listOf(
                AppSettingsChange.Changed(listOf(0), listOf("pool", "ci"), 0, "owner", "a", "b"),
                AppSettingsChange.Changed(listOf(0), listOf("pool", "ci"), 1, "split", "repository", "team"),
                AppSettingsChange.Removed(listOf(0, 1), listOf("pool", "ci", "main"), 0, "extra", "e"),
                AppSettingsChange.Changed(listOf(0, 0), listOf("pool", "ci", "main"), 0, "match", "m1", "m2"),
                AppSettingsChange.Changed(listOf(0, 1, 0), listOf("pool", "ci", "web", "x"), 0, "match", "x1", "x2"),
                AppSettingsChange.Added(listOf(0, 2), listOf("pool", "ci", "new"), 0, "match", "n"),
                AppSettingsChange.Removed(listOf(0, 0), listOf("pool", "ci", "old"), 0, "match", "o"),
            ),
            before.appSettingsChangesTo(after),
        )
    }

    @Test
    fun aRenamedRootMatchesNothing() {
        val small = TopologyDocument("pool", listOf(Lane("web", appSettings = match)))
        val renamed = assertIs<TopologyEdit.Edited>(small.rename(emptyList(), "farm")).document
        assertEquals(
            listOf(
                AppSettingsChange.Added(listOf(0), listOf("farm", "web"), 0, "match", "repo:*"),
                AppSettingsChange.Removed(listOf(0), listOf("pool", "web"), 0, "match", "repo:*"),
            ),
            small.appSettingsChangesTo(renamed),
        )
    }

    @Test
    fun theNthSiblingOfANameMatchesTheNthOfThatName() {
        val twins = TopologyDocument("pool", listOf(Lane("a", appSettings = mapOf("k" to "1")), Lane("a", appSettings = mapOf("k" to "2"))))
        val swapped = TopologyDocument("pool", listOf(Lane("a", appSettings = mapOf("k" to "2")), Lane("a", appSettings = mapOf("k" to "1"))))
        assertEquals(
            listOf(
                AppSettingsChange.Changed(listOf(0), listOf("pool", "a"), 0, "k", "1", "2"),
                AppSettingsChange.Changed(listOf(1), listOf("pool", "a"), 0, "k", "2", "1"),
            ),
            twins.appSettingsChangesTo(swapped),
        )
    }

    @Test
    fun aChangeRefusesAnAddressNoDocumentHas() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { AppSettingsChange.Added(emptyList(), listOf("pool"), 0, "k", "v") } },
            { assertFailsWith<IllegalArgumentException> { AppSettingsChange.Added(listOf(-1), listOf("pool", "a"), 0, "k", "v") } },
            { assertFailsWith<IllegalArgumentException> { AppSettingsChange.Removed(listOf(0), listOf("a"), 0, "k", "v") } },
            { assertFailsWith<IllegalArgumentException> { AppSettingsChange.Removed(listOf(0), listOf("pool", "a"), -1, "k", "v") } },
            { assertFailsWith<IllegalArgumentException> { AppSettingsChange.Changed(listOf(0), listOf("pool", "a"), 0, "k", "v", "v") } },
        )
    }
}
