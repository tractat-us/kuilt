package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the E-2 rewrite rules on [Draft] pipelines.
 *
 * Three families are covered:
 * - [Draft.deferEmbroidery] — floats the single [DraftStage.Embroider] stage to be last
 * - [Draft.pushdownFilters] — moves [DraftStage.Filter] stages earlier, ahead of [DraftStage.Map]
 * - [Draft.fuseAdjacent] — merges adjacent same-kind monotone stages into fused stages
 * - [Draft.optimize] — applies all three to a fixpoint
 *
 * Correctness of every rewrite is verified against a structural equivalence predicate:
 * [Draft.isEquivalentTo]. Two drafts are equivalent when they share the same source opId,
 * the same embroider opId (or both lack one), and the same multiset of free-stage opIds
 * (after flattening any fused stages). This characterises semantic preservation under
 * CALM monotone commutativity without needing an execution engine (which arrives in E-5).
 */
class DraftRewriteTest {

    private val src = OpId("source")
    private val m1 = OpId("map.score")
    private val m2 = OpId("map.normalise")
    private val m3 = OpId("map.enrich")
    private val f1 = OpId("filter.nonzero")
    private val f2 = OpId("filter.recent")
    private val emb = OpId("embroider.rank")

    // ── deferEmbroidery ────────────────────────────────────────────────────────

