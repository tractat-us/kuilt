package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeightTest {

    @Test
    fun ordersByExactCrossMultiplication() {
        assertTrue(Weight.of(1, 3) < Weight.of(1, 2))
        assertTrue(Weight.of(3, 1) > Weight.of(2, 1))
        assertTrue(Weight.of(2, 3) > Weight.of(1, 2))
        assertEquals(0, Weight.of(1, 2).compareTo(Weight.of(2, 4)))
    }

    @Test
    fun reducesToLowestTermsSoEqualRatiosAreStructurallyEqual() {
        assertEquals(Weight.of(1, 2), Weight.of(2, 4))
        assertEquals(Weight.of(3, 1), Weight.of(9, 3))
        assertEquals(Weight.of(2), Weight.of(4, 2))
        assertEquals(Weight.ONE, Weight.of(5, 5))
    }

    @Test
    fun rejectsNonPositiveComponents() {
        assertFailsWith<IllegalArgumentException> { Weight.of(0) }
        assertFailsWith<IllegalArgumentException> { Weight.of(-1, 2) }
        assertFailsWith<IllegalArgumentException> { Weight.of(1, 0) }
        assertFailsWith<IllegalArgumentException> { Weight.of(1, -3) }
    }

    @Test
    fun comparisonThatWouldOverflowThrowsRatherThanWrapping() {
        // a/b vs c/d compares a*d against c*b. With near-MAX numerator and denominator
        // the cross-products overflow Long — the comparator must throw, never wrap to a
        // wrong verdict.
        val huge = Weight.of(Long.MAX_VALUE / 2, 1)
        val alsoHuge = Weight.of(1, 3)
        assertFailsWith<ArithmeticException> { huge.compareTo(alsoHuge) }
    }

    @Test
    fun roundTripsThroughJson() {
        val w = Weight.of(3, 7)
        val encoded = Json.encodeToString(Weight.serializer(), w)
        assertEquals(w, Json.decodeFromString(Weight.serializer(), encoded))
    }
}
