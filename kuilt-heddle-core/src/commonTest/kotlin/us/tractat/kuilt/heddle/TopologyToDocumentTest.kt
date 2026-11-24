package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Topology.toDocument] and [Topology.from] are exact inverses on a valid tree.
 *
 * The property runs over seeded random trees carrying every field a document can hold: groups and
 * lanes to depth three, whole and ratio weights up to `Long.MAX_VALUE`, ceilings of `null`, `0` and
 * `Long.MAX_VALUE`, and templates with and without a ceiling. Every draw is from a `List` this file
 * built, so a seed walks the same trajectory on every target, and each field is counted and floored
 * so a generator that stopped producing one reds instead of passing.
 */
class TopologyToDocumentTest {

    private val seeds = 300
    private val pieces = listOf("a", "b", "/", "%2F", "!x", "@1:1#0", "é", "\uD800", "😀", " ")
    private val weights = listOf(
        Weight.ONE,
        Weight.of(3),
        Weight.of(3, 2),
        Weight.of(2, 3),
        Weight.of(Long.MAX_VALUE),
        Weight.of(Long.MAX_VALUE, Long.MAX_VALUE - 1),
    )
    private val ceilings = listOf(null, 0L, 2L, Long.MAX_VALUE)

    @Test
    fun fromReadsBackEveryGeneratedTree() {
        val failures = ArrayList<String>()
        val tally = Tally()
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val t = Topology(randomName(r), randomChildren(r, depth = 0))
            tally.count(t.children)
            val parsed = Topology.from(t.toDocument())
            if (parsed != TopologyParse.Valid(t)) failures += "seed $seed: $t read back as $parsed"
            weightTexts(t.toDocument().children).filterNot { canonical.matches(it) }.forEach {
                failures += "seed $seed: weight text \"$it\" is not digits, optionally a slash and a denominator other than 1"
            }
        }
        assertAll(
            { assertEquals(emptyList(), failures) },
            { assertTrue(tally.ratios >= 50, "ratio weights: ${tally.ratios}") },
            { assertTrue(tally.groups >= 50, "groups: ${tally.groups}") },
            { assertTrue(tally.ceilings >= 50, "ceilings: ${tally.ceilings}") },
            { assertTrue(tally.zeroCeilings >= 20, "zero ceilings: ${tally.zeroCeilings}") },
            { assertTrue(tally.templates >= 20, "templates: ${tally.templates}") },
            { assertTrue(tally.templateCeilings >= 10, "template ceilings: ${tally.templateCeilings}") },
            { assertTrue(tally.templateRatios >= 10, "template ratio weights: ${tally.templateRatios}") },
        )
    }

    @Test
    fun weightsAreWrittenAsAnAdminWritesThem() {
        fun text(weight: Weight): String = (draft("p") { lane("x", weight) }.toDocument().children.single()).weight
        assertAll(
            { assertEquals("3", text(Weight.of(3))) },
            { assertEquals("3/2", text(Weight.of(3, 2))) },
            { assertEquals("3/2", text(Weight.of(6, 4))) },
            { assertEquals("2", text(Weight.of(4, 2))) },
            { assertEquals("1", text(Weight.ONE)) },
            { assertEquals("9223372036854775807/2", text(Weight.of(Long.MAX_VALUE, 2))) },
        )
    }

    @Test
    fun aDocumentKeepsEveryField() {
        val t = draft("pool") {
            group("acme", Weight.of(3), maxConcurrent = 4) {
                lane("interactive", Weight.of(3, 2), maxConcurrent = 0)
                template(Weight.of(1, 2), maxConcurrent = 1)
            }
        }
        val expected = TopologyDocument(
            "pool",
            listOf(
                TopologyDocument.Group(
                    "acme",
                    "3",
                    listOf(TopologyDocument.Lane("interactive", "3/2", 0L)),
                    TopologyDocument.ChildTemplate("1/2", 1L),
                    4L,
                ),
            ),
        )
        assertEquals(expected, t.toDocument())
    }

    /** The document is the form a config file holds, so it survives the file too. */
    @Test
    fun aDocumentSurvivesJson() {
        val t = draft("pool") {
            group("acme", Weight.of(3, 2), maxConcurrent = 4) { template(maxConcurrent = 1) }
            lane("hobby")
        }
        val text = Json.encodeToString(TopologyDocument.serializer(), t.toDocument())
        assertEquals(TopologyParse.Valid(t), Topology.from(Json.decodeFromString(TopologyDocument.serializer(), text)))
    }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private val canonical = Regex("[1-9][0-9]*(/([2-9]|[1-9][0-9]+))?")

    private fun weightTexts(nodes: List<TopologyDocument.Node>): List<String> = nodes.flatMap { node ->
        listOf(node.weight) + when (node) {
            is TopologyDocument.Group -> listOfNotNull(node.template?.weight) + weightTexts(node.children)
            is TopologyDocument.Lane -> emptyList()
        }
    }

    private fun randomName(r: Random): String = (1..1 + r.nextInt(2)).joinToString("") { pieces[r.nextInt(pieces.size)] }

    private fun <T> List<T>.draw(r: Random): T = this[r.nextInt(size)]

    private fun randomChildren(r: Random, depth: Int): List<Node> {
        val names = (1..1 + r.nextInt(3)).map { randomName(r) }.distinct()
        return names.map { name ->
            if (depth < 3 && r.nextInt(2) == 0) {
                val template = if (r.nextInt(3) == 0) ChildTemplate(weights.draw(r), ceilings.draw(r)) else null
                // A group with a template may declare no children.
                val children = if (template != null && r.nextInt(3) == 0) emptyList() else randomChildren(r, depth + 1)
                Group(name, weights.draw(r), children, template, ceilings.draw(r))
            } else {
                Lane(name, weights.draw(r), ceilings.draw(r))
            }
        }
    }

    private class Tally {
        var ratios = 0
        var groups = 0
        var ceilings = 0
        var zeroCeilings = 0
        var templates = 0
        var templateCeilings = 0
        var templateRatios = 0

        fun count(nodes: List<Node>) {
            for (node in nodes) {
                if (node.weight.denominator != 1L) ratios++
                if (node.maxConcurrent != null) ceilings++
                if (node.maxConcurrent == 0L) zeroCeilings++
                if (node is Group) {
                    groups++
                    node.template?.let { template ->
                        templates++
                        if (template.maxConcurrent != null) templateCeilings++
                        if (template.weight.denominator != 1L) templateRatios++
                    }
                    count(node.children)
                }
            }
        }
    }
}
