package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [Draft.combine] — the G2 DAG-branching combinator.
 *
 * [Draft.combine] merges two independent drafts into a single DAG where the two source
 * branches have no edges between them. This allows a planner to represent and optimise
 * multiple coordination points in one combined draft.
 *
 * Coverage:
 * - NodeId uniqueness and predecessor validity after combine.
 * - Both branches' stages and embroideries are present.
 * - Independence: no cross-branch predecessor edges.
 * - [Draft.isMonotone] and [Draft.embroidery] behave correctly on combined drafts.
 */
class DraftCombineTest {

    private val s1 = OpId("source.docs")
    private val s2 = OpId("source.scores")
    private val m1 = OpId("map.score")
    private val m2 = OpId("map.normalise")
    private val f1 = OpId("filter.nonzero")
    private val e1 = OpId("embroider.rank")
    private val e2 = OpId("embroider.vote")

    // ── NodeId uniqueness and predecessor validity ─────────────────────────────

    @Test
    fun `all NodeIds are unique after combine`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).filter(f1).embroider(e2)
        val combined = a.combine(b)
        val ids = combined.nodes.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `every predecessor references an existing node after combine`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).filter(f1).embroider(e2)
        val combined = a.combine(b)
        val knownIds = combined.nodes.map { it.id }.toSet()
        val allPredsValid = combined.nodes.all { node ->
            node.predecessors.all { pred -> pred in knownIds }
        }
        assertTrue(allPredsValid, "every predecessor must reference a node in the combined draft")
    }

    @Test
    fun `combined stages hold all stages from both branches in topological order`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).filter(f1)
        val combined = a.combine(b)
        val combinedStages = combined.stages
        // All of a's stages appear (in their original order)
        val aStageOps = a.stages.map { it.opId }
        val bStageOps = b.stages.map { it.opId }
        assertAll(
            { assertTrue(aStageOps.all { op -> combinedStages.any { it.opId == op } }) },
            { assertTrue(bStageOps.all { op -> combinedStages.any { it.opId == op } }) },
            { assertEquals(a.stages.size + b.stages.size, combinedStages.size) },
        )
    }

    // ── Branch independence ────────────────────────────────────────────────────

    @Test
    fun `branches are independent — no cross-branch predecessor edges`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).filter(f1).embroider(e2)
        val combined = a.combine(b)

        val aNodeIds = combined.nodes.take(a.nodes.size).map { it.id }.toSet()
        val bNodeIds = combined.nodes.drop(a.nodes.size).map { it.id }.toSet()

        // No a-node has a predecessor in b, and no b-node has a predecessor in a.
        assertAll(
            {
                assertTrue(
                    combined.nodes.filter { it.id in aNodeIds }
                        .all { node -> node.predecessors.none { it in bNodeIds } },
                    "a-branch nodes must not reference b-branch predecessors",
                )
            },
            {
                assertTrue(
                    combined.nodes.filter { it.id in bNodeIds }
                        .all { node -> node.predecessors.none { it in aNodeIds } },
                    "b-branch nodes must not reference a-branch predecessors",
                )
            },
        )
    }

    @Test
    fun `combined draft has two root nodes with no predecessors`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).map(m2)
        val combined = a.combine(b)
        val roots = combined.nodes.filter { it.predecessors.isEmpty() }
        assertEquals(2, roots.size, "combine of two single-source drafts must yield two roots")
    }

    // ── embroideries ──────────────────────────────────────────────────────────

    @Test
    fun `embroideries returns both embroiders from combined branches`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).filter(f1).embroider(e2)
        val combined = a.combine(b)
        assertAll(
            { assertEquals(2, combined.embroideries.size) },
            { assertTrue(combined.embroideries.any { it.opId == e1 }) },
            { assertTrue(combined.embroideries.any { it.opId == e2 }) },
        )
    }

    @Test
    fun `embroidery is null on a combined draft with two embroiders`() {
        val a = Warp.shuttle(s1).embroider(e1)
        val b = Warp.shuttle(s2).embroider(e2)
        val combined = a.combine(b)
        // singleOrNull must return null when there are two embroiders
        assertNull(combined.embroidery)
    }

    @Test
    fun `embroideries is empty when neither branch has an embroider`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).filter(f1)
        assertTrue(a.combine(b).embroideries.isEmpty())
    }

    @Test
    fun `embroideries has one entry when only one branch has an embroider`() {
        val a = Warp.shuttle(s1).embroider(e1)
        val b = Warp.shuttle(s2).map(m1)
        val combined = a.combine(b)
        assertAll(
            { assertEquals(1, combined.embroideries.size) },
            { assertEquals(e1, combined.embroideries.single().opId) },
        )
    }

    // ── isMonotone ─────────────────────────────────────────────────────────────

    @Test
    fun `isMonotone is false when either branch has an embroider`() {
        val a = Warp.shuttle(s1).embroider(e1)
        val b = Warp.shuttle(s2).map(m1)
        assertFalse(a.combine(b).isMonotone)
    }

    @Test
    fun `isMonotone is true when both branches are fully monotone`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).filter(f1)
        assertTrue(a.combine(b).isMonotone)
    }

    // ── stages view ────────────────────────────────────────────────────────────

    @Test
    fun `stages is consistent with nodes on a combined draft`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).filter(f1).embroider(e2)
        val combined = a.combine(b)
        assertEquals(combined.nodes.map { it.stage }, combined.stages)
    }

    // ── structural types of source nodes ──────────────────────────────────────

    @Test
    fun `both Source nodes are present and tagged correctly`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).map(m2)
        val combined = a.combine(b)
        val sourceStages = combined.nodes
            .filter { it.stage is DraftStage.Source }
            .map { it.stage as DraftStage.Source }
        assertAll(
            { assertEquals(2, sourceStages.size) },
            { assertTrue(sourceStages.any { it.opId == s1 }) },
            { assertTrue(sourceStages.any { it.opId == s2 }) },
        )
    }

    // ── combine of already-combined drafts ────────────────────────────────────

    @Test
    fun `combine of a combined draft with another draft yields unique node ids`() {
        val a = Warp.shuttle(s1).map(m1)
        val b = Warp.shuttle(s2).filter(f1)
        val ab = a.combine(b)
        val c = Warp.shuttle(OpId("source.extra")).embroider(e1)
        val abc = ab.combine(c)
        val ids = abc.nodes.map { it.id }
        assertEquals(ids.distinct(), ids, "node ids must be unique after chained combines")
    }

    // ── structural test: Source node has no predecessors ──────────────────────

    @Test
    fun `each branch source node has no predecessors in the combined draft`() {
        val a = Warp.shuttle(s1).map(m1).embroider(e1)
        val b = Warp.shuttle(s2).map(m2).embroider(e2)
        val combined = a.combine(b)
        val sourceNodes = combined.nodes.filter { it.stage is DraftStage.Source }
        assertAll(
            { assertEquals(2, sourceNodes.size) },
            { assertTrue(sourceNodes.all { it.predecessors.isEmpty() }) },
        )
    }
}
