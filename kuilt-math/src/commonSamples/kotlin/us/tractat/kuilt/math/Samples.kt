package us.tractat.kuilt.math

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sum that would wrap answers `null`, so the caller decides what an overflow means. */
fun sampleCheckedLong() {
    assertEquals(7L, 3L.plusOrNull(4L))
    assertNull(Long.MAX_VALUE.plusOrNull(1L))
    assertNull(Long.MIN_VALUE.negateOrNull())
}

/** Compare `a/b` with `c/d` by cross-multiplying, with no product ever leaving its type. */
fun sampleCompareProducts() {
    val a = Long.MAX_VALUE / 3
    val b = 7L
    val c = Long.MAX_VALUE / 5
    val d = 11L
    // a/b > c/d exactly when a·d > c·b; both products overflow a Long, neither overflows an Int128.
    assertTrue(a.timesWide(d) > c.timesWide(b))
    assertTrue(compareProducts(a, d, c, b) > 0)
}

/** Scale a large count by a ratio, rounding in the direction the caller chose. */
fun sampleMulDiv() {
    val big = 1L shl 62
    // big · 4 overflows a Long, but big · 4 / 8 = 2⁶¹ does not, and mulDiv only needs the answer to fit.
    assertEquals(1L shl 61, mulDiv(big, 4L, 8L, Rounding.FLOOR))
    assertEquals(3L, mulDiv(7L, 1L, 2L, Rounding.FLOOR))
    assertEquals(4L, mulDiv(7L, 1L, 2L, Rounding.CEILING))
    assertNull(mulDiv(Long.MAX_VALUE, 2L, 1L, Rounding.FLOOR))
}
