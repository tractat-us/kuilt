package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * A sibling's fairness **share**, expressed as a positive integer ratio.
 *
 * Weights only ever have meaning *relative to their siblings* — "three parts to
 * one" — so a `Weight` is a `numerator`/`denominator` pair, reduced to lowest
 * terms on construction (`2/4` and `1/2` are the same weight). Two weights are
 * ordered by **exact cross-multiplication** with overflow detection — never by
 * converting to `Double` — because the scheduler's ordering must be bit-identical
 * on JVM, Native, and wasmJs, and floating point is not (design §2). A comparison
 * that would overflow `Long` throws rather than silently returning a wrong order.
 *
 * @property numerator the ratio's numerator; always `> 0` and coprime with [denominator].
 * @property denominator the ratio's denominator; always `> 0` and coprime with [numerator].
 */
@Serializable
public class Weight private constructor(
    public val numerator: Long,
    public val denominator: Long,
) : Comparable<Weight> {

    /**
     * Order by exact cross-multiplication: `a/b <=> c/d` compares `a*d` against
     * `c*b`. Both products are overflow-checked, so an out-of-range comparison
     * throws [ArithmeticException] rather than wrapping to a wrong verdict.
     */
    override fun compareTo(other: Weight): Int =
        checkedMul(numerator, other.denominator).compareTo(checkedMul(other.numerator, denominator))

    override fun equals(other: Any?): Boolean =
        other is Weight && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "Weight($numerator/$denominator)"

    public companion object {
        /** The unit weight `1/1`. */
        public val ONE: Weight = Weight(1L, 1L)

        /** A whole-number weight [n] (i.e. `n/1`); [n] must be positive. */
        public fun of(n: Long): Weight {
            require(n > 0L) { "Weight must be positive, was $n" }
            return Weight(n, 1L)
        }

        /**
         * The reduced weight [numerator]/[denominator]; both must be positive.
         * Reduced to lowest terms so equal ratios are structurally equal.
         */
        public fun of(numerator: Long, denominator: Long): Weight {
            require(numerator > 0L) { "Weight numerator must be positive, was $numerator" }
            require(denominator > 0L) { "Weight denominator must be positive, was $denominator" }
            val g = gcd(numerator, denominator)
            return Weight(numerator / g, denominator / g)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
