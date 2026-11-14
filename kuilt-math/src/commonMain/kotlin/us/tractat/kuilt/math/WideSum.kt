package us.tractat.kuilt.math

/**
 * A running [Int128] sum that changes in place, for a loop that would otherwise allocate a new
 * [Int128] for every term.
 *
 * A sum of `Long`s kept here is exact in any order. Folded through [plusOrNull] instead, a sum
 * refuses as soon as a *partial* sum leaves `Long`, and which partial sums occur depends on the
 * order of the terms, so a total kept up to date as terms come and go could refuse on one history
 * and not on another for the same terms. Here the order stops mattering, and the one question left
 * is whether the final [toInt128] fits wherever it goes next, which [Int128.toLongOrNull] answers.
 *
 * Every [add] and [sub] follows [Int128.plus] and [Int128.minus]: a sum that would leave 128 bits
 * throws [ArithmeticException] and leaves this sum as it was. It is never a silent wrap. With
 * `Long` terms that takes more than 2⁶⁴ of them, so a throw is a caller bug. With [Int128] terms it
 * can be data: two [timesWide] products are enough.
 *
 * Not safe to share between threads; keep one local to a call.
 *
 * @sample us.tractat.kuilt.math.sampleWideSum
 */
public class WideSum {
    private var high: Long = 0L
    private var low: Long = 0L

    /** Add [value]. Throws [ArithmeticException], leaving the sum unchanged, if it leaves 128 bits. */
    public fun add(value: Long): Unit = addWords(value shr 63, value)

    /** Subtract [value]. Throws [ArithmeticException], leaving the sum unchanged, if it leaves 128 bits. */
    public fun sub(value: Long): Unit = subWords(value shr 63, value)

    /** Add [value]. Throws [ArithmeticException], leaving the sum unchanged, if it leaves 128 bits. */
    public fun add(value: Int128): Unit = addWords(value.high, value.low)

    /** Subtract [value]. Throws [ArithmeticException], leaving the sum unchanged, if it leaves 128 bits. */
    public fun sub(value: Int128): Unit = subWords(value.high, value.low)

    /** The sum so far. */
    public fun toInt128(): Int128 = Int128.fromTwosComplement(high, low)

    /** The sum so far, in decimal. */
    override fun toString(): String = toInt128().toString()

    private fun addWords(vHigh: Long, vLow: Long) {
        val lo = low + vLow
        val carry = if (unsignedLess(lo, low)) 1L else 0L
        val hi = high + vHigh + carry
        high = hi
        low = lo
    }

    private fun subWords(vHigh: Long, vLow: Long) {
        val lo = low - vLow
        val borrow = if (unsignedLess(low, vLow)) 1L else 0L
        val hi = high - vHigh - borrow
        high = hi
        low = lo
    }
}
