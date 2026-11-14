package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.TopologyProblem.Kind.DUPLICATE_NAME
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_GROUP
import us.tractat.kuilt.heddle.TopologyProblem.Kind.EMPTY_NAME
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TopologyDraftTest {

    /** The tree of `docs/heddle-design.md` §11.1. */
    @Test
    fun draftEqualsTheValueItDescribes() {
        val drafted = draft("root") {
            group("acme", Weight.of(3)) {
                lane("interactive", Weight.of(3))
                lane("batch", Weight.of(1))
            }
            group("hobby", Weight.of(1)) { lane("default") }
        }

        val handBuilt = Topology(
            "root",
            listOf(
                Topology.Group(
                    "acme",
                    Weight.of(3),
                    listOf(Topology.Lane("interactive", Weight.of(3)), Topology.Lane("batch", Weight.of(1))),
                ),
                Topology.Group("hobby", Weight.of(1), listOf(Topology.Lane("default"))),
            ),
        )

        assertEquals(handBuilt, drafted)
    }

    /** A ceiling written beside a group, a lane or a runtime marker lands on that node, and nowhere else. */
    @Test
    fun aDraftedCeilingLandsOnItsNode() {
        val drafted = draft("root") {
            group("acme", maxConcurrent = 3L) {
                lane("batch", maxConcurrent = 0L)
                lane("interactive")
            }
            group("tenants") { runtimeChildren(Weight.of(2), maxConcurrent = 1L) }
        }

        val handBuilt = Topology(
            "root",
            listOf(
                Topology.Group("acme", Weight.ONE, listOf(Topology.Lane("batch", maxConcurrent = 0L), Topology.Lane("interactive")), maxConcurrent = 3L),
                Topology.Group("tenants", Weight.ONE, emptyList(), Topology.RuntimeChildren(Weight.of(2), maxConcurrent = 1L)),
            ),
        )

        assertEquals(handBuilt, drafted)
    }

    @Test
    fun runtimeChildrenMarkTheGroupTheyAreDeclaredIn() {
        val drafted = draft("pool") {
            group("tenants", Weight.of(3, 2)) {
                runtimeChildren(Weight.of(2))
                group("inner") { runtimeChildren() }
            }
        }

        val tenants = assertIs<Topology.Group>(drafted.children.single())
        assertAll(
            { assertEquals(Weight.of(3, 2), tenants.weight) },
            { assertEquals(Topology.RuntimeChildren(Weight.of(2)), tenants.runtimeChildren) },
            {
                val inner = assertIs<Topology.Group>(tenants.children.single())
                assertEquals(Topology.RuntimeChildren(Weight.ONE), inner.runtimeChildren)
            },
        )
    }

    /**
     * A draft reports the same problems, at the same addresses and in the same order, as
     * [Topology.from] reports for the document that writes the same tree. Compared against `from`
     * rather than against literal addresses, so it pins "one walk" and not the address shape.
     */
    @Test
    fun draftProblemsArePathAddressed() {
        val thrown = assertFailsWith<TopologyException> {
            draft("root") {
                group("acme", Weight.of(3)) {
                    lane("batch")
                    lane("batch")
                    group("deep") { group("idle") {} }
                }
                group("hobby") {
                    lane("a")
                    lane("")
                }
                group("") { group("beneath-a-bad-name") {} }
                lane("acme")
            }
        }

        val equivalent = TopologyDocument(
            "root",
            listOf(
                TopologyDocument.Group(
                    "acme",
                    "3",
                    children = listOf(
                        TopologyDocument.Lane("batch"),
                        TopologyDocument.Lane("batch"),
                        TopologyDocument.Group("deep", children = listOf(TopologyDocument.Group("idle"))),
                    ),
                ),
                TopologyDocument.Group("hobby", children = listOf(TopologyDocument.Lane("a"), TopologyDocument.Lane(""))),
                TopologyDocument.Group("", children = listOf(TopologyDocument.Group("beneath-a-bad-name"))),
                TopologyDocument.Lane("acme"),
            ),
        )
        val expected = assertIs<TopologyParse.Invalid>(Topology.from(equivalent)).problems

        assertAll(
            // The rig: the equivalent document really is wrong in every way the draft is.
            { assertTrue(expected.size >= 5, "the fixture should carry at least five problems: $expected") },
            { assertEquals(setOf(DUPLICATE_NAME, EMPTY_NAME, EMPTY_GROUP), expected.map { it.kind }.toSet()) },
            { assertEquals(expected, thrown.problems) },
        )
    }

    @Test
    fun aSecondRuntimeChildrenInOneGroupThrows() {
        val thrown = assertFailsWith<IllegalStateException> {
            draft("root") {
                group("tenants") {
                    runtimeChildren(Weight.of(2))
                    runtimeChildren(Weight.of(3))
                }
            }
        }

        assertTrue("tenants" in (thrown.message ?: ""), "the message names the group: ${thrown.message}")
    }

    @Test
    fun aScopeKeptPastTheDraftCannotChangeTheTopology() {
        var kept: TopologyDraft.Group? = null
        val drafted = draft("root") {
            group("acme") {
                lane("batch")
                kept = this
            }
        }
        val before = drafted.toString()

        kept?.lane("smuggled")

        assertAll(
            { assertTrue(kept != null, "the rig kept a scope") },
            { assertEquals(before, drafted.toString()) },
            { assertEquals(1, assertIs<Topology.Group>(drafted.children.single()).children.size) },
        )
    }
}
