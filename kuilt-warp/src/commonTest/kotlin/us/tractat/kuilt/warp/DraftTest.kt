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
 * Tests for [Draft] — the immutable, inspectable dataflow graph.
 *
 * Property verified: building and inspecting a [Draft] NEVER executes any op.
 * A [Draft] stores only symbolic [OpId]s; it has no mechanism to invoke an [Op].
 */
class DraftTest {

    private val sourceId = OpId("source.docs")
    private val mapId = OpId("map.score")
    private val filterId = OpId("filter.nonzero")
    private val embroiderOpId = OpId("embroider.consensus")

    // ── Builder captures stages ────────────────────────────────────────────────

    @Test
    fun `shuttle starts with a single Source stage`() {
        val draft = Warp.shuttle(sourceId)
        assertAll(
            { assertEquals(1, draft.stages.size) },
            { assertIs<DraftStage.Source>(draft.stages[0]) },
            { assertEquals(sourceId, draft.stages[0].opId) },
        )
    }

    @Test
    fun `stages are captured in builder call order`() {
        val draft = Warp.shuttle(sourceId).map(mapId).filter(filterId)
        assertAll(
            { assertEquals(3, draft.stages.size) },
            { assertIs<DraftStage.Source>(draft.stages[0]) },
            { assertIs<DraftStage.Map>(draft.stages[1]) },
            { assertIs<DraftStage.Filter>(draft.stages[2]) },
            { assertEquals(sourceId, draft.stages[0].opId) },
            { assertEquals(mapId, draft.stages[1].opId) },
            { assertEquals(filterId, draft.stages[2].opId) },
        )
    }

    @Test
    fun `embroider stage is appended after free stages`() {
        val draft = Warp.shuttle(sourceId).map(mapId).embroider(embroiderOpId)
        assertAll(
            { assertEquals(3, draft.stages.size) },
            { assertIs<DraftStage.Source>(draft.stages[0]) },
            { assertIs<DraftStage.Map>(draft.stages[1]) },
            { assertIs<DraftStage.Embroider>(draft.stages[2]) },
            { assertEquals(embroiderOpId, draft.stages[2].opId) },
        )
    }

    // ── Nothing executes during build or inspection ────────────────────────────

    @Test
    fun `nothing executes when building or inspecting a Draft`() {
        // OpId is symbolic — building and inspecting a Draft never invokes any Op.
        // An op named "would.throw.if.invoked" cannot run: the Draft stores only its name.
        val throwIfInvokedId = OpId("would.throw.if.invoked")

        val draft = Warp.shuttle(throwIfInvokedId)
            .map(throwIfInvokedId)
            .filter(throwIfInvokedId)
            .embroider(throwIfInvokedId)

        // Full inspection — nothing throws
        assertAll(
            { assertEquals(4, draft.stages.size) },
            { assertFalse(draft.isMonotone) },
            { assertNotNull(draft.embroidery) },
        )
    }

    // ── isMonotone ─────────────────────────────────────────────────────────────

    @Test
    fun `isMonotone is true when all stages are Free`() {
        val draft = Warp.shuttle(sourceId).map(mapId).filter(filterId)
        assertTrue(draft.isMonotone)
    }

    @Test
    fun `isMonotone is true for a source-only Draft`() {
        assertTrue(Warp.shuttle(sourceId).isMonotone)
    }

    @Test
    fun `isMonotone is false when an Embroider stage is present`() {
        val draft = Warp.shuttle(sourceId).map(mapId).embroider(embroiderOpId)
        assertFalse(draft.isMonotone)
    }

    // ── embroidery locator ─────────────────────────────────────────────────────

    @Test
    fun `embroidery is null when no Coordinated stage is present`() {
        val draft = Warp.shuttle(sourceId).map(mapId).filter(filterId)
        assertNull(draft.embroidery)
    }

    @Test
    fun `embroidery returns the Embroider stage`() {
        val draft = Warp.shuttle(sourceId).map(mapId).embroider(embroiderOpId)
        assertAll(
            { assertIs<DraftStage.Embroider>(draft.embroidery) },
            { assertEquals(embroiderOpId, draft.embroidery?.opId) },
        )
    }

    @Test
    fun `embroidery is null on a source-only Draft`() {
        assertNull(Warp.shuttle(sourceId).embroidery)
    }

    // ── CoordinationKind tagging ───────────────────────────────────────────────

    @Test
    fun `Source stage is tagged Free`() {
        val draft = Warp.shuttle(sourceId)
        assertEquals(CoordinationKind.Free, draft.stages.single().coordinationKind)
    }

    @Test
    fun `Map stage is tagged Free`() {
        val draft = Warp.shuttle(sourceId).map(mapId)
        assertEquals(CoordinationKind.Free, draft.stages.last().coordinationKind)
    }

    @Test
    fun `Filter stage is tagged Free`() {
        val draft = Warp.shuttle(sourceId).filter(filterId)
        assertEquals(CoordinationKind.Free, draft.stages.last().coordinationKind)
    }

    @Test
    fun `Embroider stage is tagged Coordinated`() {
        val draft = Warp.shuttle(sourceId).embroider(embroiderOpId)
        assertEquals(CoordinationKind.Coordinated, draft.stages.last().coordinationKind)
    }

    @Test
    fun `all stages before embroider are Free`() {
        val draft = Warp.shuttle(sourceId).map(mapId).filter(filterId).embroider(embroiderOpId)
        val freeStages = draft.stages.dropLast(1)
        assertTrue(freeStages.all { it.coordinationKind == CoordinationKind.Free })
    }
}
