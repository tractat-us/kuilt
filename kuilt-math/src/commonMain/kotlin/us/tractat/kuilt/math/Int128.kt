package us.tractat.kuilt.math

/**
 * An exact signed 128-bit integer.
 *
 * Its job is to hold the result of an operation on `Long`s that a `Long` cannot: the product of two
 * of them ([timesWide]), or a running sum of many. Every such value fits with room to spare: a
 * product of two `Long`s needs at most 127 bits, and fewer than 2⁶³ `Long` terms cannot sum past
 * 2¹²⁷. So comparing two products, or summing terms in any order, is exact here, and the only
 * question left is whether the final answer fits back in a `Long` — which [toLongOrNull] and
 * [divideOrNull] answer with `null` rather than a wrapped value.
 *
 * Arithmetic between two `Int128`s ([plus], [minus], [unaryMinus]) throws [ArithmeticException] if
 * it leaves 128 bits. Reaching that from `Long` inputs takes about 2⁶³ terms, so a throw there is a
 * caller bug rather than a data condition, and it is never a silent wrap.
 *
 * The value is two's complement over two words, `high · 2⁶⁴ + unsigned(low)`. Both words are public
 * because a caller keeping a hot accumulator needs to read and rebuild them without allocating;
 * everything else should treat the type as a number.
 *
 * Identical on every target: it uses only `Long` operations whose results Kotlin defines the same
 * way on the JVM, Native and wasmJs.
 *
 * @property high the upper 64 bits, signed: its sign is the value's sign.
 * @property low the lower 64 bits, read as unsigned.
 * @sample us.tractat.kuilt.math.sampleCompareProducts
 */
