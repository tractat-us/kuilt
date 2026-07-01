package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [Draft.consolidateEmbroideries] — the G3 round-consolidation rewrite.
 *
 * Theory: min coordination rounds = depth of the coordination dependency DAG.
 * Independent [DraftStage.Embroider] nodes at the same dependency level can be
 * batched into a single [DraftStage.BatchedEmbroider] (one consensus round);
 * a dependency chain forces one round per level.
 *
 * Coverage:
 * - Two independent embroideries at the same level → one [DraftStage.BatchedEmbroider].
 * - A dependency chain → stays separate (two levels, each with one node).
 * - Mixed: three independent (level 0) + one dependent (level 1) → BatchedEmbroider(3) + Embroider.
 * - Result-preservation: [Draft.isEquivalentTo] holds; non-coordinated structure intact.
 * - Idempotence: consolidating twice produces the same result as consolidating once.
 * - Single-embroider path → no change (prior G1/G2 guarantees preserved).
 */
class ConsolidateEmbroideriesTest {

    private val s1 = OpId("source.docs")
    private val s2 = OpId("source.scores")
    private val s3 = OpId("source.meta")
    private val s4 = OpId("source.events")
    private val m1 = OpId("map.score")
    private val f1 = OpId("filter.nonzero")
    private val e1 = OpId("embroider.rank")
    private val e2 = OpId("embroider.vote")
    private val e3 = OpId("embroider.commit")
    private val e4 = OpId("embroider.apply")

    // ── Two independent embroideries → one BatchedEmbroider ────────────────────

    @Test
    fun `two independent embroideries at same level become one BatchedEmbroider`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
        val result = combined.consolidateEmbroideries()

        val coordinated = result.nodes.filter {
            it.stage.coordinationKind == CoordinationKind.Coordinated
        }
        assertAll(
            { assertEquals(1, coordinated.size, "must have exactly one coordinated node") },
            { assertIs<DraftStage.BatchedEmbroider>(coordinated.single().stage) },
        )

