@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import us.tractat.kuilt.otel.tap.TapCbor
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [TapAdmitMessage.Challenge] against the fixed-width wire contract (#1822).
 *
 * The third of the three independent nonce sites, and the one that makes the class a class rather
 * than a duplicated mistake: a different module, a different serializer, a different hand — and the
 * same documented-but-unenforced width (#1820, fixed in #1867).
 *
 * It is also the only in-tree harness whose encoding is **length-delimited**, so it is the worked
 * example of the rig contract in [ExactWidthField]: the wrong-width frame is *re-encoded*, never
 * truncated. [theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth] is the receipt.
 */
class TapAdmitChallengeWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = TapAdmitMessage.decode(frame)

    /**
     * [TapAdmitMessage.decode] refuses by returning `null` — it is called from `TokenGatedSeam`'s
     * frame pump, which reads `null` as "not ours, or garbage: do not answer". A throw escaping it
     * would end that pump.
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.ReturningNull

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("nonce", TapAdmitMessage.Challenge.NONCE_BYTES) { width -> challengeWithNonceWidth(width) },
    )

    /**
     * The admit frame's leading [TapAdmitMessage.PREFIX_BYTE] is a one-byte discriminator, not a
     * header over an arbitrary payload: what follows it must be a whole CBOR message, so
     * `[prefix][x]` is refused. Its short side — an empty frame — is [TapAdmitMessage.isAdmitFrame]'s
     * own `isNotEmpty` check, covered in `TapAdmitMessageTest`.
     */
    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "the PREFIX_BYTE is a frame-family discriminator followed by a whole CBOR message, not a " +
                "header over an arbitrary payload: a frame one byte past the prefix is refused for " +
                "being unparseable CBOR, so there is no header region whose long side must be accepted",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /**
     * The rig is sound: at the declared width it produces the bytes the **real** encoder produces.
     *
     * Everything the suite concludes about a wrong-width frame rests on the surrogate below being
     * the same wire message with a different nonce width, and not merely something CBOR happens to
     * refuse. A `@SerialName` that drifts, a renamed field, a different `Cbor` configuration — each
     * would leave the suite green while testing an envelope `TapAdmitMessage` never emits. Byte
     * equality at the one width both encoders can express is what rules that out.
     */
    @Test
    fun theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth() {
        val width = TapAdmitMessage.Challenge.NONCE_BYTES
        val real = TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(width))))
        assertContentEquals(
            real,
            challengeWithNonceWidth(width),
            "the surrogate encoder must emit the same frame TapAdmitMessage.encode does, or every " +
                "wrong-width rejection below is a rejection of an envelope this codec never sees",
        )
    }

    /**
     * A real admit frame carrying a [width]-byte challenge nonce.
     *
     * [TapAdmitMessage.Challenge]'s constructor refuses every width but the declared one — that is
     * the invariant under test — so the frame is built through a surrogate that is not
     * width-constrained but is otherwise the same message: the same `@SerialName`, the same field
     * name, the same [TapCbor]. kotlinx-serialization therefore emits a correct CBOR byte-string
     * header for [width], which is what makes this a re-encode rather than a truncation.
     *
     * Truncating the real frame's bytes instead would leave the byte string's length header
     * claiming 16 bytes over 15, and CBOR would refuse the frame for a short read — green with the
     * `init { require }` deleted, which is the very defect this suite exists to catch, reappearing
     * inside the rig.
     */
    private fun challengeWithNonceWidth(width: Int): ByteArray {
        val cbor = TapCbor.encodeToByteArray<UnconstrainedAdmitMessage>(
            UnconstrainedAdmitMessage.Challenge(ByteArray(width)),
        )
        return ByteArray(cbor.size + 1).also {
            it[0] = TapAdmitMessage.PREFIX_BYTE
            cbor.copyInto(it, destinationOffset = 1)
        }
    }
}

/**
 * A width-unconstrained mirror of [TapAdmitMessage], used only to put a wrong-width nonce on the
 * wire. Only the arm the suite needs is mirrored: the polymorphic discriminator comes from the
 * subclass's `@SerialName`, so a one-armed hierarchy encodes identically to the real three-armed
 * one — asserted, not assumed, by
 * [TapAdmitChallengeWireCodecTest.theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth].
 */
@Serializable
private sealed interface UnconstrainedAdmitMessage {

    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonce: ByteArray) : UnconstrainedAdmitMessage {
        override fun equals(other: Any?): Boolean = other is Challenge && nonce.contentEquals(other.nonce)
        override fun hashCode(): Int = nonce.contentHashCode()
    }
}
