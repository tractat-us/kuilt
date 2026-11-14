package us.tractat.kuilt.heddle

/**
 * An exact signed integer of any size: the magnitude in base-2³² limbs, least significant first,
 * with no leading zero limb, and a separate sign. Zero is the empty magnitude and is never negative.
 *
 * It exists for [ExactTime] alone, and carries only what that needs: add, subtract, multiply,
 * compare, shift, exact division and the greatest common divisor. A JVM `BigInteger` is not
 * available in `commonMain`; this module depends on nothing that would supply one, and the policy's
 * arithmetic is small enough that schoolbook algorithms cost less than the dependency would.
 */
internal class BigInt private constructor(
    private val negative: Boolean,
    private val mag: IntArray,
) : Comparable<BigInt> {

    val signum: Int get() = if (mag.isEmpty()) 0 else if (negative) -1 else 1

    val isZero: Boolean get() = mag.isEmpty()

    operator fun unaryMinus(): BigInt = if (isZero) this else BigInt(!negative, mag)

    fun abs(): BigInt = if (negative) BigInt(false, mag) else this

    operator fun plus(other: BigInt): BigInt = when {
        other.isZero -> this
        isZero -> other
        negative == other.negative -> BigInt(negative, addMag(mag, other.mag))
        else -> when (val c = cmpMag(mag, other.mag)) {
            0 -> ZERO
            else -> if (c > 0) make(negative, subMag(mag, other.mag)) else make(other.negative, subMag(other.mag, mag))
        }
    }

    operator fun minus(other: BigInt): BigInt = this + (-other)

    operator fun times(other: BigInt): BigInt =
        if (isZero || other.isZero) ZERO else make(negative != other.negative, mulMag(mag, other.mag))

    override fun compareTo(other: BigInt): Int {
        if (negative != other.negative) return if (negative) -1 else 1
        val c = cmpMag(mag, other.mag)
        return if (negative) -c else c
    }

    /** `this · 2^bits`. */
    fun shl(bits: Int): BigInt = if (isZero || bits == 0) this else make(negative, shlMag(mag, bits))

    /** The number of bits in the magnitude: `0` for zero. */
    fun bitLength(): Int = bitLengthMag(mag)

    /**
     * Truncated division: the quotient rounded toward zero and the remainder carrying this value's
     * sign, so `this == q · divisor + r`.
     */
    fun divRem(divisor: BigInt): Pair<BigInt, BigInt> {
        if (divisor.isZero) throw ArithmeticException("BigInt division by zero")
        val (q, r) = divRemMag(mag, divisor.mag)
        return make(negative != divisor.negative, q) to make(negative, r)
    }

    /** The non-negative greatest common divisor of the two magnitudes; `gcd(0, x) == |x|`. */
    fun gcd(other: BigInt): BigInt = make(false, gcdMag(mag, other.mag))

    /** This value as a `Long`, or `null` when it does not fit. */
    fun toLongOrNull(): Long? {
        if (mag.size > 2) return null
        val low = if (mag.isEmpty()) 0L else mag[0].toLong() and MASK
        val high = if (mag.size < 2) 0L else mag[1].toLong() and MASK
        val unsigned = (high shl 32) or low
        return when {
            !negative -> if (unsigned < 0L) null else unsigned
            unsigned == Long.MIN_VALUE -> Long.MIN_VALUE
            unsigned < 0L -> null
            else -> -unsigned
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BigInt && negative == other.negative && mag.contentEquals(other.mag)

    override fun hashCode(): Int = 31 * negative.hashCode() + mag.contentHashCode()

    override fun toString(): String {
        if (isZero) return "0"
        val digits = StringBuilder()
        var rest = mag
        val ten = intArrayOf(10)
        while (rest.isNotEmpty()) {
            val (q, r) = divRemMag(rest, ten)
            digits.append(if (r.isEmpty()) 0 else r[0])
            rest = q
        }
        if (negative) digits.append('-')
        return digits.reverse().toString()
    }

    companion object {
        private const val MASK = 0xFFFF_FFFFL

        val ZERO: BigInt = BigInt(false, IntArray(0))
        val ONE: BigInt = of(1L)

        fun of(value: Long): BigInt {
            if (value == 0L) return ZERO
            val negative = value < 0L
            // Long.MIN_VALUE negates to itself; read as unsigned it is exactly 2^63, the right magnitude.
            val m = if (negative) -value else value
            return make(negative, intArrayOf(m.toInt(), (m ushr 32).toInt()))
        }

        private fun make(negative: Boolean, mag: IntArray): BigInt {
            val trimmed = trim(mag)
            return if (trimmed.isEmpty()) ZERO else BigInt(negative, trimmed)
        }

        private fun trim(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        private fun cmpMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices.reversed()) {
                val x = a[i].toLong() and MASK
                val y = b[i].toLong() and MASK
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val (long, short) = if (a.size >= b.size) a to b else b to a
            val out = IntArray(long.size + 1)
            var carry = 0L
            for (i in long.indices) {
                val s = (long[i].toLong() and MASK) + (if (i < short.size) short[i].toLong() and MASK else 0L) + carry
                out[i] = s.toInt()
                carry = s ushr 32
            }
            out[long.size] = carry.toInt()
            return trim(out)
        }

        /** `a − b` for `a ≥ b`. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                var d = (a[i].toLong() and MASK) - (if (i < b.size) b[i].toLong() and MASK else 0L) - borrow
                borrow = if (d < 0L) 1L else 0L
                if (d < 0L) d += 1L shl 32
                out[i] = d.toInt()
            }
            return trim(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                val x = a[i].toLong() and MASK
                var carry = 0L
                for (j in b.indices) {
                    val t = x * (b[j].toLong() and MASK) + (out[i + j].toLong() and MASK) + carry
                    out[i + j] = t.toInt()
                    carry = t ushr 32
                }
                out[i + b.size] = carry.toInt()
            }
            return trim(out)
        }

        private fun bitLengthMag(a: IntArray): Int =
            if (a.isEmpty()) 0 else (a.size - 1) * 32 + (32 - a[a.size - 1].countLeadingZeroBits())

        private fun shlMag(a: IntArray, bits: Int): IntArray {
            val limbs = bits / 32
            val shift = bits % 32
            val out = IntArray(a.size + limbs + 1)
            for (i in a.indices) {
                val v = a[i].toLong() and MASK
                val shifted = v shl shift
                out[i + limbs] = out[i + limbs] or shifted.toInt()
                out[i + limbs + 1] = out[i + limbs + 1] or (shifted ushr 32).toInt()
            }
            return trim(out)
        }

        private fun shrMag(a: IntArray, bits: Int): IntArray {
            val limbs = bits / 32
            if (limbs >= a.size) return IntArray(0)
            val shift = bits % 32
            val out = IntArray(a.size - limbs)
            for (i in out.indices) {
                val low = (a[i + limbs].toLong() and MASK) ushr shift
                val high = if (shift == 0 || i + limbs + 1 >= a.size) 0L else (a[i + limbs + 1].toLong() and MASK) shl (32 - shift)
                out[i] = (low or high).toInt()
            }
            return trim(out)
        }

        private fun trailingZeroBitsMag(a: IntArray): Int {
            for (i in a.indices) if (a[i] != 0) return i * 32 + a[i].countTrailingZeroBits()
            return 0
        }

        /**
         * Shift-and-subtract long division of magnitudes, one bit at a time. Quadratic in the bit
         * length, which is a few hundred bits at most here; [mulMag] dominates long before this does.
         */
        private fun divRemMag(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
            if (cmpMag(a, b) < 0) return IntArray(0) to a
            if (b.size == 1) return divRemSmall(a, b[0].toLong() and MASK)
            val bits = bitLengthMag(a)
            val q = IntArray(a.size)
            var r = IntArray(0)
            for (i in bits - 1 downTo 0) {
                r = shlMag(r, 1)
                if ((a[i / 32] ushr (i % 32)) and 1 == 1) {
                    r = if (r.isEmpty()) intArrayOf(1) else r.copyOf().also { it[0] = it[0] or 1 }
                }
                if (cmpMag(r, b) >= 0) {
                    r = subMag(r, b)
                    q[i / 32] = q[i / 32] or (1 shl (i % 32))
                }
            }
            return trim(q) to r
        }

        private fun divRemSmall(a: IntArray, d: Long): Pair<IntArray, IntArray> {
            val q = IntArray(a.size)
            var rem = 0L
            for (i in a.indices.reversed()) {
                // rem < d ≤ 2^32 − 1, so the running value fits in 64 bits unsigned; it is < 2^64 and
                // non-negative as a Long only while below 2^63, so divide unsigned.
                val cur = (rem shl 32) or (a[i].toLong() and MASK)
                val qq = cur.toULong() / d.toULong()
                q[i] = qq.toInt()
                rem = (cur.toULong() - qq * d.toULong()).toLong()
            }
            return trim(q) to (if (rem == 0L) IntArray(0) else intArrayOf(rem.toInt()))
        }

        /** Binary (Stein's) greatest common divisor: shifts and subtractions only. */
        private fun gcdMag(x: IntArray, y: IntArray): IntArray {
            if (x.isEmpty()) return y
            if (y.isEmpty()) return x
            val shift = minOf(trailingZeroBitsMag(x), trailingZeroBitsMag(y))
            var a = shrMag(x, trailingZeroBitsMag(x))
            var b = y
            while (b.isNotEmpty()) {
                b = shrMag(b, trailingZeroBitsMag(b))
                if (cmpMag(a, b) > 0) {
                    val t = a
                    a = b
                    b = t
                }
                b = subMag(b, a)
            }
            return shlMag(a, shift)
        }
    }
}
