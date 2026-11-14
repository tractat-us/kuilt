package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckedLongTest {

    @Test
    fun plusAnswersTheSumOrNull() = assertAll(
        { assertEquals(7L, 3L.plusOrNull(4L)) },
        { assertEquals(-1L, 2L.plusOrNull(-3L)) },
        { assertEquals(Long.MAX_VALUE, (Long.MAX_VALUE - 1L).plusOrNull(1L)) },
        { assertEquals(-1L, Long.MAX_VALUE.plusOrNull(Long.MIN_VALUE)) },
        { assertNull(Long.MAX_VALUE.plusOrNull(1L)) },
        { assertNull(Long.MIN_VALUE.plusOrNull(-1L)) },
        { assertNull(Long.MIN_VALUE.plusOrNull(Long.MIN_VALUE)) },
    )

    @Test
    fun minusAnswersTheDifferenceOrNull() = assertAll(
        { assertEquals(-1L, 3L.minusOrNull(4L)) },
        { assertEquals(Long.MIN_VALUE, (Long.MIN_VALUE + 1L).minusOrNull(1L)) },
        { assertEquals(0L, Long.MIN_VALUE.minusOrNull(Long.MIN_VALUE)) },
        { assertNull(Long.MIN_VALUE.minusOrNull(1L)) },
        { assertNull(Long.MAX_VALUE.minusOrNull(-1L)) },
        { assertNull(0L.minusOrNull(Long.MIN_VALUE)) },
    )

    @Test
    fun timesAnswersTheProductOrNull() = assertAll(
        { assertEquals(12L, 3L.timesOrNull(4L)) },
        { assertEquals(0L, 0L.timesOrNull(Long.MIN_VALUE)) },
        { assertEquals(-6L, (-2L).timesOrNull(3L)) },
        { assertEquals(Long.MIN_VALUE, (Long.MIN_VALUE / 2L).timesOrNull(2L)) },
        { assertEquals(Long.MIN_VALUE, Long.MIN_VALUE.timesOrNull(1L)) },
        { assertEquals(-Long.MAX_VALUE, Long.MAX_VALUE.timesOrNull(-1L)) },
        { assertNull(Long.MAX_VALUE.timesOrNull(2L)) },
        { assertNull(Long.MIN_VALUE.timesOrNull(-1L)) },
        { assertNull((-1L).timesOrNull(Long.MIN_VALUE)) },
        { assertNull((Long.MAX_VALUE / 2L + 1L).timesOrNull(3L)) },
        { assertNull((1L shl 32).timesOrNull(1L shl 31)) },
    )

    @Test
    fun negateRefusesOnlyTheMinimum() = assertAll(
        { assertEquals(-5L, 5L.negateOrNull()) },
        { assertEquals(Long.MIN_VALUE + 1L, Long.MAX_VALUE.negateOrNull()) },
        { assertEquals(0L, 0L.negateOrNull()) },
        { assertNull(Long.MIN_VALUE.negateOrNull()) },
    )
}
