package us.tractat.kuilt.math

/**
 * A slow, obviously-correct signed integer of any size, for checking `:kuilt-math` on every target.
 *
 * The JVM has `BigInteger` and the JVM oracle test uses it; Kotlin/Native and wasmJs have nothing
 * like it, and their integer paths are exactly where a word-level bug would hide. So this is a
 * second, independent construction: a sign and a magnitude in base 2¹⁶, little-endian, with
 * schoolbook add, subtract and multiply and bit-by-bit long division. Every limb and every carry
 * fits an `Int` product inside a `Long`, so nothing here can itself overflow, and none of it shares
 * code or representation with the two-word [Int128] it checks. `BigOracleTest` pins it against
 * `BigInteger` on the JVM, so the oracle is itself checked before it checks anything.
 */
internal class Big private constructor(
    /** -1, 0 or 1. */
    val sign: Int,
    /** Base-2¹⁶ limbs, least significant first, no trailing zeros; empty for zero. */
    private val mag: IntArray,
) : Comparable<Big> {

    operator fun plus(o: Big): Big = when {
        sign == 0 -> o
        o.sign == 0 -> this
        sign == o.sign -> Big(sign, addMag(mag, o.mag))
        else -> {
            val c = cmpMag(mag, o.mag)
            when {
                c == 0 -> ZERO
                c > 0 -> Big(sign, subMag(mag, o.mag))
                else -> Big(o.sign, subMag(o.mag, mag))
            }
        }
    }

    operator fun unaryMinus(): Big = Big(-sign, mag)

    operator fun minus(o: Big): Big = this + (-o)

    operator fun times(o: Big): Big = if (sign == 0 || o.sign == 0) ZERO else Big(sign * o.sign, mulMag(mag, o.mag))

    /** Floor division by a non-zero [d]: the quotient rounded toward negative infinity. */
    fun floorDiv(d: Big): Big {
        val (q, r) = truncDivRem(d)
        return if (r.sign != 0 && r.sign != d.sign) q - ONE else q
    }

    /** Ceiling division by a non-zero [d]. */
    fun ceilDiv(d: Big): Big = -((-this).floorDiv(d))

    /** Truncated division by a non-zero [d], as Kotlin's `/` and `%` on `Long`. */
    fun truncDivRem(d: Big): Pair<Big, Big> {
        require(d.sign != 0)
        val (q, r) = divMag(mag, d.mag)
        return Big.of(sign * d.sign, q) to Big.of(sign, r)
    }

    override fun compareTo(other: Big): Int = when {
        sign != other.sign -> sign.compareTo(other.sign)
        sign >= 0 -> cmpMag(mag, other.mag)
        else -> cmpMag(other.mag, mag)
    }

    override fun equals(other: Any?): Boolean = other is Big && compareTo(other) == 0

    override fun hashCode(): Int = sign * 31 + mag.contentHashCode()

    /** This value as a `Long`, or `null` when it does not fit. */
    fun toLongOrNull(): Long? = if (this < LONG_MIN || this > LONG_MAX) null else toLongWrapping()

    private fun toLongWrapping(): Long {
        var v = 0L
        for (i in mag.indices.reversed()) v = (v shl 16) or mag[i].toLong()
        return if (sign < 0) -v else v
    }

    override fun toString(): String {
        if (sign == 0) return "0"
        val digits = StringBuilder()
        var m = mag
        val ten = intArrayOf(10)
        while (m.isNotEmpty()) {
            val (q, r) = divMag(m, ten)
            digits.append(if (r.isEmpty()) 0 else r[0])
            m = q
        }
        if (sign < 0) digits.append('-')
        return digits.reverse().toString()
    }

    companion object {
        // Declared first: `of` reads it, and companion properties initialise in order.
        private val TWO_TO_64_RAW = Big(1, intArrayOf(0, 0, 0, 0, 1))
        val ZERO = Big(0, IntArray(0))
        val ONE = of(1L)
        val LONG_MIN = of(Long.MIN_VALUE)
        val LONG_MAX = of(Long.MAX_VALUE)
        val TWO_TO_64 = of(1L shl 32) * of(1L shl 32)

        /** [v] exactly, `Long.MIN_VALUE` included: its magnitude is built limb by limb, never negated. */
        fun of(v: Long): Big {
            if (v == 0L) return ZERO
            val limbs = IntArray(4) { i -> ((v ushr (16 * i)) and 0xFFFF).toInt() }
            val raw = Big(1, trim(limbs))
            // Read as unsigned and correct by 2⁶⁴ when negative: no negation of v is needed.
            return if (v < 0L) raw - TWO_TO_64_RAW else raw
        }

        /** The value whose two's complement words are [high] and [low]: `high · 2⁶⁴ + unsigned(low)`. */
        fun ofWords(high: Long, low: Long): Big = of(high) * TWO_TO_64 + unsigned(low)

        /** [v]'s 64 bits read as an unsigned number. */
        fun unsigned(v: Long): Big =
            if (v == 0L) ZERO else Big(1, trim(IntArray(4) { i -> ((v ushr (16 * i)) and 0xFFFF).toInt() }))

        fun of(int128: Int128): Big = ofWords(int128.high, int128.low)

        private fun of(sign: Int, mag: IntArray): Big = if (mag.isEmpty()) ZERO else Big(sign, mag)

        private fun trim(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return a.copyOf(n)
        }

        private fun cmpMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices.reversed()) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(maxOf(a.size, b.size) + 1)
            var carry = 0
            for (i in out.indices) {
                val s = (if (i < a.size) a[i] else 0) + (if (i < b.size) b[i] else 0) + carry
                out[i] = s and 0xFFFF
                carry = s ushr 16
            }
            return trim(out)
        }

        /** `a − b` for `a ≥ b`. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0
            for (i in a.indices) {
                var s = a[i] - (if (i < b.size) b[i] else 0) - borrow
                borrow = if (s < 0) 1 else 0
                if (s < 0) s += 0x10000
                out[i] = s
            }
            return trim(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = LongArray(a.size + b.size)
            for (i in a.indices) for (j in b.indices) out[i + j] += a[i].toLong() * b[j].toLong()
            val limbs = IntArray(out.size + 1)
            var carry = 0L
            for (k in out.indices) {
                val s = out[k] + carry
                limbs[k] = (s and 0xFFFF).toInt()
                carry = s ushr 16
            }
            limbs[out.size] = carry.toInt()
            return trim(limbs)
        }

        /** Bit-by-bit long division of magnitudes: quotient and remainder. */
        private fun divMag(a: IntArray, d: IntArray): Pair<IntArray, IntArray> {
            var r = IntArray(0)
            val q = IntArray(a.size)
            for (bit in a.size * 16 - 1 downTo 0) {
                r = addMag(r, r) // r · 2
                if ((a[bit / 16] ushr (bit % 16)) and 1 == 1) r = addMag(r, intArrayOf(1))
                if (cmpMag(r, d) >= 0) {
                    r = subMag(r, d)
                    q[bit / 16] = q[bit / 16] or (1 shl (bit % 16))
                }
            }
            return trim(q) to r
        }
    }
}
