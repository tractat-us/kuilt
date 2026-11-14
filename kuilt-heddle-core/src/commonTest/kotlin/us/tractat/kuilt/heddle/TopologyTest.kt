package us.tractat.kuilt.heddle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.TopologyProblem.Kind.CONSUMER
import us.tractat.kuilt.heddle.TopologyProblem.Kind.DUPLICATE_NAME
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_GROUP
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_NAME
import us.tractat.kuilt.heddle.TopologyProblem.Kind.INVALID_WEIGHT
import us.tractat.kuilt.heddle.TopologyProblem.Kind.NEGATIVE_MAX_CONCURRENT
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologyTest {

    private val json = Json

    /** A problem without its prose, so a test pins the address and kind but not the wording. */
    private data class Key(val path: List<Int>, val kind: TopologyProblem.Kind)

    private fun List<TopologyProblem>.keys(): List<Key> = map { Key(it.path, it.kind) }

    private fun invalid(document: TopologyDocument): List<TopologyProblem> =
        assertIs<TopologyParse.Invalid>(Topology.from(document)).problems

    private fun lane(name: String, weight: String = "1") = TopologyDocument.Lane(name, weight)

    @Test
    fun fromCollectsEveryProblem() {
        val document = TopologyDocument(
            root = "root",
            children = listOf(
                TopologyDocument.Group("acme", "3", children = listOf(lane("batch"), lane("batch"))),
                TopologyDocument.Group("hobby", children = listOf(lane("a"), lane(""))),
                TopologyDocument.Group("idle"),
                lane("x", weight = "0"),
            ),
        )

        val problems = invalid(document)

        assertAll(
            {
                assertEquals(
                    listOf(
                        Key(listOf(0, 1), DUPLICATE_NAME),
                        Key(listOf(1, 1), EMPTY_NAME),
                        Key(listOf(2), EMPTY_GROUP),
                        Key(listOf(3), INVALID_WEIGHT),
                    ),
                    problems.keys(),
                )
            },
            {
                assertEquals(
                    listOf(listOf("root", "acme", "batch"), listOf("root", "hobby", ""), listOf("root", "idle"), listOf("root", "x")),
                    problems.map { it.names },
                )
            },
            { assertTrue(problems.all { it.detail.isNotBlank() }, "every problem says what is wrong: $problems") },
        )
    }

    /**
     * [TopologyProblem.Kind.CONSUMER] is the app's, never heddle's. The document reaches every kind
     * heddle reports, so this reds both if `from` starts reporting the app's kind and if heddle
     * gains a kind this fixture never reaches.
     */
    @Test
    fun fromReportsEveryKindButTheConsumers() {
        val document = TopologyDocument(
            root = "",
            children = listOf(
                TopologyDocument.Group("acme", "3", children = listOf(lane("batch"), lane("batch"), lane(""))),
                TopologyDocument.Group("idle", runtimeChildren = "0"),
                TopologyDocument.Group("empty"),
                lane("x", weight = "0"),
                TopologyDocument.Lane("capped", maxConcurrent = -1L),
            ),
        )

        assertEquals(TopologyProblem.Kind.entries.toSet() - CONSUMER, invalid(document).map { it.kind }.toSet())
    }

    @Test
    fun emptyNameProblemDoesNotCollideWithHashNames() {
        val problems = invalid(TopologyDocument("root", listOf(lane("#1"), lane(""))))

        assertAll(
            { assertEquals(listOf(Key(listOf(1), EMPTY_NAME)), problems.keys()) },
            { assertEquals(listOf("root", ""), problems.single().names) },
        )
    }

    @Test
    fun weightTextAcceptsWholeAndRatio() {
        fun parsedWeight(text: String): Weight {
            val topology = assertIs<TopologyParse.Valid>(Topology.from(TopologyDocument("root", listOf(lane("x", text))))).topology
            return topology.children.single().weight
        }
        val rejected = listOf("0", "-1", "x", "1/0", "", "3/", "/2", "1/2/3", " 3", "+3", "99999999999999999999")
        val markerProblems = invalid(
            TopologyDocument("root", listOf(TopologyDocument.Group("tenants", runtimeChildren = "0"))),
        )

        assertAll(
            { assertEquals(Weight.of(3), parsedWeight("3")) },
            { assertEquals(Weight.of(3, 2), parsedWeight("3/2")) },
            { assertEquals(Weight.of(1, 2), parsedWeight("2/4")) },
            *rejected.map { text ->
                {
                    assertEquals(
                        listOf(Key(listOf(0), INVALID_WEIGHT)),
                        invalid(TopologyDocument("root", listOf(lane("x", text)))).keys(),
                        "weight text '$text'",
                    )
                }
            }.toTypedArray(),
            // The runtime-children marker's weight is a property of the group itself.
            { assertEquals(listOf(Key(listOf(0), INVALID_WEIGHT)), markerProblems.keys()) },
        )
    }

    @Test
    fun fromValidDocumentEqualsHandBuiltTopology() {
        val document = TopologyDocument(
            root = "root",
            children = listOf(
                TopologyDocument.Group(
                    "acme",
                    "3",
                    children = listOf(lane("interactive", "3/2"), lane("batch")),
                ),
                TopologyDocument.Group("tenants", runtimeChildren = "2"),
                lane("hobby"),
            ),
        )
        val handBuilt = Topology(
            root = "root",
            children = listOf(
                Topology.Group(
                    "acme",
                    Weight.of(3),
                    children = listOf(Topology.Lane("interactive", Weight.of(3, 2)), Topology.Lane("batch")),
                ),
                Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.of(2))),
                Topology.Lane("hobby", Weight.ONE),
            ),
        )

        assertEquals(TopologyParse.Valid(handBuilt), Topology.from(document))
    }

    @Test
    fun constructingInvalidTopologyThrowsWithAllProblems() {
        val children = listOf(
            Topology.Group("idle", Weight.ONE, emptyList()),
            Topology.Lane(""),
            Topology.Lane("x"),
            Topology.Lane("x"),
        )

        val thrown = assertFailsWith<TopologyException> { Topology("root", children) }

        assertAll(
            {
                assertEquals(
                    listOf(
                        Key(listOf(0), EMPTY_GROUP),
                        Key(listOf(1), EMPTY_NAME),
                        Key(listOf(3), DUPLICATE_NAME),
                    ),
                    thrown.problems.keys(),
                )
            },
            { assertEquals(Topology.problems("root", children), thrown.problems) },
            { assertEquals(thrown.problems.size, thrown.message.orEmpty().lines().size, "one line per problem") },
            { assertFailsWith<TopologyException> { Topology("root", emptyList()) } },
        )
    }

    @Test
    fun topologyJsonRoundTrips() {
        val topology = Topology(
            root = "root",
            children = listOf(
                Topology.Group(
                    "acme",
                    Weight.of(3),
                    children = listOf(Topology.Lane("é#%@", Weight.of(3, 2)), Topology.Lane("a/b")),
                ),
                Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.of(2))),
            ),
        )

        val encoded = json.encodeToString(Topology.serializer(), topology)

        assertAll(
            { assertEquals(topology, json.decodeFromString(Topology.serializer(), encoded)) },
            // A weight is text on the wire, the same text an admin writes in a TopologyDocument.
            { assertTrue("\"weight\":\"3/2\"" in encoded, encoded) },
            { assertTrue("\"weight\":\"3\"" in encoded, encoded) },
        )
    }

    @Test
    fun decodingAnInvalidTopologyThrows() {
        val emptyGroup = """{"root":"root","children":[{"type":"group","name":"idle","weight":"1","children":[]}]}"""
        val zeroWeight = """{"root":"root","children":[{"type":"lane","name":"x","weight":"0"}]}"""

        assertAll(
            {
                val thrown = assertFailsWith<TopologyException> { json.decodeFromString(Topology.serializer(), emptyGroup) }
                assertEquals(listOf(Key(listOf(0), EMPTY_GROUP)), thrown.problems.keys())
            },
            { assertFailsWith<SerializationException> { json.decodeFromString(Topology.serializer(), zeroWeight) } },
            // The permissive form takes the same bytes without complaint and reports them instead.
            {
                val document = json.decodeFromString(TopologyDocument.serializer(), emptyGroup)
                assertEquals(listOf(Key(listOf(0), EMPTY_GROUP)), invalid(document).keys())
            },
        )
    }

    @Test
    fun aWrongShapeIsTheFormatsCallNotTopologys() {
        // What `TopologyDocument`'s KDoc and the cookbook promise about JSON: `from` reports wrong
        // values, but whether a wrong shape decodes at all is decided by the consumer's format.
        fun document(weight: String) = """{"root":"root","children":[{"type":"lane","name":"batch","weight":$weight}]}"""
        val quoted = document("\"3\"")
        val bareNumber = document("3")
        val misspeltKey = """{"root":"root","children":[{"type":"lane","name":"batch","weigth":"3"}]}"""
        val lenient = Json { isLenient = true }
        val expected = TopologyDocument("root", listOf(lane("batch", "3")))

        assertAll(
            { assertEquals(expected, json.decodeFromString(TopologyDocument.serializer(), quoted)) },
            { assertFailsWith<SerializationException> { json.decodeFromString(TopologyDocument.serializer(), bareNumber) } },
            { assertEquals(expected, lenient.decodeFromString(TopologyDocument.serializer(), bareNumber)) },
            { assertFailsWith<SerializationException> { json.decodeFromString(TopologyDocument.serializer(), misspeltKey) } },
        )
    }

    @Test
    fun acceptsAMarkedGroupWithNoDeclaredChildren() {
        val marked = Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.of(2)))

        assertAll(
            { assertEquals(listOf(marked), Topology("root", listOf(marked)).children) },
            {
                val document = TopologyDocument("root", listOf(TopologyDocument.Group("tenants", runtimeChildren = "2")))
                assertEquals(TopologyParse.Valid(Topology("root", listOf(marked))), Topology.from(document))
            },
        )
    }

    @Test
    fun sameNameAtDifferentDepthsIsAllowed() {
        val nested = Topology.Group("a", Weight.ONE, listOf(Topology.Group("a", Weight.ONE, listOf(Topology.Lane("a")))))

        assertAll(
            { assertEquals(emptyList(), Topology.problems("a", listOf(nested))) },
            { assertEquals(listOf(nested), Topology("a", listOf(nested)).children) },
        )
    }

    @Test
    fun problemsBeneathAlikeOrUnnamedSiblingsHaveDistinctPaths() {
        // Both `batch`es and the unnamed group hold an empty `idle`. By name the three would be one
        // address; by index they are three, so every one is reported.
        val document = TopologyDocument(
            root = "root",
            children = listOf(
                TopologyDocument.Group("batch", children = listOf(TopologyDocument.Group("idle"))),
                TopologyDocument.Group("batch", children = listOf(TopologyDocument.Group("idle"))),
                TopologyDocument.Group("", children = listOf(TopologyDocument.Group("idle"))),
            ),
        )

        val problems = invalid(document)
        val emptyGroups = problems.filter { it.kind == EMPTY_GROUP }

        assertAll(
            {
                assertEquals(
                    listOf(
                        Key(listOf(0, 0), EMPTY_GROUP),
                        Key(listOf(1), DUPLICATE_NAME),
                        Key(listOf(1, 0), EMPTY_GROUP),
                        Key(listOf(2), EMPTY_NAME),
                        Key(listOf(2, 0), EMPTY_GROUP),
                    ),
                    problems.keys(),
                )
            },
            { assertEquals(listOf(listOf(0, 0), listOf(1, 0), listOf(2, 0)), emptyGroups.map { it.path }) },
            // The display names cannot tell the two `batch` subtrees apart; the paths can.
            { assertEquals(listOf("root", "batch", "idle"), emptyGroups[0].names) },
            { assertEquals(emptyGroups[0].names, emptyGroups[1].names) },
        )
    }

    /**
     * A ceiling below zero is reported at the node it is written on, and the runtime children's
     * ceiling at the marked group, like the marker's weight. Zero is a pause, not a problem.
     */
    @Test
    fun aNegativeCeilingIsAProblemAndZeroIsValid() {
        fun document(cap: Long) = TopologyDocument(
            "root",
            listOf(
                TopologyDocument.Group("acme", children = listOf(TopologyDocument.Lane("batch", maxConcurrent = cap)), maxConcurrent = cap),
                TopologyDocument.Group("tenants", runtimeChildren = "1", runtimeChildrenMaxConcurrent = cap),
            ),
        )
        fun built(cap: Long) = Topology(
            "root",
            listOf(
                Topology.Group("acme", Weight.ONE, listOf(Topology.Lane("batch", maxConcurrent = cap)), maxConcurrent = cap),
                Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.ONE, maxConcurrent = cap)),
            ),
        )
        val negative = listOf(
            Key(listOf(0), NEGATIVE_MAX_CONCURRENT),
            Key(listOf(0, 0), NEGATIVE_MAX_CONCURRENT),
            Key(listOf(1), NEGATIVE_MAX_CONCURRENT),
        )

        assertAll(
            { assertEquals(negative, invalid(document(-1L)).keys()) },
            { assertEquals(negative, invalid(document(Long.MIN_VALUE)).keys()) },
            { assertTrue(invalid(document(-1L)).all { "-1" in it.detail }, "the detail names the value: ${invalid(document(-1L))}") },
            { assertEquals(negative, assertFailsWith<TopologyException> { built(-1L) }.problems.keys()) },
            { assertEquals(TopologyParse.Valid(built(0L)), Topology.from(document(0L))) },
            { assertEquals(TopologyParse.Valid(built(Long.MAX_VALUE)), Topology.from(document(Long.MAX_VALUE))) },
        )
    }

    /**
     * A runtime-children ceiling belongs to the marker: a document group with a ceiling but no
     * `runtimeChildren` drops it, and the topology built is the same as one without it.
     */
    @Test
    fun aRuntimeChildrenCeilingWithoutAMarkerIsDropped() {
        fun document(cap: Long?) = TopologyDocument(
            "root",
            listOf(TopologyDocument.Group("acme", children = listOf(TopologyDocument.Lane("batch")), runtimeChildrenMaxConcurrent = cap)),
        )
        val parsed = Topology.from(document(5L))

        assertAll(
            { assertEquals(Topology.from(document(null)), parsed) },
            { assertEquals(null, ((parsed as TopologyParse.Valid).topology.children.single() as Topology.Group).runtimeChildren) },
        )
    }

    /** A ceiling survives every form a topology takes, and a node without one writes no key. */
    @Test
    fun aCeilingRoundTripsAndAnAbsentOneIsNotWritten() {
        val capped = Topology(
            "root",
            listOf(
                Topology.Group("acme", Weight.ONE, listOf(Topology.Lane("batch", maxConcurrent = 2L)), maxConcurrent = 3L),
                Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.ONE, maxConcurrent = 0L)),
            ),
        )
        val document = TopologyDocument(
            "root",
            listOf(
                TopologyDocument.Group("acme", children = listOf(TopologyDocument.Lane("batch", maxConcurrent = 2L)), maxConcurrent = 3L),
                TopologyDocument.Group("tenants", runtimeChildren = "1", runtimeChildrenMaxConcurrent = 0L),
            ),
        )
        val uncapped = Topology("root", listOf(Topology.Group("tenants", Weight.ONE, listOf(Topology.Lane("a")), Topology.RuntimeChildren(Weight.ONE))))
        val encoded = json.encodeToString(Topology.serializer(), capped)

        assertAll(
            { assertEquals(capped, json.decodeFromString(Topology.serializer(), encoded)) },
            { assertTrue("\"maxConcurrent\":3" in encoded && "\"maxConcurrent\":0" in encoded, encoded) },
            { assertTrue("maxConcurrent" !in json.encodeToString(Topology.serializer(), uncapped)) },
            { assertEquals(TopologyParse.Valid(capped), Topology.from(document)) },
            // `problems` reads a Topology back through the document form; a ceiling it dropped would pass silently.
            { assertEquals(document, json.decodeFromString(TopologyDocument.serializer(), json.encodeToString(TopologyDocument.serializer(), document))) },
        )
    }

    /**
     * Format 1 of [LocalHeddleSnapshot] stores the topology last applied and predates the ceiling:
     * the snapshot v0.7.4 wrote decodes with no ceiling on any node, the runtime marker included.
     */
    @Test
    fun aFormatOneSnapshotDecodesWithNoCeiling() {
        val restored = assertIs<RestoreResult.Restored>(LocalHeddle.decode(GOLDEN_FORMAT_1_RICH, Json))
        val lastApplied = restored.snapshot.lastApplied
        fun Topology.Node.ceilings(): List<Long?> = when (this) {
            is Topology.Lane -> listOf(maxConcurrent)
            is Topology.Group ->
                listOf(maxConcurrent) + listOfNotNull(runtimeChildren).map { it.maxConcurrent } + children.flatMap { it.ceilings() }
        }
        val ceilings = lastApplied.children.flatMap { it.ceilings() }

        assertAll(
            { assertTrue(lastApplied.children.any { it is Topology.Group && it.runtimeChildren != null }, "the rig: a marked group") },
            { assertEquals(7, ceilings.size, "the rig: three groups, three lanes and a marker were walked: $ceilings") },
            { ceilings.forEach { assertNull(it) } },
        )
    }

    @Test
    fun aProblemAddressIsWellFormed() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { TopologyProblem(listOf(0), listOf("root"), EMPTY_NAME, "d") } },
            { assertFailsWith<IllegalArgumentException> { TopologyProblem(listOf(-1), listOf("root", "x"), EMPTY_NAME, "d") } },
            { assertEquals(emptyList(), TopologyProblem(emptyList(), listOf(""), EMPTY_NAME, "d").path) },
        )
    }
}