        val batched = coordinated.single().stage as DraftStage.BatchedEmbroider
        assertEquals(setOf(e1, e2), batched.opIds.toSet())
    }

    @Test
    fun `batched node representative opId is the first opId`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
        val result = combined.consolidateEmbroideries()
        val batched = result.nodes
            .map { it.stage }
            .filterIsInstance<DraftStage.BatchedEmbroider>()
            .single()

        assertEquals(batched.opIds.first(), batched.opId)
    }

    // ── Dependency chain → two levels, no consolidation ───────────────────────

    @Test
    fun `dependency chain keeps two separate coordinated nodes`() {
        // Embroider(e1) → Map → Embroider(e2): e2 has e1 as ancestor → two levels
        val chained = Warp.shuttle(s1).embroider(e1).map(m1).embroider(e2)
        val result = chained.consolidateEmbroideries()

        val coordinated = result.nodes.filter {
            it.stage.coordinationKind == CoordinationKind.Coordinated
        }
        assertAll(
            { assertEquals(2, coordinated.size, "dependency chain must stay as two separate nodes") },
            {
                assertTrue(
                    coordinated.all { it.stage is DraftStage.Embroider },
                    "neither node should be fused into a BatchedEmbroider",
                )
            },
        )
    }

    @Test
    fun `direct chain with no intermediate free stage stays separate`() {
        // Embroider(e1) → Embroider(e2): e2 directly depends on e1 → two levels
        val chained = Warp.shuttle(s1).embroider(e1).embroider(e2)
        val result = chained.consolidateEmbroideries()

        assertEquals(2, result.nodes.count { it.stage.coordinationKind == CoordinationKind.Coordinated })
    }

    // ── Mixed: level 0 (×3) + level 1 (×1) ───────────────────────────────────

    @Test
    fun `three independent plus one dependent yields BatchedEmbroider of three and one separate`() {
        // branch1: s1 → e1 (level 0)
        // branch2: s2 → e2 (level 0)
        // dep:     s3 → e3 (level 0) → e4 (level 1)
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
            .combine(Warp.shuttle(s3).embroider(e3).embroider(e4))

        val result = combined.consolidateEmbroideries()
        val coordinated = result.nodes.filter {
            it.stage.coordinationKind == CoordinationKind.Coordinated
        }

        assertAll(
            { assertEquals(2, coordinated.size, "must have exactly two coordinated nodes") },
            {
                val batched = coordinated.filterIsInstance2()
                assertEquals(1, batched.size, "exactly one BatchedEmbroider for level 0")
                assertEquals(setOf(e1, e2, e3), batched.single().opIds.toSet())
            },
            {
                val singles = coordinated.filter { it.stage is DraftStage.Embroider }
                assertEquals(1, singles.size, "exactly one separate Embroider for level 1")
                assertEquals(e4, singles.single().stage.opId)
            },
        )
    }

    @Test
    fun `level 1 node depends on the fused level 0 node in the result`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
            .combine(Warp.shuttle(s3).embroider(e3).embroider(e4))
        val result = combined.consolidateEmbroideries()

        val batchedId = result.nodes
            .first { it.stage is DraftStage.BatchedEmbroider }.id
        val levelOneNode = result.nodes
            .first { it.stage is DraftStage.Embroider && it.stage.opId == e4 }

        // The level-1 node's predecessor chain must reach the batched node
        assertTrue(
            levelOneNode.transitiveAncestors(result.nodes).contains(batchedId),
            "level-1 Embroider must transitively depend on the fused BatchedEmbroider",
        )
    }

    // ── Result-preservation ────────────────────────────────────────────────────

    @Test
    fun `consolidate is equivalent to original`() {
        val original = Warp.shuttle(s1).map(m1).embroider(e1)
            .combine(Warp.shuttle(s2).filter(f1).embroider(e2))
        val result = original.consolidateEmbroideries()
        assertTrue(result.isEquivalentTo(original))
    }

    @Test
    fun `non-coordinated nodes and edges are preserved`() {
        val original = Warp.shuttle(s1).map(m1).embroider(e1)
            .combine(Warp.shuttle(s2).filter(f1).embroider(e2))
        val result = original.consolidateEmbroideries()

        // Free-stage opIds are preserved
        val originalFreeOps = original.nodes
            .filter { it.stage.coordinationKind == CoordinationKind.Free }
            .flatMap { it.stage.allOpIds() }
            .map { it.value }
            .sorted()
        val resultFreeOps = result.nodes
            .filter { it.stage.coordinationKind == CoordinationKind.Free }
            .flatMap { it.stage.allOpIds() }
            .map { it.value }
            .sorted()

        assertEquals(originalFreeOps, resultFreeOps)
    }

    // ── Idempotence ────────────────────────────────────────────────────────────

    @Test
    fun `consolidating twice produces same stages as consolidating once`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
        val once = combined.consolidateEmbroideries()
        val twice = once.consolidateEmbroideries()
        assertEquals(once.stages, twice.stages)
    }

    @Test
    fun `consolidating twice is equivalent to the original`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
        val twice = combined.consolidateEmbroideries().consolidateEmbroideries()
        assertTrue(twice.isEquivalentTo(combined))
    }

    // ── Single-embroider path → unchanged ──────────────────────────────────────

    @Test
    fun `single embroider path is unchanged`() {
        val draft = Warp.shuttle(s1).map(m1).filter(f1).embroider(e1)
        val result = draft.consolidateEmbroideries()
        assertEquals(draft.stages, result.stages)
    }

    @Test
    fun `monotone path with no embroider is unchanged`() {
        val draft = Warp.shuttle(s1).map(m1).filter(f1)
        val result = draft.consolidateEmbroideries()
        assertEquals(draft.stages, result.stages)
    }

    // ── BatchedEmbroider is CoordinationKind.Coordinated ──────────────────────

    @Test
    fun `BatchedEmbroider has CoordinationKind Coordinated`() {
        val stage = DraftStage.BatchedEmbroider(listOf(e1, e2))
        assertEquals(CoordinationKind.Coordinated, stage.coordinationKind)
    }

    @Test
    fun `BatchedEmbroider requires non-empty opIds`() {
        var threw = false
        try {
            DraftStage.BatchedEmbroider(emptyList())
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "BatchedEmbroider must require at least one opId")
    }

    // ── optimize() includes consolidateEmbroideries ────────────────────────────

    @Test
    fun `optimize consolidates independent embroideries`() {
        val combined = Warp.shuttle(s1).embroider(e1)
            .combine(Warp.shuttle(s2).embroider(e2))
        val optimized = combined.optimize()
        assertTrue(
            optimized.nodes.any { it.stage is DraftStage.BatchedEmbroider },
            "optimize must include consolidation",
        )
    }

    @Test
    fun `optimize result is equivalent to input`() {
        val combined = Warp.shuttle(s1).map(m1).embroider(e1)
            .combine(Warp.shuttle(s2).filter(f1).embroider(e2))
        val optimized = combined.optimize()
        assertTrue(optimized.isEquivalentTo(combined))
    }
}

// ── Private test helpers ──────────────────────────────────────────────────────

private fun List<DraftNode>.filterIsInstance2(): List<DraftStage.BatchedEmbroider> =
    mapNotNull { it.stage as? DraftStage.BatchedEmbroider }

private fun DraftNode.transitiveAncestors(allNodes: List<DraftNode>): Set<NodeId> {
    val byId = allNodes.associateBy { it.id }
    val visited = mutableSetOf<NodeId>()
    val queue = ArrayDeque(predecessors.toList())
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        byId[id]?.predecessors?.forEach { queue.add(it) }
    }
    return visited
}
