package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PNCounterDoubleTest {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    private fun PNCounterDouble.inc(replica: ReplicaId, by: Double) = piece(increment(replica, by))

    private fun PNCounterDouble.dec(replica: ReplicaId, by: Double) = piece(decrement(replica, by))

    @Test
    fun zeroHasValueZero() {
        assertAll(
            { assertEquals(0.0, PNCounterDouble.ZERO.value) },
            { assertEquals(0.0, PNCounterDouble.ZERO.count(a)) },
        )
    }

    @Test
    fun incrementsLessDecrementsIsTheValue() {
        val pn = PNCounterDouble.ZERO.inc(a, 1.5).dec(a, 0.25)
        assertAll(
            { assertEquals(1.25, pn.value) },
            { assertEquals(1.5, pn.totalIncrement) },
            { assertEquals(0.25, pn.totalDecrement) },
        )
    }

    @Test
    fun countIsOneReplicasShareAndTheSharesAddUpToTheValue() {
        val pn = PNCounterDouble.ZERO.inc(a, 4.5).dec(a, 1.0).inc(b, 0.5).dec(b, 2.75)
        assertAll(
            { assertEquals(3.5, pn.count(a), "A's own share") },
            { assertEquals(-2.25, pn.count(b), "B's own share, negative") },
            { assertEquals(0.0, pn.count(c), "a replica with no slot") },
            { assertEquals(1.25, pn.value) },
        )
    }

    @Test
    fun valueCanGoNegative() {
        assertEquals(-0.75, PNCounterDouble.ZERO.dec(a, 0.75).value)
    }

    @Test
    fun incrementAndDecrementRefuseAByThatIsNotPositiveAndFinite() {
        val refused = listOf(0.0, -0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        assertAll(
            refused.flatMap { by ->
                listOf(
                    { assertFailsWith<IllegalArgumentException>("increment by $by") { PNCounterDouble.ZERO.increment(a, by) } },
                    { assertFailsWith<IllegalArgumentException>("decrement by $by") { PNCounterDouble.ZERO.decrement(a, by) } },
                )
            },
        )
    }

    /**
     * A slot that reached infinity could never be corrected: `∞ − x` is `∞`, and `∞ − ∞` is NaN.
     * So the step that would take one there is refused, as [PNCounter] refuses to pass
     * [Long.MAX_VALUE]. The first two assertions are the rig: each slot really is at the edge.
     */
    @Test
    fun aStepThatWouldTakeASlotToInfinityIsRefused() {
        val incAtMax = PNCounterDouble.ZERO.inc(a, Double.MAX_VALUE)
        val decAtMax = PNCounterDouble.ZERO.dec(a, Double.MAX_VALUE)
        assertAll(
            { assertEquals(Double.MAX_VALUE, incAtMax.count(a)) },
            { assertEquals(-Double.MAX_VALUE, decAtMax.count(a)) },
            { assertFailsWith<ArithmeticException> { incAtMax.increment(a, Double.MAX_VALUE) } },
            { assertFailsWith<ArithmeticException> { decAtMax.decrement(a, Double.MAX_VALUE) } },
        )
    }

    @Test
    fun concurrentChangesFromDifferentReplicasMergeEitherWay() {
        val onA = PNCounterDouble.ZERO.inc(a, 3.0)
        val onB = PNCounterDouble.ZERO.inc(b, 4.0).dec(b, 0.5)
        assertAll(
            { assertEquals(onA.piece(onB), onB.piece(onA)) },
            { assertEquals(6.5, onA.piece(onB).value) },
            { assertEquals(onA.piece(onB), onA.piece(onB).piece(onB), "idempotent") },
        )
    }

    /**
     * Floating-point addition is not associative, so [PNCounterDouble.value] must sum in one fixed
     * order or two replicas holding equal state could read different values. The rig: the two
     * orders below do differ, so a value summed in merge order would tell them apart.
     */
    @Test
    fun valueIsTheSameWhicheverOrderTheStateWasMergedIn() {
        val big = PNCounterDouble.ZERO.inc(a, 1e16)
        val small1 = PNCounterDouble.ZERO.inc(b, 1.0)
        val small2 = PNCounterDouble.ZERO.inc(c, 1.0)
        val forward = big.piece(small1).piece(small2)
        val backward = small2.piece(small1).piece(big)
        assertAll(
            { assertNotEquals((1e16 + 1.0) + 1.0, (1.0 + 1.0) + 1e16, "the rig: the two orders round differently") },
            { assertEquals(forward, backward) },
            { assertEquals(forward.value.toRawBits(), backward.value.toRawBits()) },
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val pn = PNCounterDouble.ZERO.inc(a, 2.5).dec(b, 0.75)
        val decoded = Json.decodeFromString(PNCounterDouble.serializer(), Json.encodeToString(PNCounterDouble.serializer(), pn))
        assertAll(
            { assertEquals(pn, decoded) },
            { assertEquals(1.75, decoded.value) },
        )
    }

    /** The type refuses an infinite slot, so the decoder does too. The first assertion is the rig. */
    @Test
    fun decoderRefusesAnInfiniteSlot() {
        val json = Json { allowSpecialFloatingPointValues = true }
        val valid = json.encodeToString(PNCounterDouble.serializer(), PNCounterDouble.ZERO.inc(a, 7.5))
        val infinite = valid.replace(":7.5", ":Infinity")
        assertAll(
            { assertNotEquals(valid, infinite, "the rig found the slot to replace in $valid") },
            { assertFailsWith<IllegalArgumentException> { json.decodeFromString(PNCounterDouble.serializer(), infinite) } },
        )
    }

    /** A negative slot is refused by the half it lives in, [GCounterDouble]. */
    @Test
    fun decoderRefusesANegativeSlot() {
        val valid = Json.encodeToString(PNCounterDouble.serializer(), PNCounterDouble.ZERO.dec(a, 7.5))
        val negated = valid.replace(":7.5", ":-7.5")
        assertAll(
            { assertNotEquals(valid, negated, "the rig found the slot to negate in $valid") },
            { assertFailsWith<IllegalArgumentException> { Json.decodeFromString(PNCounterDouble.serializer(), negated) } },
        )
    }

    @Test
    fun toStringNamesTheValue() {
        assertTrue("value=1.25" in PNCounterDouble.ZERO.inc(a, 1.25).toString())
    }
}
