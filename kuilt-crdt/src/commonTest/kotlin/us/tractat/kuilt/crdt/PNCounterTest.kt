package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    /**
     * The defining invariant, over states reached by an interleaved run of increments and
     * decrements across three replicas: [PNCounter.value] is the total incremented minus the
     * total decremented, with no floor and no clamping anywhere in between.
     *
     * Re-homed from the deleted JVM-only jqwik surface (#2101) so it runs on every target. The
     * two are computed separately — `value` from the exact 128-bit net (#3318), the totals each
     * from their own half — which is exactly what makes it worth pinning: the day either side
     * grows a cache, a compaction, or a reset the two definitions can part company silently.
     */
    @Test
    fun valueEqualsIncMinusDec() {
        val replicas = listOf(a, b, ReplicaId("C"))
        for (seed in 0 until VALUE_INVARIANT_SEEDS) {
            val random = Random(seed)
            val deltas = List(random.nextInt(0, MAX_OPS + 1)) { random.nextInt(0, MAX_DELTA).toLong() }
            var acc = PNCounter.ZERO
            deltas.forEachIndexed { i, delta ->
                val replica = replicas[i % replicas.size]
                acc =
                    if (i % 2 == 0) acc.piece(acc.increment(replica, delta + 1L))
                    else acc.piece(acc.decrement(replica, delta + 1L))
            }
            assertEquals(
                acc.totalIncrement - acc.totalDecrement,
                acc.value,
                "seed $seed: value invariant failed after ${deltas.size} ops",
            )
        }
    }

    // ── Overflow (#3318) ─────────────────────────────────────────────────────────────────────

    private fun PNCounter.inc(replica: ReplicaId, by: Long) = piece(increment(replica, by))

    private fun PNCounter.dec(replica: ReplicaId, by: Long) = piece(decrement(replica, by))

    @Test
    fun incrementRefusesAnIncrementThatWouldOverflowItsSlot() {
        val pn = PNCounter.ZERO.inc(a, Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { pn.increment(a, 1L) }
    }

    @Test
    fun decrementRefusesADecrementThatWouldOverflowItsSlot() {
        val pn = PNCounter.ZERO.dec(a, Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { pn.decrement(a, 1L) }
    }

    @Test
    fun valueRefusesANetThatDoesNotFitInALong() {
        val pn = PNCounter.ZERO.inc(a, QUARTER).inc(b, QUARTER)
        assertAll(
            { assertFailsWith<ArithmeticException> { pn.value } },
            { assertFailsWith<ArithmeticException> { pn.totalIncrement } },
        )
    }

    /**
     * The net is computed exactly, so it is answered whenever it fits, even when one half's total
     * does not. Subtracting two checked totals would refuse here; the first assertion is what
     * proves the counter really is in that state.
     */
    @Test
    fun valueIsAnsweredWhenTheNetFitsThoughTheIncrementTotalDoesNot() {
        val pn = PNCounter.ZERO.inc(a, QUARTER).inc(b, QUARTER).dec(a, 1L)
        assertAll(
            { assertFailsWith<ArithmeticException> { pn.totalIncrement } },
            { assertEquals(Long.MAX_VALUE, pn.value) },
        )
    }

    @Test
    fun valueReachesExactlyLongMinValueAndRefusesOnePast() {
        val atMin = PNCounter.ZERO.dec(a, QUARTER).dec(b, QUARTER)
        assertAll(
            { assertEquals(Long.MIN_VALUE, atMin.value) },
            { assertFailsWith<ArithmeticException> { atMin.dec(b, 1L).value } },
        )
    }

    /** A counter whose value does not fit still prints, so it can appear in a log or a failure. */
    @Test
    fun toStringNamesTheExactNetWhenItDoesNotFitInALong() {
        val pn = PNCounter.ZERO.inc(a, QUARTER).inc(b, QUARTER)
        assertTrue("value=9223372036854775808" in pn.toString(), pn.toString())
    }

    private companion object {
        /** 2⁶²: two slots of it sum to one past [Long.MAX_VALUE]. */
        const val QUARTER = 1L shl 62
        const val VALUE_INVARIANT_SEEDS = 64
        const val MAX_OPS = 6
        const val MAX_DELTA = 31
    }
}
