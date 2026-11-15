package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 128-bit [mulDiv] against [Big] on every target, over the corner-word grid
 * [Int128WordSweepTest] uses for the dividend and its multipliers and divisors.
 *
 * It counts the arms it reached and asserts each one, so a grid that stops reaching an arm reds.
 */
class WideMulDivTest {

    private val values: List<Pair<Int128, Big>> = Int128WordSweepTest.WORDS.flatMap { high ->
        Int128WordSweepTest.WORDS.map { low -> Int128.fromTwosComplement(high, low) to Big.ofWords(high, low) }
    }

    private val multipliers: List<Long> = listOf(0L, 1L, -1L, 3L, -7L, 16L, 1L shl 40, Long.MAX_VALUE, Long.MIN_VALUE)

    private val divisors: List<Long> = listOf(1L, -1L, 2L, 3L, -7L, 192L, 1L shl 40, Long.MAX_VALUE, Long.MIN_VALUE)

    private val min = Big.of(Int128.MIN_VALUE)
    private val max = Big.of(Int128.MAX_VALUE)

    @Test
    fun wideMulDivMatchesTheOracle() {
        var fits = 0
        var refused = 0
        var rounded = 0
        var landedOnMin = 0
        var landedOnMax = 0
        var productPast128Bits = 0
        for ((x, bx) in values) for (b in multipliers) for (d in divisors) {
            val product = bx * Big.of(b)
            if (product < min || product > max) productPast128Bits++
            for (rounding in Rounding.entries) {
                val exactBig = when (rounding) {
                    Rounding.FLOOR -> product.floorDiv(Big.of(d))
                    Rounding.CEILING -> product.ceilDiv(Big.of(d))
                }
                val exact = exactBig.takeIf { it >= min && it <= max }
                val actual = mulDiv(x, b, d, rounding)
                assertEquals(exact?.toString(), actual?.toString(), "$rounding($x · $b / $d)")
                if (actual == null) {
                    refused++
                } else {
                    fits++
                    if (product.truncDivRem(Big.of(d)).second != Big.ZERO) rounded++
                    if (actual == Int128.MIN_VALUE) landedOnMin++
                    if (actual == Int128.MAX_VALUE) landedOnMax++
                }
            }
        }
        val arms = "fits=$fits refused=$refused rounded=$rounded landedOnMin=$landedOnMin " +
            "landedOnMax=$landedOnMax productPast128Bits=$productPast128Bits"
        println("arms reached: $arms")
        assertAll(
            { assertTrue(fits > 0, arms) },
            { assertTrue(refused > 0, arms) },
            { assertTrue(rounded > 0, arms) },
            { assertTrue(landedOnMin > 0, arms) },
            { assertTrue(landedOnMax > 0, arms) },
            // The point of the 192-bit product: a quotient that fits although the product does not.
            { assertTrue(productPast128Bits > 0, arms) },
        )
    }

    @Test
    fun onlyTheQuotientHasToFit() = assertAll(
        // (2¹²⁶ · 4) / 8 = 2¹²⁵, though the product 2¹²⁸ is past 128 bits.
        { assertEquals(Int128.fromTwosComplement(1L shl 61, 0L), mulDiv(Int128.fromTwosComplement(1L shl 62, 0L), 4L, 8L, Rounding.FLOOR)) },
        { assertEquals(Int128.of(3L), mulDiv(Int128.of(7L), 1L, 2L, Rounding.FLOOR)) },
        { assertEquals(Int128.of(4L), mulDiv(Int128.of(7L), 1L, 2L, Rounding.CEILING)) },
        { assertEquals(Int128.of(-4L), mulDiv(Int128.of(-7L), 1L, 2L, Rounding.FLOOR)) },
        { assertNull(mulDiv(Int128.MAX_VALUE, 2L, 1L, Rounding.FLOOR)) },
        { assertEquals(Int128.MIN_VALUE, mulDiv(Int128.MIN_VALUE, 1L, 1L, Rounding.FLOOR)) },
        { assertNull(mulDiv(Int128.MIN_VALUE, -1L, 1L, Rounding.FLOOR)) },
    )

    @Test
    fun aZeroDivisorThrowsLikeLongDivision() {
        val e = assertFailsWith<ArithmeticException> { mulDiv(Int128.of(1L), 1L, 0L, Rounding.FLOOR) }
        assertTrue("division by zero" in e.message.orEmpty(), "message: ${e.message}")
    }
}