public class Int128 private constructor(
    public val high: Long,
    public val low: Long,
) : Comparable<Int128> {

    /** `true` when this value is below zero. */
    public val isNegative: Boolean get() = high < 0L

    /** `-1`, `0` or `1`, the sign of this value. */
    public val sign: Int get() = when {
        high < 0L -> -1
        high == 0L && low == 0L -> 0
        else -> 1
    }

    /** The exact sum. Throws [ArithmeticException] if it leaves 128 bits. */
    public operator fun plus(other: Int128): Int128 {
        val lo = low + other.low
        val carry = if (unsignedLess(lo, low)) 1L else 0L
        val hi = high + other.high + carry
        // A two's complement sum overflows iff both operands share a sign the result does not.
        if ((high xor hi) and (other.high xor hi) < 0L) throw overflow("$this + $other")
        return Int128(hi, lo)
    }

    /** The exact difference. Throws [ArithmeticException] if it leaves 128 bits. */
    public operator fun minus(other: Int128): Int128 {
        val lo = low - other.low
        val borrow = if (unsignedLess(low, other.low)) 1L else 0L
        val hi = high - other.high - borrow
        // A difference overflows iff the operands differ in sign and the result left this one's.
        if ((high xor other.high) and (high xor hi) < 0L) throw overflow("$this - $other")
        return Int128(hi, lo)
    }

    /** The exact sum with a `Long`. Throws [ArithmeticException] if it leaves 128 bits. */
    public operator fun plus(other: Long): Int128 = plus(of(other))

    /** The exact difference with a `Long`. Throws [ArithmeticException] if it leaves 128 bits. */
    public operator fun minus(other: Long): Int128 = minus(of(other))

    /** The negation. Throws [ArithmeticException] for [MIN_VALUE], whose negation is 2¹²⁷. */
    public operator fun unaryMinus(): Int128 = ZERO - this

    /** This value as a `Long`, or `null` when it does not fit in one. */
    public fun toLongOrNull(): Long? = if (high == (low shr 63)) low else null

    /**
     * `this / divisor`, rounded as [rounding] says, or `null` when the rounded quotient does not
     * fit in a `Long`.
     *
     * The rounding is a required argument because the direction is the decision: a scheduler
     * seating a newcomer rounds its position *up* so the newcomer is never ahead of the field, and
     * a quota rounds a share *down* so it never hands out more than it holds. A default would make
     * that choice silently.
     *
     * @throws IllegalArgumentException if [divisor] is zero.
     */
    public fun divideOrNull(divisor: Long, rounding: Rounding): Long? {
        require(divisor != 0L) { "division by zero: $this / 0" }
        val negative = isNegative != (divisor < 0L)
        val n = magnitude()
        val d = if (divisor < 0L) (-divisor).toULong() else divisor.toULong() // |MIN_VALUE| = 2⁶³ fits.
        val q = divideUnsigned(n.high.toULong(), n.low.toULong(), d)
        if (q.quotientHigh != 0UL) return null
        // Truncation rounded the magnitude down. Floor of a negative and ceiling of a positive
        // quotient are one further from zero whenever anything was left over.
        val bump = q.remainder != 0UL && when (rounding) {
            Rounding.FLOOR -> negative
            Rounding.CEILING -> !negative
        }
        // A negative result may reach 2⁶³ in magnitude (Long.MIN_VALUE); a positive one 2⁶³ − 1.
        val limit = if (negative) LONG_MIN_MAGNITUDE else LONG_MAX_MAGNITUDE
        if (q.quotientLow > limit) return null // checked first, so the bump below cannot wrap.
        val magnitude = if (bump) q.quotientLow + 1UL else q.quotientLow
        if (magnitude > limit) return null
        return if (negative) (0UL - magnitude).toLong() else magnitude.toLong()
    }

    override fun compareTo(other: Int128): Int =
        if (high != other.high) high.compareTo(other.high) else compareUnsigned(low, other.low)

    override fun equals(other: Any?): Boolean = other is Int128 && high == other.high && low == other.low

    override fun hashCode(): Int = 31 * high.hashCode() + low.hashCode()

    /** The value in decimal, with a leading `-` when negative. */
    override fun toString(): String {
        if (high == (low shr 63)) return low.toString()
        val m = magnitude()
        var hi = m.high.toULong()
        var lo = m.low.toULong()
        val chunks = ArrayList<ULong>()
        while (hi != 0UL || lo != 0UL) {
            val q = divideUnsigned(hi, lo, DECIMAL_CHUNK)
            chunks += q.remainder
            hi = q.quotientHigh
            lo = q.quotientLow
        }
        val digits = StringBuilder(if (isNegative) "-" else "")
        digits.append(chunks.last().toString())
        for (i in chunks.size - 2 downTo 0) digits.append(chunks[i].toString().padStart(18, '0'))
        return digits.toString()
    }

    /** The absolute value as an unsigned 128-bit pair; correct for [MIN_VALUE] too (2¹²⁷). */
    private fun magnitude(): Int128 {
        if (!isNegative) return this
        // Two's complement negation without the overflow check: 2¹²⁷ read as unsigned is right.
        val lo = low.inv() + 1L
        val hi = high.inv() + (if (lo == 0L) 1L else 0L)
        return Int128(hi, lo)
    }

    public companion object {
        /** Zero. */
        public val ZERO: Int128 = Int128(0L, 0L)

        /** One. */
        public val ONE: Int128 = Int128(0L, 1L)

        /** The largest value, 2¹²⁷ − 1. */
        public val MAX_VALUE: Int128 = Int128(Long.MAX_VALUE, -1L)

        /** The smallest value, −2¹²⁷. */
        public val MIN_VALUE: Int128 = Int128(Long.MIN_VALUE, 0L)

        /** The `Long` [value], widened. */
        public fun of(value: Long): Int128 = Int128(value shr 63, value)

        /** The value whose two's complement words are [high] and [low]: `high · 2⁶⁴ + unsigned(low)`. */
        public fun fromTwosComplement(high: Long, low: Long): Int128 = Int128(high, low)

        private const val DECIMAL_CHUNK: ULong = 1_000_000_000_000_000_000UL
        private val LONG_MAX_MAGNITUDE: ULong = Long.MAX_VALUE.toULong()
        private val LONG_MIN_MAGNITUDE: ULong = LONG_MAX_MAGNITUDE + 1UL

        private fun overflow(what: String) = ArithmeticException("Int128 overflow: $what")
    }
}

