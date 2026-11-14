package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every operation against [Big], an independent arbitrary-precision construction, on **every**
 * target. `BigIntegerOracleTest` does the same against `BigInteger` on the JVM only; the integer
 * paths that differ — Kotlin/Native's and wasm's — are exactly where a word-level bug would live,
 * so the 200,000-input sweeps run here too.
 *
 * Each sweep asserts a property per operation (the value matches the oracle, `null` exactly when the
 * oracle's value leaves `Long`, plus an algebraic identity the operation must keep), counts its
 * arms so a sweep that never reached the overflow arm cannot pass, and draws half its inputs from
 * the corners ([nextCornerLong]).
 */
class OracleTest {

    @Test
    fun checkedLongMatchesTheOracle() {
        val random = Random(101)
        var overflowed = 0
        repeat(N) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val (ba, bb) = Big.of(a) to Big.of(b)
            val sum = a.plusOrNull(b)
            assertEquals((ba + bb).toLongOrNull(), sum, "$a + $b")
            assertEquals(sum, b.plusOrNull(a), "$a + $b commutes")
            assertEquals((ba - bb).toLongOrNull(), a.minusOrNull(b), "$a - $b")
            assertEquals((ba * bb).toLongOrNull(), a.timesOrNull(b), "$a * $b")
            assertEquals((-ba).toLongOrNull(), a.negateOrNull(), "-$a")
            // a − b = a + (−b) whenever −b fits.
            b.negateOrNull()?.let { nb -> assertEquals(a.plusOrNull(nb), a.minusOrNull(b), "$a - $b as a + -b") }
            if (sum == null) overflowed++
        }
        assertTrue(overflowed > N / 50, "the overflow arm ran $overflowed of $N times")
    }

    @Test
    fun wideProductsSumsAndOrderMatchTheOracle() {
        val random = Random(102)
        repeat(N) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val c = random.nextCornerLong()
            val d = random.nextCornerLong()
            val ab = a.timesWide(b)
            val cd = c.timesWide(d)
            val bab = Big.of(a) * Big.of(b)
            val bcd = Big.of(c) * Big.of(d)
            assertEquals(bab, Big.of(ab), "$a * $b")
            assertEquals(bab.toString(), ab.toString(), "$a * $b in decimal")
            assertEquals(bab.compareTo(bcd).coerceIn(-1, 1), compareProducts(a, b, c, d).coerceIn(-1, 1), "$a*$b <=> $c*$d")
            // Products lie in [−2¹²⁶ + 2⁶³, 2¹²⁶], so a sum of two leaves 128 bits at one corner only,
            // MIN·MIN + MIN·MIN = 2¹²⁷, and a difference never does: it is within ±(2¹²⁷ − 2⁶³). So
            // the difference has no throw arm to check, and a throw there fails the test.
            assertSumMatches(bab + bcd) { ab + cd }
            assertEquals(bab - bcd, Big.of(ab - cd), "$ab - $cd")
            assertEquals(bab.toLongOrNull(), ab.toLongOrNull(), "$ab narrowed")
            // (a·b) − (c·d) + (c·d) = a·b: the two operators undo each other, wherever the first fits.
            if (bab - bcd in Big.of(Int128.MIN_VALUE)..Big.of(Int128.MAX_VALUE)) assertEquals(ab, ab - cd + cd, "$ab - $cd + $cd")
        }
    }

    /** [actual] equals [exact], or throws exactly when [exact] leaves 128 bits. */
    private fun assertSumMatches(exact: Big, actual: () -> Int128) {
        if (exact < Big.of(Int128.MIN_VALUE) || exact > Big.of(Int128.MAX_VALUE)) {
            assertFailsWith<ArithmeticException>("$exact leaves 128 bits") { actual() }
        } else {
            assertEquals(exact, Big.of(actual()))
        }
    }

    @Test
    fun sumsPastOneHundredTwentyEightBitsThrowExactlyWhenTheOracleLeavesTheRange() {
        val random = Random(103)
        val min = Big.of(Int128.MIN_VALUE)
        val max = Big.of(Int128.MAX_VALUE)
        var threw = 0
        repeat(N) {
            val x = Int128.fromTwosComplement(random.nextLong(), random.nextLong())
            val y = Int128.fromTwosComplement(random.nextLong(), random.nextLong())
            val exact = Big.of(x) + Big.of(y)
            if (exact < min || exact > max) {
                threw++
                assertFailsWith<ArithmeticException>("$x + $y") { x + y }
            } else {
                assertEquals(exact, Big.of(x + y), "$x + $y")
            }
        }
        assertTrue(threw in N / 8..N / 2, "the overflow arm ran $threw of $N times")
    }

    @Test
    fun mulDivMatchesTheOracleInBothDirections() {
        val random = Random(104)
        var refused = 0
        var inexact = 0
        repeat(N) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val d = random.nextCornerLong().takeIf { it != 0L } ?: 1L
            val product = Big.of(a) * Big.of(b)
            val floor = mulDiv(a, b, d, Rounding.FLOOR)
            val ceiling = mulDiv(a, b, d, Rounding.CEILING)
            assertEquals(product.floorDiv(Big.of(d)).toLongOrNull(), floor, "floor($a * $b / $d)")
            assertEquals(product.ceilDiv(Big.of(d)).toLongOrNull(), ceiling, "ceil($a * $b / $d)")
            // ⌈x⌉ = −⌊−x⌋, wherever −a and ⌊−x⌋ both fit.
            a.negateOrNull()?.let { na ->
                mulDiv(na, b, d, Rounding.FLOOR)?.negateOrNull()?.let { assertEquals(it, ceiling, "ceil via floor of -$a") }
            }
            if (floor == null) refused++
            if (floor != null && ceiling != null && floor != ceiling) inexact++
        }
        assertAll(
            { assertTrue(refused > N / 100, "the refusal arm ran $refused of $N times") },
            { assertTrue(inexact > N / 10, "the rounding arm ran $inexact of $N times") },
        )
    }

    /** Every pair of corner values through every operation: the edges a random draw can miss. */
    @Test
    fun everyPairOfCornersMatchesTheOracle() {
        var pairs = 0
        for (a in CORNERS) for (b in CORNERS) {
            val (ba, bb) = Big.of(a) to Big.of(b)
            assertEquals((ba + bb).toLongOrNull(), a.plusOrNull(b), "$a + $b")
            assertEquals((ba - bb).toLongOrNull(), a.minusOrNull(b), "$a - $b")
            assertEquals((ba * bb).toLongOrNull(), a.timesOrNull(b), "$a * $b")
            assertEquals(ba * bb, Big.of(a.timesWide(b)), "$a ⊗ $b")
            for (d in CORNERS) {
                if (d == 0L) continue
                val bd = Big.of(d)
                assertEquals((ba * bb).floorDiv(bd).toLongOrNull(), mulDiv(a, b, d, Rounding.FLOOR), "floor($a * $b / $d)")
                assertEquals((ba * bb).ceilDiv(bd).toLongOrNull(), mulDiv(a, b, d, Rounding.CEILING), "ceil($a * $b / $d)")
            }
            pairs++
        }
        assertEquals(CORNERS.size * CORNERS.size, pairs)
    }

    /** The cases a rounding bug lives at: −1 × MIN, division by ±1, and exact halves in each direction. */
    @Test
    fun boundariesRoundTheWayTheirModeSays() = assertAll(
        // −1 × MIN_VALUE is the one product of two Longs whose negation does not fit.
        { assertEquals(null, (-1L).timesOrNull(Long.MIN_VALUE)) },
        { assertEquals(Big.of(Long.MIN_VALUE) * Big.of(-1L), Big.of((-1L).timesWide(Long.MIN_VALUE))) },
        // Dividing by ±1 is exact in both modes, and MIN / −1 is refused in both.
        { assertEquals(Long.MIN_VALUE, mulDiv(Long.MIN_VALUE, 1L, 1L, Rounding.FLOOR)) },
        { assertEquals(Long.MIN_VALUE, mulDiv(Long.MIN_VALUE, 1L, 1L, Rounding.CEILING)) },
        { assertEquals(null, mulDiv(Long.MIN_VALUE, 1L, -1L, Rounding.FLOOR)) },
        { assertEquals(null, mulDiv(Long.MIN_VALUE, 1L, -1L, Rounding.CEILING)) },
        { assertEquals(-Long.MAX_VALUE, mulDiv(Long.MAX_VALUE, 1L, -1L, Rounding.CEILING)) },
        // Exact halves, each sign, each mode: ±(2k+1)/2 and ±(2k+1)/−2.
        { assertEquals(listOf(3L, 4L, -4L, -3L, -4L, -3L, 3L, 4L), halves(7L)) },
        { assertEquals(listOf(0L, 1L, -1L, 0L, -1L, 0L, 0L, 1L), halves(1L)) },
        {
            // (2⁶³ − 1)/2 at the top of the range, where the bump is the overflow risk.
            val k = Long.MAX_VALUE
            assertEquals(listOf(k / 2, k / 2 + 1, -(k / 2) - 1, -(k / 2), -(k / 2) - 1, -(k / 2), k / 2, k / 2 + 1), halves(k))
        },
    )

    /** `x/2` and `x/−2` floored and ceiled, for `x` and `−x`, in that order. */
    private fun halves(x: Long): List<Long?> = listOf(
        mulDiv(x, 1L, 2L, Rounding.FLOOR), mulDiv(x, 1L, 2L, Rounding.CEILING),
        mulDiv(-x, 1L, 2L, Rounding.FLOOR), mulDiv(-x, 1L, 2L, Rounding.CEILING),
        mulDiv(x, 1L, -2L, Rounding.FLOOR), mulDiv(x, 1L, -2L, Rounding.CEILING),
        mulDiv(-x, 1L, -2L, Rounding.FLOOR), mulDiv(-x, 1L, -2L, Rounding.CEILING),
    )

    private companion object {
        const val N = 200_000
        val CORNERS = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1L, -(1L shl 32), -2L, -1L, 0L, 1L, 2L,
            (1L shl 32) - 1L, 1L shl 32, Long.MAX_VALUE - 1L, Long.MAX_VALUE,
        )
    }
}
