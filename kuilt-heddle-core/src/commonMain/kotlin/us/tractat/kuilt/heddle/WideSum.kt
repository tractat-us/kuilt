package us.tractat.kuilt.heddle

/**
 * An exact signed 128-bit integer, two's complement over [hi] and [lo]: the value is
 * `hi · 2⁶⁴ + unsigned(lo)`.
 *
 * It exists so a running sum of `Long`s can be kept exactly. A sum folded through [checkedAdd] throws
 * when a *partial* sum leaves `Long`, and which partial sums occur depends on the order of the terms,
 * so a fold that is updated incrementally — terms added and removed as the ledger changes — could
 * throw on one history and not on another for the same state. A 128-bit sum of fewer than 2⁶³ terms
 * cannot overflow, so the order stops mattering, and the one check left is [toLongExact] on the
 * final result, which depends on the state alone. That check still throws rather than wraps
 * (design §10.12).
 */
internal data class Int128(val hi: Long, val lo: Long) {

    operator fun plus(v: Long): Int128 = WideSum(this).also { it.add(v) }.toInt128()

    operator fun minus(v: Long): Int128 = WideSum(this).also { it.sub(v) }.toInt128()

    operator fun plus(v: Int128): Int128 = WideSum(this).also { it.add(v) }.toInt128()

    operator fun minus(v: Int128): Int128 = WideSum(this).also { it.sub(v) }.toInt128()

    /** This value as a `Long`, or [ArithmeticException] when it does not fit. */
    fun toLongExact(): Long {
        if (hi != (lo shr 63)) throw ArithmeticException("Long overflow: 128-bit value ($hi, $lo) does not fit")
        return lo
    }

    companion object {
        val ZERO: Int128 = Int128(0L, 0L)

        fun of(v: Long): Int128 = Int128(v shr 63, v)
    }
}

/**
 * A mutable [Int128] accumulator, for a hot loop that would otherwise allocate one value per term.
 * Local to one call; never shared.
 */
internal class WideSum(start: Int128 = Int128.ZERO) {
    private var hi: Long = start.hi
    private var lo: Long = start.lo

    fun add(v: Long) = addParts(v shr 63, v)

    fun sub(v: Long) = subParts(v shr 63, v)

    fun add(v: Int128) = addParts(v.hi, v.lo)

    fun sub(v: Int128) = subParts(v.hi, v.lo)

    private fun addParts(vHi: Long, vLo: Long) {
        val sum = lo + vLo
        // Unsigned carry out of the low word: the wrapped sum is below either addend.
        val carry = if (unsignedLess(sum, lo)) 1L else 0L
        hi = hi + vHi + carry
        lo = sum
    }

    private fun subParts(vHi: Long, vLo: Long) {
        // Unsigned borrow into the low word.
        val borrow = if (unsignedLess(lo, vLo)) 1L else 0L
        lo -= vLo
        hi = hi - vHi - borrow
    }

    fun toInt128(): Int128 = Int128(hi, lo)

    fun toLongExact(): Long = toInt128().toLongExact()

    private companion object {
        fun unsignedLess(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) < (b xor Long.MIN_VALUE)
    }
}
