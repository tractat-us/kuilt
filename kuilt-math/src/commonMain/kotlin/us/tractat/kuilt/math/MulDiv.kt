package us.tractat.kuilt.math

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
