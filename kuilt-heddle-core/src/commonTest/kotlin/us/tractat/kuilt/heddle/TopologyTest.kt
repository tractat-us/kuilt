package us.tractat.kuilt.heddle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.TopologyProblem.Kind.DUPLICATE_NAME
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_GROUP
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_NAME
import us.tractat.kuilt.heddle.TopologyProblem.Kind.INVALID_WEIGHT
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TopologyTest {

    private val json = Json

    /** A problem without its prose, so a test pins the address and kind but not the wording. */
    private data class Key(val parent: List<String>, val childIndex: Int?, val kind: TopologyProblem.Kind)

    private fun List<TopologyProblem>.keys(): List<Key> = map { Key(it.parent, it.childIndex, it.kind) }

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
                        Key(listOf("root", "acme"), 1, DUPLICATE_NAME),
                        Key(listOf("root", "hobby"), 1, EMPTY_NAME),
                        Key(listOf("root", "idle"), null, EMPTY_GROUP),
                        Key(listOf("root"), 3, INVALID_WEIGHT),
                    ),
                    problems.keys(),
                )
            },
            { assertTrue(problems.all { it.detail.isNotBlank() }, "every problem says what is wrong: $problems") },
        )
    }

    @Test
    fun emptyNameProblemDoesNotCollideWithHashNames() {
        val problems = invalid(TopologyDocument("root", listOf(lane("#1"), lane(""))))

        assertEquals(listOf(Key(listOf("root"), 1, EMPTY_NAME)), problems.keys())
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
                        listOf(Key(listOf("root"), 0, INVALID_WEIGHT)),
                        invalid(TopologyDocument("root", listOf(lane("x", text)))).keys(),
                        "weight text '$text'",
                    )
                }
            }.toTypedArray(),
            // The runtime-children marker's weight is a property of the group itself.
            { assertEquals(listOf(Key(listOf("root", "tenants"), null, INVALID_WEIGHT)), markerProblems.keys()) },
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
                        Key(listOf("root", "idle"), null, EMPTY_GROUP),
                        Key(listOf("root"), 1, EMPTY_NAME),
                        Key(listOf("root"), 3, DUPLICATE_NAME),
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
                assertEquals(listOf(Key(listOf("root", "idle"), null, EMPTY_GROUP)), thrown.problems.keys())
            },
            { assertFailsWith<SerializationException> { json.decodeFromString(Topology.serializer(), zeroWeight) } },
            // The permissive form takes the same bytes without complaint and reports them instead.
            {
                val document = json.decodeFromString(TopologyDocument.serializer(), emptyGroup)
                assertEquals(listOf(Key(listOf("root", "idle"), null, EMPTY_GROUP)), invalid(document).keys())
            },
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
    fun problemsBeneathAnUnaddressableChildWaitForItsName() {
        // Beneath the second `batch` and beneath the unnamed group, a problem's parent path would
        // name two groups at once, so those subtrees are not walked until the name is fixed. The
        // first `batch` is addressable, and its empty grandchild is reported.
        val document = TopologyDocument(
            root = "root",
            children = listOf(
                TopologyDocument.Group("batch", children = listOf(TopologyDocument.Group("idle"))),
                TopologyDocument.Group("batch", children = listOf(TopologyDocument.Group("idle"))),
                TopologyDocument.Group("", children = listOf(TopologyDocument.Group("idle"))),
            ),
        )

        assertEquals(
            listOf(
                Key(listOf("root", "batch", "idle"), null, EMPTY_GROUP),
                Key(listOf("root"), 1, DUPLICATE_NAME),
                Key(listOf("root"), 2, EMPTY_NAME),
            ),
            invalid(document).keys(),
        )
    }
}
