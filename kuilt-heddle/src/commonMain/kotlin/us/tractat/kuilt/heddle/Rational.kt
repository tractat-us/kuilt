package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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

    /**
     * The **exact ceiling** — the least `Long` that is `>= this`. `7/2` ceils to `4`,
     * `-7/2` to `-3`, and a whole number to itself.
     *
     * This is the only sanctioned way to land an exact virtual time on a `Long` field
     * (see [AttachmentRecord.neutralInitialVirtualTime]). Note that Kotlin's `/` on `Long`
     * truncates toward zero — for the non-negative virtual times the scheduler deals in
     * that is a *floor*, which rounds the wrong way for fairness.
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
 * Both decode boundaries that carry this state — `Quilter`'s delta dispatch and the heddle
 * control plane's `applyEntry` — swallow a decode failure and drop the **entire frame**.
 * Rejecting a merely denormalized encoding would therefore discard every legitimate record
 * travelling with it, and anti-entropy would re-send the same frame forever: a silent,
 * permanent convergence wedge between two peers over a difference with no semantic content.
 * Sign-normalization and reduction have a unique correct answer, so they are applied —
 * [Rational.of] does both.
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
