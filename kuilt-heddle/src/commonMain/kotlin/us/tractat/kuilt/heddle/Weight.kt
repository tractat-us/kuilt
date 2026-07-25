package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
 * A `Weight` crosses the wire inside [AttachmentRecord] — replicated in the
 * [EntitlementLedger] and carried by the Raft `Prepare` command — so the **read path is
 * a second construction path** and enforces the same invariant: [WeightSerializer]
 * routes every decoded pair through [of] rather than into the constructor, and a
 * denormalized encoding (`2/4`, `-1/-2`) is *repaired* to its canonical form rather
 * than admitted. See [WeightSerializer] for why repair, not rejection.
 *
 * @property numerator the ratio's numerator; always `> 0` and coprime with [denominator].
 * @property denominator the ratio's denominator; always `> 0` and coprime with [numerator].
 * @sample us.tractat.kuilt.heddle.sampleWeightOrdering
 */
@Serializable(with = WeightSerializer::class)
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

/**
 * Decodes a [Weight] **through [Weight.of]**, so a value that arrived over the wire is
 * indistinguishable from one the factory built (#1647).
 *
 * The plugin-generated serializer would deserialize straight into [Weight]'s primary
 * constructor, skipping the only invariant-enforcing path. An unreduced `2/4` would then
 * be structurally unequal to `1/2`, and a negative denominator would invert
 * [Weight.compareTo] — whose cross-multiplication assumes a positive denominator — so a
 * corrupt or hostile peer could **flip sibling ordering cluster-wide** by encoding `1/2`
 * as `-1/-2`.
 *
 * **Repair where a canonical form exists; refuse only where none does.** Both decode
 * boundaries that carry a `Weight` — [us.tractat.kuilt.quilter.Quilter]'s delta dispatch
 * and the heddle control plane's `applyEntry` — already swallow a decode failure and drop
 * the **entire frame**. Rejecting a merely denormalized encoding would therefore discard
 * every legitimate record travelling with it, and anti-entropy would re-send the same
 * frame forever: a silent, permanent convergence wedge between two peers over a
 * difference with no semantic content. Sign-normalization and reduction have a unique
 * correct answer, so they are applied.
 *
 * Values that name no weight at all — a zero or negative share, a zero denominator — are
 * a different case: there is nothing to repair to, and substituting a default would
 * fabricate a fairness claim (a conservation-relevant lie) out of hostile input. Those
 * throw [SerializationException] at the decode boundary, before any lattice state is
 * touched; the frame is dropped and the merge loop survives.
 */
public object WeightSerializer : KSerializer<Weight> {

    /**
     * Field-for-field identical to the generated form, including the serial name, so the
     * change is wire-compatible with already-persisted and in-flight frames.
     */
    @Serializable
    @SerialName("us.tractat.kuilt.heddle.Weight")
    private data class Surrogate(val numerator: Long, val denominator: Long)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Weight) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(value.numerator, value.denominator),
        )
    }

    override fun deserialize(decoder: Decoder): Weight {
        val (numerator, denominator) = decoder.decodeSerializableValue(Surrogate.serializer())
        // n/-d is the same ratio as -n/d, so a negative denominator is a repairable
        // encoding, not a different value; of() then rejects what remains invalid and
        // reduces to lowest terms.
        val signNormalized = denominator < 0L
        return try {
            if (signNormalized) {
                Weight.of(checkedNegate(numerator), checkedNegate(denominator))
            } else {
                Weight.of(numerator, denominator)
            }
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Not a valid Weight: $numerator/$denominator", e)
        } catch (e: ArithmeticException) {
            // checkedNegate(Long.MIN_VALUE) — no representable normalization.
            throw SerializationException("Not a valid Weight: $numerator/$denominator", e)
        }
    }
}
