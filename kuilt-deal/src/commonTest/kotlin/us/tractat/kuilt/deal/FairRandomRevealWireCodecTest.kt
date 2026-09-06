@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [FairRandomMessage.Reveal] against the fixed-width wire contract (#1822, instance four — #2650).
 *
 * Two fields, not one, and that is the point of declaring both: `secret` and `nonce` are the two
 * halves of a single unframed preimage. The commitment is `SHA-256(secret ‖ nonce)` with no length
 * delimiter between them, so a peer who is free to choose the widths can commit to a 48-byte string
 * and later re-split it — revealing a 33-byte `secret` that hashes to the very commitment it
 * published, while contributing a *different* secret to the derived seed. Fixing both widths is
 * what makes the preimage unambiguous; fixing one of them would leave the other free to move.
 *
 * The encoding is length-delimited (CBOR), so the rig **re-encodes** through a width-unconstrained
 * surrogate rather than truncating a real frame — the contract [ExactWidthField] states, and
 * [theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidths] is the receipt.
 */
class FairRandomRevealWireCodecTest : WireCodecConformanceSuite() {

    /**
     * The exact call `FairRandom.roll()`'s collector makes on a peer's bytes:
     * `swatch.decode(Cbor, FairRandomMessage.serializer())` is
     * [Cbor.decodeFromByteArray] over the default `Cbor` and the sealed hierarchy's own
     * serializer. Pointed here rather than at `roll()` because `roll()` is where the *frame* is
     * disposed of; the invariant under test belongs to the codec.
     */
    override fun decode(frame: ByteArray): Any? = Cbor.decodeFromByteArray(FairRandomMessage.serializer(), frame)

    /**
     * The codec refuses by throwing, and `roll()`'s frame path is written to expect that.
     *
     * Read the path rather than assumed: the collector wraps the decode in
     * `try { … } catch (e: CancellationException) { throw e } catch (_: IllegalArgumentException) { return@collect }`,
     * so both refusal shapes this codec can produce — `SerializationException` for bytes CBOR
     * cannot parse, and the wire type's own `init { require }` for values it will not hold — arrive
     * as an `IllegalArgumentException` the caller catches and drops. (`SerializationException`
     * *is* an `IllegalArgumentException`, so one arm covers both; measured, not assumed.) The
     * decoder itself never returns `null`, which is what makes [WireRejectionMode.Throwing] the
     * honest declaration.
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("secret", FairRandomMessage.Reveal.SECRET_BYTES) { width ->
            unconstrainedRevealFrame(ByteArray(width), ByteArray(FairRandomMessage.Reveal.NONCE_BYTES))
        },
        ExactWidthField("nonce", FairRandomMessage.Reveal.NONCE_BYTES) { width ->
            unconstrainedRevealFrame(ByteArray(FairRandomMessage.Reveal.SECRET_BYTES), ByteArray(width))
        },
    )

    /**
     * A `FairRandomMessage` frame is a bare CBOR document — a polymorphic envelope whose first
     * element is the serial name — with no framing prefix or length header of its own ahead of it.
     * There is no header region whose long side a decoder must accept, so there is nothing to
     * declare: a frame one byte past any prefix of that envelope is refused for being unparseable
     * CBOR, not for violating a header width.
     */
    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "a FairRandomMessage frame is a bare CBOR polymorphic envelope with no framing prefix " +
                "and no length header of its own: every byte of it is message, so there is no " +
                "fixed-width header region followed by an arbitrary payload to construct",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /**
     * The rig is sound: at the declared widths it produces the bytes the **real** encoder produces.
     *
     * Everything this suite concludes about a wrong-width frame rests on the surrogate below being
     * the same wire message with different field widths, and not merely something the real
     * serializer happens to refuse. The polymorphic discriminator is the fragile part — it is the
     * subclass's serial name, which for [FairRandomMessage.Reveal] defaults to its fully-qualified
     * Kotlin name, so a package move, a class rename, or a `@SerialName` added later would leave
     * every rejection below a rejection of an envelope `FairRandom` never emits, with the suite
     * still green. Byte equality at the one width pair both encoders can express rules that out.
     */
    @Test
    fun theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidths() {
        val secret = ByteArray(FairRandomMessage.Reveal.SECRET_BYTES)
        val nonce = ByteArray(FairRandomMessage.Reveal.NONCE_BYTES)
        assertContentEquals(
            Cbor.encodeToByteArray<FairRandomMessage>(FairRandomMessage.Reveal(secret, nonce)),
            unconstrainedRevealFrame(secret, nonce),
            "the surrogate encoder must emit the frame FairRandom itself broadcasts, or every " +
                "wrong-width rejection above is a rejection of an envelope this codec never sees",
        )
    }
}

/**
 * A `FairRandomMessage.Reveal` frame carrying fields of arbitrary width.
 *
 * [FairRandomMessage.Reveal]'s constructor refuses every width but the declared ones — that is the
 * invariant under test — and `Cbor` calls that constructor on the way *out* as well as in, so the
 * real encoder cannot express a malformed frame at all. A real attacker is not running our encoder;
 * this is how a test stops running it either.
 *
 * Re-encoding rather than truncating is what makes the frame well-formed in every respect except
 * the width: kotlinx-serialization emits a correct CBOR length for whatever it is handed, where
 * cutting bytes out of a real frame would leave a length claiming more than follows and the parser
 * would refuse it for a short read — green with the `init { require }` deleted, which is the very
 * defect this rig exists to catch, reappearing one level up inside the rig.
 */
internal fun unconstrainedRevealFrame(secret: ByteArray, nonce: ByteArray): ByteArray =
    Cbor.encodeToByteArray<UnconstrainedFairRandomMessage>(
        UnconstrainedFairRandomMessage.Reveal(secret, nonce),
    )

/**
 * A width-unconstrained mirror of [FairRandomMessage], used only to put a wrong-width reveal on the
 * wire. Only the arm the tests need is mirrored: the polymorphic discriminator comes from the
 * subclass's serial name, so a one-armed hierarchy encodes identically to the real two-armed one —
 * asserted, not assumed, by
 * [FairRandomRevealWireCodecTest.theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidths].
 *
 * The `@SerialName` is spelled out because [FairRandomMessage.Reveal] takes the default — its
 * fully-qualified name — and a surrogate in a different hierarchy would otherwise take its own.
 */
@Serializable
internal sealed class UnconstrainedFairRandomMessage {

    @Serializable
    @SerialName("us.tractat.kuilt.deal.FairRandomMessage.Reveal")
    internal data class Reveal(val secret: ByteArray, val nonce: ByteArray) : UnconstrainedFairRandomMessage() {
        override fun equals(other: Any?): Boolean =
            other is Reveal && secret.contentEquals(other.secret) && nonce.contentEquals(other.nonce)

        override fun hashCode(): Int = 31 * secret.contentHashCode() + nonce.contentHashCode()
    }
}
