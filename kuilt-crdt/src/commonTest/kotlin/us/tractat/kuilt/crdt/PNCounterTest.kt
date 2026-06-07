package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PNCounterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun zeroHasValueZero() {
        assertEquals(0L, PNCounter.ZERO.value)
    }

    @Test
    fun incrementRaisesValue() {
        val pn = PNCounter.ZERO
        val next = pn.piece(pn.increment(a, 3L))
        assertEquals(3L, next.value)
    }

    @Test
    fun decrementLowersValue() {
        val pn = PNCounter.ZERO.piece(PNCounter.ZERO.increment(a, 5L))
        val next = pn.piece(pn.decrement(a, 2L))
        assertEquals(3L, next.value)
    }

    @Test
    fun incrementDefaultsToOne() {
        val pn = PNCounter.ZERO
        assertEquals(1L, pn.piece(pn.increment(a)).value)
    }

    @Test
    fun decrementDefaultsToOne() {
        val pn = PNCounter.ZERO.piece(PNCounter.ZERO.increment(a, 3L))
        assertEquals(2L, pn.piece(pn.decrement(a)).value)
    }

    @Test
    fun incrementMustBePositive() {
        assertFailsWith<IllegalArgumentException> { PNCounter.ZERO.increment(a, 0L) }
    }

    @Test
    fun decrementMustBePositive() {
        assertFailsWith<IllegalArgumentException> { PNCounter.ZERO.decrement(a, 0L) }
    }

    @Test
    fun valueCanGoNegative() {
        // The dec GCounter is independent — value = inc - dec, no floor at zero.
        val pn = PNCounter.ZERO.piece(PNCounter.ZERO.decrement(a, 5L))
        assertEquals(-5L, pn.value)
    }

    @Test
    fun concurrentIncAndDecFromDifferentReplicasMerge() {
        val zero = PNCounter.ZERO
        val aInc = zero.piece(zero.increment(a, 10L))
        val bDec = zero.piece(zero.decrement(b, 3L))
        // Both sides merge; value = 10 - 3 = 7
        val merged = aInc.piece(bDec)
        assertEquals(7L, merged.value)
    }

    @Test
    fun pieceIsIdempotentForCounters() {
        val pn = PNCounter.ZERO.piece(PNCounter.ZERO.increment(a, 2L))
        assertEquals(pn, pn.piece(pn))
    }

    @Test
    fun roundTripsThroughJson() {
        val zero = PNCounter.ZERO
        val pn = zero.piece(zero.increment(a, 4L)).piece(PNCounter.ZERO.decrement(b, 1L))
        val encoded = Json.encodeToString(PNCounter.serializer(), pn)
        assertEquals(pn, Json.decodeFromString(PNCounter.serializer(), encoded))
    }
}
