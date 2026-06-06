package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GCounterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun zeroHasValueZero() {
        assertEquals(0L, GCounter.ZERO.value)
    }

    @Test
    fun valueSumsAcrossReplicas() {
        assertEquals(7L, GCounter.of(a to 2L, b to 5L).value)
    }

    @Test
    fun incProducesADeltaThatRaisesTheCount() {
        val gc = GCounter.ZERO
        val delta = gc.inc(a, 3L)
        val next = gc.piece(delta)
        assertEquals(3L, next.value)
        assertEquals(3L, next.count(a))
    }

    @Test
    fun incDefaultsToOne() {
        assertEquals(1L, GCounter.ZERO.let { it.piece(it.inc(a)) }.value)
    }

    @Test
    fun incMustBePositive() {
        assertFailsWith<IllegalArgumentException> { GCounter.ZERO.inc(a, 0L) }
    }

    @Test
    fun pieceTakesElementwiseMaxNotSum() {
        // each replica owns its own slot; merge is max, so concurrent bumps to
        // DIFFERENT slots both count, but the same slot does not double-count
        assertEquals(
            GCounter.of(a to 2L, b to 3L),
            GCounter.of(a to 2L, b to 1L).piece(GCounter.of(a to 1L, b to 3L)),
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val gc = GCounter.of(a to 2L, b to 5L)
        val encoded = Json.encodeToString(GCounter.serializer(), gc)
        assertEquals(gc, Json.decodeFromString(GCounter.serializer(), encoded))
    }
}