    @Test
    fun `deferEmbroidery is a no-op when embroider is already last`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1).embroider(emb)
        val rewritten = draft.deferEmbroidery()
        assertEquals(draft.stages, rewritten.stages)
    }

    @Test
    fun `deferEmbroidery moves embroider to end when Free stages follow it`() {
        // Hand-build a draft with embroider in the middle (not via the builder, which always appends)
        val stages = listOf(
            DraftStage.Source(src),
            DraftStage.Map(m1),
            DraftStage.Embroider(emb),
            DraftStage.Filter(f1),
            DraftStage.Map(m2),
        )
        val draft = Draft<ByteArray>(stages.toPathNodes())
        val rewritten = draft.deferEmbroidery()

        assertAll(
            { assertIs<DraftStage.Source>(rewritten.stages[0]) },
            { assertIs<DraftStage.Map>(rewritten.stages[1]) },
            { assertIs<DraftStage.Filter>(rewritten.stages[2]) },
            { assertIs<DraftStage.Map>(rewritten.stages[3]) },
            { assertIs<DraftStage.Embroider>(rewritten.stages[4]) },
            { assertEquals(emb, rewritten.stages[4].opId) },
        )
    }

    @Test
    fun `deferEmbroidery is a no-op when no embroider is present`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1)
        assertEquals(draft.stages, draft.deferEmbroidery().stages)
    }

    @Test
    fun `deferEmbroidery never invents or drops the embroider`() {
        // With an embroider: it must still be present after rewrite
        val withEmb = Draft<ByteArray>(
            listOf(DraftStage.Source(src), DraftStage.Embroider(emb), DraftStage.Map(m1)).toPathNodes()
        )
        val rewritten = withEmb.deferEmbroidery()
        assertAll(
            { assertEquals(1, rewritten.stages.filterIsInstance<DraftStage.Embroider>().size) },
            { assertEquals(emb, rewritten.embroidery?.opId) },
        )
    }

    @Test
    fun `deferEmbroidery never changes the source`() {
        val draft = Draft<ByteArray>(
            listOf(DraftStage.Source(src), DraftStage.Embroider(emb), DraftStage.Map(m1)).toPathNodes()
        )
        val rewritten = draft.deferEmbroidery()
        assertAll(
            { assertIs<DraftStage.Source>(rewritten.stages.first()) },
            { assertEquals(src, rewritten.stages.first().opId) },
        )
    }

    @Test
    fun `deferEmbroidery preserves equivalence`() {
        val draft = Draft<ByteArray>(
            listOf(
                DraftStage.Source(src),
                DraftStage.Embroider(emb),
                DraftStage.Map(m1),
                DraftStage.Filter(f1),
            ).toPathNodes()
        )
        assertTrue(draft.isEquivalentTo(draft.deferEmbroidery()))
    }

    // ── pushdownFilters ────────────────────────────────────────────────────────

    @Test
    fun `pushdownFilters moves Filter stages before Map stages`() {
        // map → filter → map → filter  should become  filter → filter → map → map
        val draft = Warp.shuttle(src).map(m1).filter(f1).map(m2).filter(f2)
        val rewritten = draft.pushdownFilters()

        val middleStages = rewritten.stages.drop(1)  // skip Source
        assertAll(
            { assertIs<DraftStage.Filter>(middleStages[0]) },
            { assertIs<DraftStage.Filter>(middleStages[1]) },
            { assertIs<DraftStage.Map>(middleStages[2]) },
            { assertIs<DraftStage.Map>(middleStages[3]) },
        )
    }

    @Test
    fun `pushdownFilters is a no-op when filters already precede maps`() {
        val draft = Warp.shuttle(src).filter(f1).filter(f2).map(m1).map(m2)
        assertEquals(draft.stages, draft.pushdownFilters().stages)
    }

    @Test
    fun `pushdownFilters keeps source first`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1)
        val rewritten = draft.pushdownFilters()
        assertAll(
            { assertIs<DraftStage.Source>(rewritten.stages.first()) },
            { assertEquals(src, rewritten.stages.first().opId) },
        )
    }

    @Test
    fun `pushdownFilters keeps embroider last`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1).embroider(emb)
        val rewritten = draft.pushdownFilters()
        assertAll(
            { assertIs<DraftStage.Embroider>(rewritten.stages.last()) },
            { assertEquals(emb, rewritten.stages.last().opId) },
        )
    }

    @Test
    fun `pushdownFilters is a no-op when no embroider is present and only maps exist`() {
        val draft = Warp.shuttle(src).map(m1).map(m2)
        assertEquals(draft.stages, draft.pushdownFilters().stages)
    }

    @Test
    fun `pushdownFilters preserves equivalence`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1).map(m2).filter(f2).embroider(emb)
        assertTrue(draft.isEquivalentTo(draft.pushdownFilters()))
    }

    @Test
    fun `pushdownFilters never changes the source opId`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1)
        val rewritten = draft.pushdownFilters()
        assertEquals(src, (rewritten.stages.first() as DraftStage.Source).opId)
    }

    @Test
    fun `pushdownFilters never invents or drops the embroider`() {
        // With embroider
        val withEmb = Warp.shuttle(src).map(m1).filter(f1).embroider(emb)
        assertEquals(emb, withEmb.pushdownFilters().embroidery?.opId)

        // Without embroider
        val withoutEmb = Warp.shuttle(src).map(m1).filter(f1)
        assertNull(withoutEmb.pushdownFilters().embroidery)
    }

    // ── fuseAdjacent ──────────────────────────────────────────────────────────

    @Test
    fun `fuseAdjacent merges adjacent Map stages into FusedMap`() {
        val draft = Warp.shuttle(src).map(m1).map(m2)
        val rewritten = draft.fuseAdjacent()

        assertAll(
            { assertEquals(2, rewritten.stages.size) },
            { assertIs<DraftStage.Source>(rewritten.stages[0]) },
            { assertIs<DraftStage.FusedMap>(rewritten.stages[1]) },
            { assertEquals(listOf(m1, m2), (rewritten.stages[1] as DraftStage.FusedMap).opIds) },
        )
    }

    @Test
    fun `fuseAdjacent merges adjacent Filter stages into FusedFilter`() {
        val draft = Warp.shuttle(src).filter(f1).filter(f2)
        val rewritten = draft.fuseAdjacent()

        assertAll(
            { assertEquals(2, rewritten.stages.size) },
            { assertIs<DraftStage.Source>(rewritten.stages[0]) },
            { assertIs<DraftStage.FusedFilter>(rewritten.stages[1]) },
            { assertEquals(listOf(f1, f2), (rewritten.stages[1] as DraftStage.FusedFilter).opIds) },
        )
    }

    @Test
    fun `fuseAdjacent merges three adjacent Map stages into one FusedMap`() {
        val draft = Warp.shuttle(src).map(m1).map(m2).map(m3)
        val rewritten = draft.fuseAdjacent()

        assertAll(
            { assertEquals(2, rewritten.stages.size) },
            { assertIs<DraftStage.FusedMap>(rewritten.stages[1]) },
            { assertEquals(listOf(m1, m2, m3), (rewritten.stages[1] as DraftStage.FusedMap).opIds) },
        )
    }

    @Test
    fun `fuseAdjacent does not fuse non-adjacent same-kind stages`() {
        // map → filter → map: the two maps are not adjacent, so they should not fuse
        val draft = Warp.shuttle(src).map(m1).filter(f1).map(m2)
        val rewritten = draft.fuseAdjacent()

        assertAll(
            { assertEquals(4, rewritten.stages.size) },
            { assertIs<DraftStage.Source>(rewritten.stages[0]) },
            { assertIs<DraftStage.Map>(rewritten.stages[1]) },
            { assertIs<DraftStage.Filter>(rewritten.stages[2]) },
            { assertIs<DraftStage.Map>(rewritten.stages[3]) },
        )
    }

    @Test
    fun `fuseAdjacent does not fuse Source or Embroider stages`() {
        // A Source followed by a second Source-like or Embroider should never fuse
        val draft = Warp.shuttle(src).map(m1).embroider(emb)
        val rewritten = draft.fuseAdjacent()

        assertAll(
            { assertIs<DraftStage.Source>(rewritten.stages[0]) },
            { assertIs<DraftStage.Map>(rewritten.stages[1]) },
            { assertIs<DraftStage.Embroider>(rewritten.stages[2]) },
        )
    }

    @Test
    fun `fuseAdjacent is a no-op on a single-stage pipeline`() {
        val draft = Warp.shuttle(src)
        assertEquals(draft.stages, draft.fuseAdjacent().stages)
    }

    @Test
    fun `fuseAdjacent FusedMap reports CoordinationKind Free`() {
        val draft = Warp.shuttle(src).map(m1).map(m2)
        val fused = draft.fuseAdjacent().stages[1]
        assertEquals(CoordinationKind.Free, fused.coordinationKind)
    }

    @Test
    fun `fuseAdjacent FusedFilter reports CoordinationKind Free`() {
        val draft = Warp.shuttle(src).filter(f1).filter(f2)
        val fused = draft.fuseAdjacent().stages[1]
        assertEquals(CoordinationKind.Free, fused.coordinationKind)
    }

    @Test
    fun `fuseAdjacent preserves equivalence`() {
        val draft = Warp.shuttle(src).map(m1).map(m2).filter(f1).filter(f2).embroider(emb)
        assertTrue(draft.isEquivalentTo(draft.fuseAdjacent()))
    }

    @Test
    fun `fuseAdjacent never invents or drops the embroider`() {
        // With embroider
        val withEmb = Warp.shuttle(src).map(m1).map(m2).embroider(emb)
        assertAll(
            { assertNotNull(withEmb.fuseAdjacent().embroidery) },
            { assertEquals(emb, withEmb.fuseAdjacent().embroidery?.opId) },
        )

        // Without embroider
        assertNull(Warp.shuttle(src).map(m1).map(m2).fuseAdjacent().embroidery)
    }

    @Test
    fun `fuseAdjacent never changes the source`() {
        val draft = Warp.shuttle(src).map(m1).map(m2)
        val rewritten = draft.fuseAdjacent()
        assertAll(
            { assertIs<DraftStage.Source>(rewritten.stages.first()) },
            { assertEquals(src, rewritten.stages.first().opId) },
        )
    }

    // ── fuseAdjacent — fused stage round-trips ─────────────────────────────────

    @Test
    fun `FusedMap opId returns the first fused opId`() {
        val fused = DraftStage.FusedMap(listOf(m1, m2, m3))
        assertEquals(m1, fused.opId)
    }

    @Test
    fun `FusedFilter opId returns the first fused opId`() {
        val fused = DraftStage.FusedFilter(listOf(f1, f2))
        assertEquals(f1, fused.opId)
    }

    // ── optimize ──────────────────────────────────────────────────────────────

    @Test
    fun `optimize applies all three rules`() {
        // Worst-case ordering: embroider in the middle, filters after maps, duplicates adjacent
        val stages = listOf(
            DraftStage.Source(src),
            DraftStage.Map(m1),
            DraftStage.Filter(f1),
            DraftStage.Embroider(emb),
            DraftStage.Map(m2),
            DraftStage.Filter(f2),
            DraftStage.Map(m3),
        )
        val draft = Draft<ByteArray>(stages.toPathNodes())
        val optimised = draft.optimize()

        assertAll(
            // Source is first
            { assertIs<DraftStage.Source>(optimised.stages.first()) },
            // Embroider (if present) is last
            { assertIs<DraftStage.Embroider>(optimised.stages.last()) },
            // Filters precede maps in the middle
            { assertTrue(optimised.stages.drop(1).dropLast(1).indexOfFirst { it is DraftStage.Filter || it is DraftStage.FusedFilter } < optimised.stages.drop(1).dropLast(1).indexOfLast { it is DraftStage.Map || it is DraftStage.FusedMap }) },
            // Preserved equivalence
            { assertTrue(draft.isEquivalentTo(optimised)) },
        )
    }

    @Test
    fun `optimize is idempotent — optimising an already-optimal Draft is a no-op`() {
        // Build an already-optimal draft: source, filters first, then maps, then embroider
        val draft = Warp.shuttle(src).filter(f1).map(m1).embroider(emb)
        val once = draft.optimize()
        val twice = once.optimize()
        assertEquals(once.stages, twice.stages)
    }

    @Test
    fun `optimize never invents or drops embroider`() {
        val withEmb = Warp.shuttle(src).map(m1).embroider(emb)
        assertAll(
            { assertNotNull(withEmb.optimize().embroidery) },
            { assertEquals(emb, withEmb.optimize().embroidery?.opId) },
        )

        assertNull(Warp.shuttle(src).map(m1).optimize().embroidery)
    }

    @Test
    fun `optimize never changes the source`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1).embroider(emb)
        val optimised = draft.optimize()
        assertAll(
            { assertIs<DraftStage.Source>(optimised.stages.first()) },
            { assertEquals(src, optimised.stages.first().opId) },
        )
    }

    @Test
    fun `optimize preserves equivalence across a complex pipeline`() {
        val draft = Warp.shuttle(src)
            .map(m1)
            .map(m2)
            .filter(f1)
            .map(m3)
            .filter(f2)
            .embroider(emb)
        assertTrue(draft.isEquivalentTo(draft.optimize()))
    }

    // ── Structural equivalence ─────────────────────────────────────────────────

    @Test
    fun `isEquivalentTo is reflexive`() {
        val draft = Warp.shuttle(src).map(m1).filter(f1).embroider(emb)
        assertTrue(draft.isEquivalentTo(draft))
    }

    @Test
    fun `isEquivalentTo detects a different source`() {
        val a = Warp.shuttle(src).map(m1)
        val b = Warp.shuttle(OpId("other.source")).map(m1)
        assertFalse(a.isEquivalentTo(b))
    }

    @Test
    fun `isEquivalentTo detects a different embroider`() {
        val a = Warp.shuttle(src).map(m1).embroider(emb)
        val b = Warp.shuttle(src).map(m1).embroider(OpId("other.embroider"))
        assertFalse(a.isEquivalentTo(b))
    }

    @Test
    fun `isEquivalentTo detects an added free stage`() {
        val a = Warp.shuttle(src).map(m1)
        val b = Warp.shuttle(src).map(m1).map(m2)
        assertFalse(a.isEquivalentTo(b))
    }
}
