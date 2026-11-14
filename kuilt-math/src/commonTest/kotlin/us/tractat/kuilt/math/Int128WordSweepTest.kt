package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `Int128` built from two corner words, through the operations that take an `Int128` as
 * input, against [Big] on every target.
 *
 * The other sweeps build their `Int128`s from `Long`s ([timesWide], [Int128.of]), so they never
 * reach most of the 128-bit range: [Int128.MIN_VALUE] as a dividend, a quotient that needs a second
 * word, a sum leaving 128 bits from either side. Here both words come from [WORDS] — the extremes,
 * ±1, 0, powers of two, and 32-bit half patterns, where a carry or a borrow between the halves would
 * go wrong — and every pair of them is one value, so the sweep is exhaustive over that grid.
 *
 * Each test counts the arms it reached and asserts every one, throw arms included, so a change to
 * [WORDS] that stops reaching an arm reds rather than passing by never getting there.
 */
class Int128WordSweepTest {

    private val values: List<Pair<Int128, Big>> = WORDS.flatMap { high ->
        WORDS.map { low -> Int128.fromTwosComplement(high, low) to Big.ofWords(high, low) }
    }

    private val min = Big.of(Int128.MIN_VALUE)
    private val max = Big.of(Int128.MAX_VALUE)

    @Test
    fun divideOrNullMatchesTheOracleInBothDirections() {
        var fits = 0
        var refused = 0
        var refusedOnlyByRounding = 0 // the truncated quotient fits; the rounded one does not
        var rounded = 0 // the rounding moved the quotient off the truncated one
        var landedOnLongMin = 0
        var landedOnLongMax = 0
        var minValueDividend = 0
        for ((x, bx) in values) for (d in DIVISORS) {
            val bd = Big.of(d)
            val truncated = bx.truncDivRem(bd).first.toLongOrNull()
            for (rounding in Rounding.entries) {
                val exact = when (rounding) {
                    Rounding.FLOOR -> bx.floorDiv(bd)
                    Rounding.CEILING -> bx.ceilDiv(bd)
                }.toLongOrNull()
                val actual = x.divideOrNull(d, rounding)
                assertEquals(exact, actual, "$rounding($x / $d)")
                if (x == Int128.MIN_VALUE) minValueDividend++
                when {
                    actual == null && truncated != null -> refusedOnlyByRounding++
                    actual == null -> refused++
                    else -> {
                        fits++
                        if (actual != truncated) rounded++
                        if (actual == Long.MIN_VALUE) landedOnLongMin++
                        if (actual == Long.MAX_VALUE) landedOnLongMax++
                    }
                }
            }
        }
        val arms = "fits=$fits refused=$refused refusedOnlyByRounding=$refusedOnlyByRounding rounded=$rounded " +
            "landedOnLongMin=$landedOnLongMin landedOnLongMax=$landedOnLongMax minValueDividend=$minValueDividend"
        println("arms reached: $arms") // in the results XML, so a run shows what it covered
        assertAll(
            { assertTrue(fits > 0, arms) },
            { assertTrue(refused > 0, arms) },
            { assertTrue(refusedOnlyByRounding > 0, arms) },
            { assertTrue(rounded > 0, arms) },
            { assertTrue(landedOnLongMin > 0, arms) },
            { assertTrue(landedOnLongMax > 0, arms) },
            // 2¹²⁷ / d needs a second quotient word for every Long d, so each of these is a refusal;
            // what the arm checks is that magnitude() reads MIN_VALUE as 2¹²⁷, not as a wrapped value.
            { assertEquals(DIVISORS.size * Rounding.entries.size, minValueDividend, arms) },
        )
    }

    @Test
    fun remMatchesTheOracle() {
        var zero = 0
        var positive = 0
        var negative = 0
        var minValueDividendNonZero = 0
        for ((x, bx) in values) for (d in DIVISORS) {
            val exact = checkNotNull(bx.truncDivRem(Big.of(d)).second.toLongOrNull()) { "|$x % $d| < |$d|" }
            val actual = x % d
            assertEquals(exact, actual, "$x % $d")
            when {
                actual == 0L -> zero++
                actual > 0L -> positive++
                else -> negative++
            }
            if (x == Int128.MIN_VALUE && actual != 0L) minValueDividendNonZero++
        }
        val arms = "zero=$zero positive=$positive negative=$negative minValueDividendNonZero=$minValueDividendNonZero"
        println("arms reached: $arms") // in the results XML, so a run shows what it covered
        assertAll(
            { assertTrue(zero > 0, arms) },
            { assertTrue(positive > 0, arms) },
            { assertTrue(negative > 0, arms) },
            { assertTrue(minValueDividendNonZero > 0, arms) },
        )
    }

    @Test
    fun plusAndMinusMatchTheOracleAndThrowExactlyWhenTheyLeave() {
        val arms = IntArray(6) // plus: fits, above, below; minus: fits, above, below
        for ((x, bx) in values) for ((y, by) in values) {
            sweepArm(bx + by, arms, 0, { "$x + $y" }) { x + y }
            sweepArm(bx - by, arms, 3, { "$x - $y" }) { x - y }
        }
        val counts = "plus fits=${arms[0]} above=${arms[1]} below=${arms[2]}; " +
            "minus fits=${arms[3]} above=${arms[4]} below=${arms[5]}"
        println("arms reached: $counts") // in the results XML, so a run shows what it covered
        assertAll(
            { assertTrue(arms.all { it > 0 }, counts) },
            { assertEquals(2 * values.size * values.size, arms.sum(), counts) },
        )
    }

    /**
     * [actual] equals [exact], or throws [ArithmeticException] exactly when [exact] leaves 128 bits;
     * counts the arm at [base]. The message is built only on a failure: printing two `Int128`s for
     * each of the ~200,000 pairs would cost more than the sweep.
     */
    private fun sweepArm(exact: Big, arms: IntArray, base: Int, what: () -> String, actual: () -> Int128) {
        val arm = when {
            exact > max -> 1
            exact < min -> 2
            else -> 0
        }
        arms[base + arm]++
        val result = try {
            actual()
        } catch (_: ArithmeticException) {
            null
        }
        val agrees = if (arm == 0) result != null && Big.of(result) == exact else result == null
        if (!agrees) fail("${what()} = $exact: expected ${if (arm == 0) "that value" else "a throw"}, got $result")
    }

    private companion object {
        /** The words each half of a value is drawn from. */
        val WORDS: List<Long> = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE, Long.MAX_VALUE - 1L, 0L, 1L, -1L, 2L, -2L,
            // Powers of two at the 32-bit boundary and one below the sign bit.
            1L shl 31, 1L shl 32, 1L shl 62, -(1L shl 62),
            // 32-bit half patterns: a half of all ones beside one of zeros, and each half's sign bit.
            0x0000_0000_FFFF_FFFFuL.toLong(), 0xFFFF_FFFF_0000_0000uL.toLong(), 0x0000_0000_7FFF_FFFFuL.toLong(),
            0x7FFF_FFFF_7FFF_FFFFuL.toLong(), 0x7FFF_FFFF_0000_0000uL.toLong(), 0x0000_0001_0000_0001uL.toLong(),
            0x8000_0000_8000_0000uL.toLong(), 0x8000_0000_FFFF_FFFFuL.toLong(),
        ).also { check(it.distinct().size == it.size) { "a word is listed twice" } }

        /** The non-zero words, plus small odd divisors, so that many quotients are inexact. */
        val DIVISORS: List<Long> = (WORDS.filter { it != 0L } + listOf(3L, -3L, 7L, 1_000_000_000_000_000_000L)).distinct()
    }
}
