package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotContextTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyContainsNothing() {
        assertFalse(DotContext.EMPTY.contains(Dot(a, 1L)))
    }

    @Test
    fun addThenContains() {
        val ctx = DotContext.EMPTY.add(Dot(a, 1L))
        assertTrue(ctx.contains(Dot(a, 1L)))
        assertFalse(ctx.contains(Dot(a, 2L)))
    }

    @Test
    fun nextDotMintsTheNextSeq() {
        assertEquals(Dot(a, 1L), DotContext.EMPTY.nextDot(a))
        assertEquals(Dot(a, 2L), DotContext.of(Dot(a, 1L)).nextDot(a))
    }

    @Test
    fun contiguousDotsCompactRegardlessOfOrder() {
        val forward = DotContext.EMPTY.add(Dot(a, 1L)).add(Dot(a, 2L))
        val backward = DotContext.EMPTY.add(Dot(a, 2L)).add(Dot(a, 1L))
        assertEquals(forward, backward)
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), forward)
    }

    @Test
    fun gapStaysInCloudUntilFilled() {
        val withGap = DotContext.EMPTY.add(Dot(a, 3L)) // missing (A,1),(A,2)
        assertTrue(withGap.contains(Dot(a, 3L)))
        assertFalse(withGap.contains(Dot(a, 2L)))
        assertEquals(Dot(a, 1L), withGap.nextDot(a))
        val filled = withGap.add(Dot(a, 1L)).add(Dot(a, 2L))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)), filled)
        assertTrue(filled.contains(Dot(a, 2L)))
    }

    @Test
    fun addIsIdempotent() {
        val once = DotContext.EMPTY.add(Dot(a, 1L))
        assertEquals(once, once.add(Dot(a, 1L)))
    }

    @Test
    fun pieceUnionsHistories() {
        val left = DotContext.of(Dot(a, 1L), Dot(a, 2L))
        val right = DotContext.of(Dot(b, 1L))
        val merged = left.piece(right)
        assertTrue(merged.contains(Dot(a, 2L)))
        assertTrue(merged.contains(Dot(b, 1L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 1L)), merged)
    }

    @Test
    fun pieceCompactsAcrossOperands() {
        val merged = DotContext.of(Dot(a, 1L)).piece(DotContext.of(Dot(a, 2L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), merged)
    }

    @Test
    fun roundTripsThroughJson() {
        val ctx = DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 4L))
        val encoded = Json.encodeToString(DotContext.serializer(), ctx)
        assertEquals(ctx, Json.decodeFromString(DotContext.serializer(), encoded))
    }
}
