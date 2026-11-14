package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Int128Test {

    private val two64 = Int128.fromTwosComplement(1L, 0L)

    @Test
    fun sumsCarryAcrossTheWordBoundary() = assertAll(
        { assertEquals(two64, Int128.of(Long.MAX_VALUE) + Long.MAX_VALUE + 2L) },
        { assertEquals(Int128.fromTwosComplement(-2L, -1L), Int128.of(Long.MIN_VALUE) + Long.MIN_VALUE - 1L) },
        { assertEquals(Int128.fromTwosComplement(-1L, Long.MAX_VALUE), Int128.of(Long.MIN_VALUE) - 1L) },
        { assertEquals(Int128.of(-1L), Int128.ZERO - 1L) },
        { assertEquals(Int128.ZERO, two64 - two64) },
        { assertEquals(Int128.of(Long.MIN_VALUE), Int128.ZERO - (Int128.of(Long.MAX_VALUE) + 1L)) },
    )

    @Test
    fun leavingOneHundredTwentyEightBitsThrowsRatherThanWraps() = assertAll(
        { assertFailsWith<ArithmeticException> { Int128.MAX_VALUE + 1L } },
        { assertFailsWith<ArithmeticException> { Int128.MIN_VALUE - 1L } },
        { assertFailsWith<ArithmeticException> { Int128.MAX_VALUE - Int128.MIN_VALUE } },
        { assertFailsWith<ArithmeticException> { Int128.ZERO - Int128.MIN_VALUE } },
        { assertEquals(Int128.MIN_VALUE, Int128.ZERO - Int128.MAX_VALUE - 1L) },
    )

    @Test
    fun narrowsToALongOnlyWhenItFits() = assertAll(
        { assertEquals(Long.MAX_VALUE, Int128.of(Long.MAX_VALUE).toLongOrNull()) },
        { assertEquals(Long.MIN_VALUE, Int128.of(Long.MIN_VALUE).toLongOrNull()) },
        { assertEquals(-1L, Int128.of(-1L).toLongOrNull()) },
        { assertNull((Int128.of(Long.MAX_VALUE) + 1L).toLongOrNull()) },
        { assertNull((Int128.of(Long.MIN_VALUE) - 1L).toLongOrNull()) },
    )

    @Test
    fun ordersBySignedHighWordThenUnsignedLowWord() = assertAll(
        { assertTrue(Int128.of(-1L) < Int128.ZERO) },
        { assertTrue(Int128.of(Long.MAX_VALUE) < Int128.of(Long.MAX_VALUE) + 1L) },
        { assertTrue(Int128.MIN_VALUE < Int128.of(Long.MIN_VALUE)) },
        { assertTrue(Int128.MAX_VALUE > two64) },
        { assertEquals(0, Int128.of(42L).compareTo(Int128.of(42L))) },
    )

    @Test
    fun printsInDecimal() = assertAll(
        { assertEquals("0", Int128.ZERO.toString()) },
        { assertEquals("-9223372036854775808", Int128.of(Long.MIN_VALUE).toString()) },
        { assertEquals("18446744073709551616", two64.toString()) },
        { assertEquals("170141183460469231731687303715884105727", Int128.MAX_VALUE.toString()) },
        { assertEquals("-170141183460469231731687303715884105728", Int128.MIN_VALUE.toString()) },
    )

    @Test
    fun wideProductsAtTheCorners() = assertAll(
        { assertEquals("85070591730234615847396907784232501249", Long.MAX_VALUE.timesWide(Long.MAX_VALUE).toString()) },
        { assertEquals("85070591730234615865843651857942052864", Long.MIN_VALUE.timesWide(Long.MIN_VALUE).toString()) },
        { assertEquals("-85070591730234615856620279821087277056", Long.MIN_VALUE.timesWide(Long.MAX_VALUE).toString()) },
        { assertEquals(Int128.of(-6L), (-2L).timesWide(3L)) },
        { assertEquals(two64, (1L shl 32).timesWide(1L shl 32)) },
    )

    /**
     * The algebra that ties the operations together, over a seeded mix of corner and random values.
     * Each target runs the same seed, so a word-level difference between the JVM, Native and wasm
     * would surface as a red here on the target that disagrees. The JVM-only oracle test checks the
     * values themselves against `BigInteger`.
     */
    @Test
    fun productsSumsAndComparisonsAgree() {
        val random = Random(3313)
        var checked = 0
        repeat(20_000) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val c = random.nextCornerLong()
            val ab = a.timesWide(b)
            // (a + c)·b = a·b + c·b, whenever a + c fits.
            a.plusOrNull(c)?.let { sum -> assertEquals(sum.timesWide(b), ab + c.timesWide(b), "($a + $c) * $b") }
            assertEquals(ab, b.timesWide(a), "$a * $b commutes")
            assertEquals(ab.compareTo(c.timesWide(b)).coerceIn(-1, 1), compareProducts(a, b, c, b).coerceIn(-1, 1))
            assertEquals(a.timesOrNull(b), ab.toLongOrNull(), "$a * $b narrows exactly when it fits")
            checked++
        }
        assertEquals(20_000, checked)
    }
}

/** A `Long` drawn from the corners half the time and uniformly otherwise. */
internal fun Random.nextCornerLong(): Long = if (nextBoolean()) {
    val corners = longArrayOf(
        0L, 1L, -1L, 2L, -2L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L,
        1L shl 31, 1L shl 32, (1L shl 32) - 1L, -(1L shl 32), 1L shl 62, -(1L shl 62),
    )
    corners[nextInt(corners.size)]
} else {
    nextLong() shr nextInt(64)
}
