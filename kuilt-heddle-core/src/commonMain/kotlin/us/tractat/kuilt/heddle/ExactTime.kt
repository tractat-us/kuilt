package us.tractat.kuilt.heddle

/**
 * An exact rational virtual time of **any** size: the arithmetic the policy computes in, where a
 * [Rational] is only what it stores.
 *
 * ## Why the policy cannot compute in [Rational]
 *
 * A [Rational] is a pair of `Long`s, so every product it forms must fit in 63 bits. The policy's
 * weighted mean `V = Σ w·ev / Σ w` divides by a sum of weights that changes with the set it is
 * taken over, and a wake clamp stores `V − ev` as a child's offset, which feeds the next mean. Each
 * time the waiting set changes, the stored offsets' denominators are multiplied by another `Σ w`,
 * so a pick overflowed `Long` within tens of picks of a waiting set that moved between picks. Under
 * fractional weights the same arithmetic overflows on magnitude alone once enough service has
 * passed (#3231). Neither is a state a caller can avoid.
 *
 * So the policy computes in [ExactTime], which cannot overflow, and every value that **leaves** the
 * policy — a wake offset the allocator remembers, a front a newborn is seated at, a gauge
 * checkpoint, a diagnostic — goes through [toRational], the one narrowing back to the stored type.
 *
 * ## The narrowing, and its bias
 *
 * [toRational] returns the exact value whenever a [Rational] can hold it. Only a value that has no
 * `Long` form is rounded, and it is rounded **up**, to the finest power-of-two grid whose numerator
 * still fits. Up is the side design §10.5 allows: a virtual time read slightly high only ever gives
 * a turn away, never takes one.
 *
 * That split is what keeps every result the old `Long` arithmetic produced. That arithmetic threw on
 * any value it could not represent, so wherever it returned at all, it returned a value [toRational]
 * passes through unchanged, and the comparisons made here in exact arithmetic agree with the ones it
 * made. Rounding happens only past the point where it would have thrown.
 *
 * It is also what bounds the state. A stored virtual time is a [Rational], two `Long`s, however many
 * means it has been through: the compounding is cut at the store, every time, instead of carried
 * into the next mean. The values computed here grow with the number of terms in one sum, and with
 * nothing that accumulates across picks.
 */
internal class ExactTime private constructor(
    val numerator: BigInt,
    val denominator: BigInt,
) : Comparable<ExactTime> {

    operator fun plus(other: ExactTime): ExactTime =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    operator fun minus(other: ExactTime): ExactTime =
        of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    operator fun times(other: ExactTime): ExactTime =
        of(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: ExactTime): ExactTime {
        if (other.numerator.isZero) throw ArithmeticException("ExactTime division by zero")
        return of(numerator * other.denominator, denominator * other.numerator)
    }

    override fun compareTo(other: ExactTime): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    /**
     * This value as a [Rational]: exact when a [Rational] can hold it, otherwise the least value at
     * or above it on the finest power-of-two grid whose numerator fits in a `Long`. See the class
     * KDoc for why up, and why the exact case is never rounded.
     *
     * @throws ArithmeticException when the value is too large in magnitude for any `Long` numerator
     *   even at the coarsest grid, `2⁶³` or beyond: a virtual time no `Long` service total can reach
     *   at a weight of at least `1/Long.MAX_VALUE`.
     */
    fun toRational(): Rational {
        val n = numerator.toLongOrNull()
        val d = denominator.toLongOrNull()
        // Long.MIN_VALUE has no negation, so no Rational carries it as a numerator.
        if (n != null && d != null && n != Long.MIN_VALUE) return Rational.of(n, d)
        // Bits of the integer part, give or take one; start a step above the estimate and walk down.
        val magnitudeBits = numerator.bitLength() - denominator.bitLength()
        var k = (MAX_GRID_BITS - magnitudeBits + 1).coerceIn(0, MAX_GRID_BITS)
        while (k >= 0) {
            val scaled = ceilDiv(numerator.shl(k), denominator).toLongOrNull()
            if (scaled != null) return Rational.of(scaled, 1L shl k)
            k--
        }
        throw ArithmeticException("virtual time $this has no Long form at any grid")
    }

    override fun equals(other: Any?): Boolean =
        other is ExactTime && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        /** The finest grid is `2⁻⁶²`, so the grid denominator is itself a positive `Long`. */
        private const val MAX_GRID_BITS = 62

        val ZERO: ExactTime = ExactTime(BigInt.ZERO, BigInt.ONE)

        fun of(value: Rational): ExactTime = ExactTime(BigInt.of(value.numerator), BigInt.of(value.denominator))

        fun of(value: Long): ExactTime = ExactTime(BigInt.of(value), BigInt.ONE)

        /** The weight [w] as a value, `num/den`. */
        fun of(w: Weight): ExactTime = ExactTime(BigInt.of(w.numerator), BigInt.of(w.denominator))

        /** [units] of service at weight [w], `units · den / num`: [perWeight] without the `Long` ceiling. */
        fun perWeight(units: Long, w: Weight): ExactTime =
            of(BigInt.of(units) * BigInt.of(w.denominator), BigInt.of(w.numerator))

        fun max(a: ExactTime, b: ExactTime): ExactTime = if (a >= b) a else b

        /** Reduced to lowest terms with a positive denominator, so equal values are equal. */
        private fun of(numerator: BigInt, denominator: BigInt): ExactTime {
            if (denominator.isZero) throw ArithmeticException("ExactTime denominator must be non-zero")
            if (numerator.isZero) return ZERO
            val g = numerator.gcd(denominator)
            val sign = if (denominator.signum < 0) BigInt.of(-1L) else BigInt.ONE
            val n = if (g == BigInt.ONE) numerator else numerator.divRem(g).first
            val d = if (g == BigInt.ONE) denominator else denominator.divRem(g).first
            return ExactTime(n * sign, d * sign)
        }

        /** `⌈a / b⌉` for `b > 0`. */
        private fun ceilDiv(a: BigInt, b: BigInt): BigInt {
            val (q, r) = a.divRem(b)
            return if (r.signum > 0) q + BigInt.ONE else q
        }
    }
}

/**
 * The **gross** virtual service an edge reads at base issuance [baseIssued], exactly: the one copy of
 * the gauge read ([grossVirtualServiceAt] narrows this), so the checkpoint `delegate` writes and the
 * value the policy reads cannot drift.
 */
internal fun Gauge?.exactGrossVirtualServiceAt(baseIssued: Long, weight: Weight): ExactTime =
    if (this == null) {
        ExactTime.perWeight(baseIssued, weight)
    } else {
        ExactTime.of(floor) + ExactTime.perWeight(checkedSub(baseIssued, folded), weight)
    }