/**
 * The exact product of two `Long`s. It never overflows: the widest product, `Long.MIN_VALUE²`, is
 * 2¹²⁶.
 *
 * @sample us.tractat.kuilt.math.sampleCompareProducts
 */
public fun Long.timesWide(other: Long): Int128 {
    // Signed 64×64 → 128 by 32-bit halves (Hacker's Delight, mulhs). Each partial product and
    // running sum below stays inside a signed Long; the comments give the bound.
    val aLo = this and LOW_32
    val aHi = this shr 32
    val bLo = other and LOW_32
    val bHi = other shr 32
    val p0 = aLo * bLo // < 2⁶⁴, read as unsigned
    val t = aHi * bLo + (p0 ushr 32) // |aHi·bLo| < 2⁶³ − 2³², plus < 2³²
    val w1 = (t and LOW_32) + aLo * bHi // |aLo·bHi| < 2⁶³ − 2³², plus < 2³²
    val high = aHi * bHi + (t shr 32) + (w1 shr 32)
    return Int128.fromTwosComplement(high, this * other)
}

/**
 * The sign of `a·b − c·d`: negative, zero or positive as `a·b` is below, equal to or above `c·d`.
 * Exact for every input, so comparing two fractions `a/c` and `d/b` with positive denominators by
 * cross-multiplying can no longer throw or wrap.
 */
public fun compareProducts(a: Long, b: Long, c: Long, d: Long): Int = a.timesWide(b).compareTo(c.timesWide(d))

/**
 * `a · b / divisor`, rounded as [rounding] says, or `null` when the rounded result does not fit in a
 * `Long`.
 *
 * The product is formed in 128 bits, so it never overflows on the way: only the final quotient has
 * to fit. That is the difference from `a.timesOrNull(b)?.let { it / divisor }`, which refuses
 * `(2⁶² · 4) / 8` although the answer, 2⁶¹, is small.
 *
 * @throws IllegalArgumentException if [divisor] is zero.
 * @sample us.tractat.kuilt.math.sampleMulDiv
 */
public fun mulDiv(a: Long, b: Long, divisor: Long, rounding: Rounding): Long? =
    a.timesWide(b).divideOrNull(divisor, rounding)

/** Which way a quotient that is not whole moves to land on an integer. */
public enum class Rounding {
    /** Toward negative infinity: `7/2 → 3`, `-7/2 → -4`. */
    FLOOR,

    /** Toward positive infinity: `7/2 → 4`, `-7/2 → -3`. */
    CEILING,
}

private const val LOW_32: Long = 0xFFFF_FFFFL

private fun unsignedLess(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) < (b xor Long.MIN_VALUE)

private fun compareUnsigned(a: Long, b: Long): Int = (a xor Long.MIN_VALUE).compareTo(b xor Long.MIN_VALUE)

private class UnsignedQuotient(val quotientHigh: ULong, val quotientLow: ULong, val remainder: ULong)

/** The unsigned 128-bit `(high, low)` divided by a non-zero unsigned [d]: quotient and remainder. */
private fun divideUnsigned(high: ULong, low: ULong, d: ULong): UnsignedQuotient {
    if (high == 0UL) return UnsignedQuotient(0UL, low / d, low % d)
    val quotientHigh = high / d
    var r = high % d // < d, so the low word's quotient fits in 64 bits.
    var q = 0UL
    for (i in 63 downTo 0) {
        // Shifting r left can carry a bit out of 64; when it does, the true value is ≥ 2⁶⁴ > d, so
        // the subtraction is owed, and doing it modulo 2⁶⁴ lands on the right remainder (< d).
        val carried = r >= TOP_BIT
        r = (r shl 1) or ((low shr i) and 1UL)
        q = q shl 1
        if (carried || r >= d) {
            r -= d
            q = q or 1UL
        }
    }
    return UnsignedQuotient(quotientHigh, q, r)
}

private const val TOP_BIT: ULong = 0x8000_0000_0000_0000UL
