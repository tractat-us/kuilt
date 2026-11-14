package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128

/**
 * A mutable [Int128] accumulator, for a hot loop that would otherwise allocate one value per term.
 * Local to one call; never shared.
 *
 * It exists so a running sum of `Long`s can be kept exactly. A sum folded through [checkedAdd] throws
 * when a *partial* sum leaves `Long`, and which partial sums occur depends on the order of the terms,
 * so a fold that is updated incrementally — terms added and removed as the ledger changes — could
 * throw on one history and not on another for the same state. A 128-bit sum of fewer than 2⁶³ terms
 * cannot overflow, so the order stops mattering, and the one check left is [toLongExact] on the
 * final result, which depends on the state alone. That check still throws rather than wraps
 * (design §10.12). So a sum kept here throws on fewer inputs than the same terms folded through
 * [checkedAdd], never on more, and returns the same value wherever that fold returns one.
 */
internal class WideSum(start: Int128 = Int128.ZERO) {
    private var hi: Long = start.high
    private var lo: Long = start.low

    fun add(v: Long) = addParts(v shr 63, v)

    fun sub(v: Long) = subParts(v shr 63, v)

    fun add(v: Int128) = addParts(v.high, v.low)

    fun sub(v: Int128) = subParts(v.high, v.low)

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

    fun toInt128(): Int128 = Int128.fromTwosComplement(hi, lo)

    fun toLongExact(): Long = toInt128().toLongExact()

    private companion object {
        fun unsignedLess(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) < (b xor Long.MIN_VALUE)
    }
}

/** This value as a `Long`, or [ArithmeticException] when it does not fit (design §10.12). */
internal fun Int128.toLongExact(): Long =
    toLongOrNull() ?: throw ArithmeticException("Long overflow: 128-bit value $this does not fit")
