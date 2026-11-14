package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.compareProducts
import us.tractat.kuilt.math.timesWide

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
 * **Deliberately not `@Serializable`.** The generated serializer would deserialize past
 * [of] — the only path that reduces and forces a positive denominator — so an unreduced
 * or sign-denormalized value could arrive over the wire and break [equals]/[compareTo]
 * exactly as it could for [Weight] (#1647). Leaving the annotation off makes that
 * unreachable *by default*: a type that wants to replicate a `Rational` cannot simply
 * inherit wire-legality, it has to name a normalizing serializer at the property.
 *
 * **Replication is opt-in, per site, through [RationalSerializer]** — the normalizing
 * serializer routed through [of] that this stance always prescribed, and the same shape as
 * [WeightSerializer]. Two sites exist today: [PolicyEdge.virtualOffset] is scheduler-local
 * and design §7.2 explicitly does *not* replicate it, while [Gauge.floor] **is** replicated
 * (issue #1752) and carries `@Serializable(with = RationalSerializer::class)`. Annotate the
 * property, never this class — that is what keeps the default closed.
 *
 * @property numerator the reduced numerator; may be negative, zero, or positive.
 * @property denominator the reduced denominator; always `> 0` and coprime with [numerator].
 */
public class Rational private constructor(
    public val numerator: Long,
    public val denominator: Long,
) : Comparable<Rational> {

    /**
     * The exact sum, reduced. Throws [ArithmeticException] only when the **answer** does not fit in
     * `Long`; see [combine] for why the intermediates never decide that.
     */
    public operator fun plus(other: Rational): Rational = combine(other, subtract = false)

    /** The exact difference, reduced. Throws [ArithmeticException] only when the answer does not fit. */
    public operator fun minus(other: Rational): Rational = combine(other, subtract = true)

    /**
     * `this ± other` by Knuth's gcd-first method (TAOCP §4.5.1), with the numerator formed in 128
     * bits. The textbook `(a·d ± c·b) / (b·d)` builds `b·d` before reducing, so `1/2³² + 3/2³²`
     * threw on the way to `1/2³⁰` (#3313). Here `g = gcd(b, d)` comes out first: the numerator is
     * `t = a·(d/g) ± c·(b/g)` and the denominator `(b/g)·d`, and since both operands are in lowest
     * terms the only factor `t` can share with that denominator is one it shares with `g`. So
     * dividing by `g₂ = gcd(t, g)` leaves the answer in lowest terms, and the only overflow left is
     * the answer's own.
     */
    private fun combine(other: Rational, subtract: Boolean): Rational {
        val g = gcd(denominator, other.denominator)
        val thisScale = other.denominator / g
        val otherScale = denominator / g
        val left = numerator.timesWide(thisScale)
        val right = other.numerator.timesWide(otherScale)
        val t = if (subtract) left - right else left + right
        if (t == Int128.ZERO) return ZERO
        val remainder = t % g
        val g2 = gcd(if (remainder < 0L) -remainder else remainder, g) // |remainder| < g, so no overflow
        val reduced = t.divideOrNull(g2, Rounding.FLOOR) // exact: g2 divides t
            ?: throw ArithmeticException("Long overflow: $this ${if (subtract) "-" else "+"} $other")
        return of(reduced, checkedMul(otherScale, other.denominator / g2))
    }

    /**
     * The exact product, reduced. Throws [ArithmeticException] only when the **answer** does not fit
     * in `Long`; see [crossReduced] for why the intermediates never decide that.
     */
    public operator fun times(other: Rational): Rational =
        crossReduced(numerator, other.numerator, denominator, other.denominator)

    /**
     * The exact quotient, reduced. Throws [ArithmeticException] on division by zero, or when the
     * answer does not fit in `Long`.
     */
    public operator fun div(other: Rational): Rational {
        if (other.numerator == 0L) throw ArithmeticException("Rational division by zero")
        return crossReduced(numerator, other.denominator, denominator, other.numerator)
    }

    /**
     * Order by exact cross-multiplication: `a/b <=> c/d` compares `a·d` against `c·b`. Both
     * denominators are positive, so no sign flip is needed. The products are formed in 128 bits,
     * where any two `Long`s multiply exactly, so every pair of rationals orders and none throws
     * (#3313). A compare that threw used to wedge [Gauge.join] and every pick that sorts by time.
     */
    override fun compareTo(other: Rational): Int =
        compareProducts(numerator, other.denominator, other.numerator, denominator)

    /**
     * The **exact ceiling** — the least `Long` that is `>= this`. `7/2` ceils to `4`,
     * `-7/2` to `-3`, and a whole number to itself.
     *
     * This is the only sanctioned way to land an exact virtual time on a `Long` field. Note
     * that Kotlin's `/` on `Long` truncates toward zero — for the non-negative virtual times
     * the scheduler deals in that is a *floor*, which rounds the wrong way for fairness: it
     * would seat a joiner *behind* the front, and lower virtual service reads as "has had less
     * than its share", so the joiner would take the next grants outright (§10.5's forbidden
     * lifetime credit). **No production path rounds a virtual time any more** — the seat lives
     * in [Gauge.floor], which is a [Rational] (#1752), so the one caller this rule existed for
     * is gone. It stays because the rounding direction is the non-obvious part, and the next
     * `Long` virtual-time field would get it wrong.
     */
    public fun ceil(): Long {
        val quotient = numerator / denominator
        // The denominator is strictly positive, so the remainder carries the numerator's sign;
        // a positive remainder means the truncation went down and one step back up is owed.
        return if (numerator % denominator > 0L) checkedAdd(quotient, 1L) else quotient
    }

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
            if (numerator == 0L) return ZERO
            // Reduce before normalizing the sign, and on the non-positive side, where Long.MIN_VALUE
            // has a magnitude (#3347): MIN_VALUE/4 and MIN_VALUE/-2 both fit once reduced, and only an
            // answer whose own denominator is 2⁶³, or whose numerator is +2⁶³, still throws below.
            val negativeG = negativeGcd(nonPositive(numerator), nonPositive(denominator))
            if (negativeG == Long.MIN_VALUE) return ONE // both are MIN_VALUE: nothing else has |g| = 2⁶³
            val g = -negativeG
            val n = numerator / g
            val d = denominator / g
            // Normalize the sign onto the numerator, so the stored denominator is strictly positive.
            return if (d < 0L) Rational(checkedNegate(n), checkedNegate(d)) else Rational(n, d)
        }

        /**
         * `(a·b) / (c·d)` in lowest terms, where `a/c` and `b/d` are each already in lowest terms
         * (#3347). Multiplying straight across and reducing afterwards throws whenever a product
         * leaves `Long`, even when the answer fits: `(2⁶²+1)/3 × 3/(2⁶²+1)` threw on the way to `1`.
         * Cancelling each numerator against the *other* denominator first leaves the two products
         * coprime, because nothing else they could share survives the operands' own reduction, so
         * the products are the answer and the only overflow left is the answer's own.
         *
         * Magnitudes are held negated, since only the non-positive side holds `|Long.MIN_VALUE|`,
         * and the sign is applied once at the end: an answer of `Long.MIN_VALUE / k` has a
         * magnitude of `2⁶³` on the way, which no `Long` holds.
         */
        private fun crossReduced(a: Long, b: Long, c: Long, d: Long): Rational {
            if (a == 0L || b == 0L) return ZERO
            val na = nonPositive(a)
            val nb = nonPositive(b)
            val nc = nonPositive(c)
            val nd = nonPositive(d)
            val ad = negativeGcd(na, nd)
            val bc = negativeGcd(nb, nc)
            val magnitude = cancel(na, ad).timesWide(cancel(nb, bc)) // |the answer's numerator|
            val denominator = cancel(nc, bc).timesWide(cancel(nd, ad)).toLongOrNull()
            val negative = (a < 0L) != (b < 0L) != (c < 0L) != (d < 0L)
            val numerator = (if (negative) Int128.ZERO - magnitude else magnitude).toLongOrNull()
            if (numerator == null || denominator == null) {
                throw ArithmeticException("Long overflow: ($a·$b)/($c·$d)")
            }
            return Rational(numerator, denominator) // already coprime, and the denominator is positive
        }

        /**
         * `-(|x| / |g|)`, from `nx = -|x|` and `g` minus a gcd, without overflow: `-g` is a positive
         * `Long` unless `g` is `Long.MIN_VALUE`, which divides only `Long.MIN_VALUE` itself.
         */
        private fun cancel(nx: Long, g: Long): Long = if (g == Long.MIN_VALUE) -1L else nx / -g

        /** `-|x|`, which unlike `|x|` exists for every `Long`. */
        private fun nonPositive(x: Long): Long = if (x > 0L) -x else x

        /**
         * Minus the gcd of `|x|` and `|y|`, for `x, y ≤ 0` not both zero. Euclid runs on the
         * non-positive side, where `Long.MIN_VALUE` has a magnitude: the gcd of two `Long.MIN_VALUE`s
         * is `2⁶³`, which only its negation can hold.
         */
        private tailrec fun negativeGcd(x: Long, y: Long): Long = if (y == 0L) x else negativeGcd(y, x % y)

        /** The larger of two rationals ([a] if equal). */
        public fun max(a: Rational, b: Rational): Rational = if (a >= b) a else b

        /** The gcd of two non-negative `Long`s, not both zero. */
        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/**
 * Decodes a [Rational] **through [Rational.of]**, so a value that arrived over the wire is
 * indistinguishable from one the factory built (#1647, #1752).
 *
 * [Rational] is deliberately not `@Serializable` (see its KDoc): the plugin-generated
 * serializer would write straight into the private primary constructor, skipping the only
 * path that reduces to lowest terms and forces a positive denominator. An unreduced `2/4`
 * would then be structurally unequal to `1/2`, and a negative denominator would invert
 * [Rational.compareTo] — whose cross-multiplication assumes a positive denominator — so a
 * corrupt or hostile peer could **flip sibling ordering cluster-wide** by encoding `1/2` as
 * `-1/-2`. Where a `Rational` genuinely must be replicated the property names this
 * serializer explicitly; [Gauge.floor] is the one such site today.
 *
 * **Repair where a canonical form exists; refuse only where none does** — the same split
 * [WeightSerializer] makes, and for the same reason, which applies verbatim here because a
 * [Gauge] rides the wire inside the very same [EntitlementLedger] frames a [Weight] does.
 * A decode failure is expensive at both boundaries that carry this state. `Quilter`'s delta
 * dispatch drops the **entire frame**, so rejecting a merely denormalized encoding would discard
 * every legitimate record travelling with it, and anti-entropy would re-send the same frame
 * forever: a silent, permanent convergence wedge between two peers over a difference with no
 * semantic content. The heddle control plane's `applyEntry` latches on it, which stops the whole
 * group at that entry. Sign-normalization and reduction have a unique correct answer, so they are
 * applied — [Rational.of] does both.
 *
 * A **zero denominator** names no rational at all. There is nothing to repair to, and
 * substituting a default would fabricate a virtual-time claim out of hostile input — a
 * fairness-relevant lie, since a gauge floor is what seats an edge. That throws
 * [SerializationException] at the decode boundary, before any lattice state is touched; the
 * frame is dropped and the merge loop survives.
 */
public object RationalSerializer : KSerializer<Rational> {

    /**
     * Field-for-field what the generated form would have been, including the serial name, so
     * a future decision to annotate the class instead would be wire-compatible with anything
     * already persisted or in flight.
     */
    @Serializable
    @SerialName("us.tractat.kuilt.heddle.Rational")
    private data class Surrogate(val numerator: Long, val denominator: Long)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Rational) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(value.numerator, value.denominator),
        )
    }

    override fun deserialize(decoder: Decoder): Rational {
        val (numerator, denominator) = decoder.decodeSerializableValue(Surrogate.serializer())
        // of() already sign-normalizes n/-d to -n/d and reduces to lowest terms, so the
        // repairable cases need no pre-pass here; it throws on what cannot be repaired.
        return try {
            Rational.of(numerator, denominator)
        } catch (e: ArithmeticException) {
            // A zero denominator (no rational named), or negate(Long.MIN_VALUE) during
            // sign-normalization (no representable canonical form).
            throw SerializationException("Not a valid Rational: $numerator/$denominator", e)
        }
    }
}
