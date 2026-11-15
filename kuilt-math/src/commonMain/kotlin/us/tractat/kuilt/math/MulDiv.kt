package us.tractat.kuilt.math

/**
 * `a · b / divisor`, rounded as [rounding] says, or `null` when the rounded result does not fit in a
 * `Long`.
 *
 * The product is formed in 128 bits, so it never overflows on the way: only the final quotient has
 * to fit. That is the difference from `a.timesOrNull(b)?.let { it / divisor }`, which refuses
 * `(2⁶² · 4) / 8` although the answer, 2⁶¹, is small.
 *
 * @throws ArithmeticException if [divisor] is zero, as `Long / 0` does.
 * @sample us.tractat.kuilt.math.sampleMulDiv
 */
public fun mulDiv(a: Long, b: Long, divisor: Long, rounding: Rounding): Long? =
    a.timesWide(b).divideOrNull(divisor, rounding)

/**
 * `a · b / divisor` for a 128-bit [a], rounded as [rounding] says, or `null` when the rounded result
 * does not fit in an [Int128].
 *
 * The product is formed in 192 bits, so, as with the `Long` form, only the final quotient has to
 * fit. It is the shape a position takes when a wide count is read on a fractional scale, `a · n / d`
 * with `a` itself past `Long`.
 *
 * @throws ArithmeticException if [divisor] is zero, as `Long / 0` does.
 * @sample us.tractat.kuilt.math.sampleWideMulDiv
 */
public fun mulDiv(a: Int128, b: Long, divisor: Long, rounding: Rounding): Int128? {
    if (divisor == 0L) throw ArithmeticException("division by zero: $a · $b / 0")
    val negative = (a < Int128.ZERO) != (b < 0L) != (divisor < 0L)
    val m = a.magnitude()
    val bm = magnitudeOf(b)
    val dm = magnitudeOf(divisor) // ≤ 2⁶³, what divideUnsigned needs.
    // |a| · |b| = (m.high · |b|) · 2⁶⁴ + m.low · |b|, three 64-bit words p2 · 2¹²⁸ + p1 · 2⁶⁴ + p0.
    val low = unsignedTimesWide(m.low.toULong(), bm)
    val high = unsignedTimesWide(m.high.toULong(), bm)
    val p0 = low.second
    val p1 = low.first + high.second
    val p2 = high.first + (if (p1 < low.first) 1UL else 0UL) // m.high ≤ 2⁶³, so this cannot wrap.
    // Long division by one word, top word first: each step's remainder is below the divisor.
    val top = p2 / dm
    if (top != 0UL) return null
    val mid = divideUnsigned(p2 % dm, p1, dm)
    val bottom = divideUnsigned(mid.remainder, p0, dm)
    var qHigh = mid.quotientLow
    var qLow = bottom.quotientLow
    val bump = bottom.remainder != 0UL && when (rounding) {
        Rounding.FLOOR -> negative
        Rounding.CEILING -> !negative
    }
    if (bump) {
        qLow += 1UL
        if (qLow == 0UL) {
            if (qHigh == ULong.MAX_VALUE) return null
            qHigh += 1UL
        }
    }
    // A positive result may reach 2¹²⁷ − 1; a negative one 2¹²⁷ in magnitude (Int128's least value).
    val topBit = 1UL shl 63
    if (!negative && qHigh >= topBit) return null
    if (negative && (qHigh > topBit || (qHigh == topBit && qLow != 0UL))) return null
    val magnitude = Int128.fromTwosComplement(qHigh.toLong(), qLow.toLong())
    return when {
        !negative -> magnitude
        qHigh == topBit -> magnitude // exactly 2¹²⁷, whose two's complement is itself: Int128's least value.
        else -> Int128.ZERO - magnitude
    }
}

/** |[v]| as an unsigned word; 2⁶³ for `Long.MIN_VALUE`. */
private fun magnitudeOf(v: Long): ULong = if (v < 0L) (0UL - v.toULong()) else v.toULong()

/** The unsigned product of two words as (high, low). */
private fun unsignedTimesWide(x: ULong, y: ULong): Pair<ULong, ULong> {
    val mask = 0xFFFF_FFFFUL
    val x0 = x and mask
    val x1 = x shr 32
    val y0 = y and mask
    val y1 = y shr 32
    val p00 = x0 * y0
    val p01 = x0 * y1
    val p10 = x1 * y0
    val p11 = x1 * y1
    // Each partial product is below 2⁶⁴; the middle column sums three values below 2³² · 2 + 2³².
    val middle = (p00 shr 32) + (p01 and mask) + (p10 and mask)
    val low = (middle shl 32) or (p00 and mask)
    val high = p11 + (p01 shr 32) + (p10 shr 32) + (middle shr 32)
    return high to low
}

/** Which way a quotient that is not whole moves to land on an integer. */
public enum class Rounding {
    /** Toward negative infinity: `7/2 → 3`, `-7/2 → -4`. */
    FLOOR,

    /** Toward positive infinity: `7/2 → 4`, `-7/2 → -3`. */
    CEILING,
}
