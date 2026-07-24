package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * An **exact rational number** — a `numerator/denominator` pair over `Long`, kept
 * reduced to lowest terms with a strictly positive denominator.
 *
 * The scheduler's virtual times are rationals (`b + committedService / weight`,
 * design §7.1) and the whole point of the policy is that **every replica orders
 * children bit-identically** on JVM, Native, and wasmJs. Floating point does not
 * give that — `0.1 + 0.2` is not `0.3` and rounds differently across platforms — so
 * the policy never converts a virtual time to `Double`/`Float`. All arithmetic here
 * is exact integer math, reduced after every operation to keep magnitudes small, and
 * **overflow-checked**: an add or multiply that would exceed `Long.MAX_VALUE` throws
 * [ArithmeticException] rather than wrapping to a wrong order (the same discipline as
 * [Weight] and [CheckedMath]).
 *
 * `BigInteger` would sidestep overflow entirely but is a JVM-only type — unavailable
 * in this module's `commonMain` — so the exact-but-bounded `Long` rational is the
 * portable choice; the reduce-after-every-op keeps realistic scheduler workloads far
 * from the ceiling.
 *
 * @property numerator the reduced numerator; may be negative, zero, or positive.
 * @property denominator the reduced denominator; always `> 0` and coprime with [numerator].
 */
@Serializable
public class Rational private constructor(
    public val numerator: Long,
    public val denominator: Long,
) : Comparable<Rational> {

    /** The exact sum, reduced. Throws [ArithmeticException] on `Long` overflow. */
    public operator fun plus(other: Rational): Rational =
        of(
            checkedAdd(
                checkedMul(numerator, other.denominator),
                checkedMul(other.numerator, denominator),
            ),
            checkedMul(denominator, other.denominator),
        )

    /** The exact difference, reduced. Throws [ArithmeticException] on `Long` overflow. */
    public operator fun minus(other: Rational): Rational =
        of(
            checkedSub(
                checkedMul(numerator, other.denominator),
                checkedMul(other.numerator, denominator),
            ),
            checkedMul(denominator, other.denominator),
        )

    /** The exact product, reduced. Throws [ArithmeticException] on `Long` overflow. */
    public operator fun times(other: Rational): Rational =
        of(checkedMul(numerator, other.numerator), checkedMul(denominator, other.denominator))

    /** The exact quotient, reduced. Throws on `Long` overflow or division by zero. */
    public operator fun div(other: Rational): Rational {
        if (other.numerator == 0L) throw ArithmeticException("Rational division by zero")
        return of(checkedMul(numerator, other.denominator), checkedMul(denominator, other.numerator))
    }

    /**
     * Order by exact cross-multiplication: `a/b <=> c/d` compares `a*d` against
     * `c*b`. Both denominators are positive, so no sign flip is needed. Products are
     * overflow-checked, so an out-of-range comparison throws rather than wrapping to
     * a wrong verdict.
     */
    override fun compareTo(other: Rational): Int =
        checkedMul(numerator, other.denominator).compareTo(checkedMul(other.numerator, denominator))

    override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    public companion object {
        /** The rational `0/1`. */
        public val ZERO: Rational = Rational(0L, 1L)

        /** The rational `1/1`. */
        public val ONE: Rational = Rational(1L, 1L)

        /** The whole number [n] as `n/1`. */
        public fun of(n: Long): Rational = Rational(n, 1L)

        /**
         * The reduced rational [numerator]/[denominator]. The sign is normalized so the
         * stored denominator is always positive; the fraction is reduced to lowest
         * terms so equal values are structurally equal ([equals]/[compareTo] agree).
         *
         * @throws ArithmeticException if [denominator] is zero.
         */
        public fun of(numerator: Long, denominator: Long): Rational {
            if (denominator == 0L) throw ArithmeticException("Rational denominator must be non-zero")
            // Normalize sign onto the numerator (denominator strictly positive).
            var n = numerator
            var d = denominator
            if (d < 0L) {
                n = checkedNegate(n)
                d = checkedNegate(d)
            }
            if (n == 0L) return ZERO
            val g = gcd(if (n < 0L) checkedNegate(n) else n, d)
            return Rational(n / g, d / g)
        }

        /** The larger of two rationals ([a] if equal). */
        public fun max(a: Rational, b: Rational): Rational = if (a >= b) a else b

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
