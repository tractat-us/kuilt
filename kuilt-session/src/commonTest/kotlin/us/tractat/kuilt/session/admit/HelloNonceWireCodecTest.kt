@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import us.tractat.kuilt.identity.DidKey
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [AdmitMessage.Hello.joinerNonce] against the fixed-width wire contract (#1822, #3210).
 *
 * The joiner nonce is what the host's signed Challenge echoes back, so it is a MAC input: a
 * wrong-width one is refused, never padded or cut. The check sits in the Hello's `init`, so it holds
 * on every decode path; this suite is the regression lock on it, and [AdmitMessage.decode] is what
 * turns the constructor's throw into the `null` its caller expects.
 *
 * The admit encoding is CBOR, which is length-delimited, so a wrong-width frame is **re-encoded**
 * through a width-unconstrained surrogate rather than truncated, as in
 * `TapAdmitChallengeWireCodecTest`. [theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth] is the
 * receipt that the surrogate is the same message.
 */
class HelloNonceWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = AdmitMessage.decode(frame)

    /**
     * [AdmitMessage.decode] refuses by returning `null`. `SeamRoom` calls it for every admit frame off
     * the room's receive loop, and reads `null` as "malformed, ignore".
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.ReturningNull

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("joinerNonce", AdmitMessage.Hello.JOINER_NONCE_SIZE) { width -> helloWithNonceWidth(width) },
    )

    /**
     * The admit frame's leading [AdmitMessage.PREFIX_BYTE] is a frame-family discriminator followed by
     * a whole CBOR message, not a header over an arbitrary payload.
     */
    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "the PREFIX_BYTE is a frame-family discriminator followed by a whole CBOR message, not a " +
                "header over an arbitrary payload: a frame one byte past the prefix is refused for " +
                "being unparseable CBOR, so there is no header region whose long side must be accepted",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /**
     * At the declared width the surrogate writes exactly the bytes the real encoder writes. Without
     * this, a drifted `@SerialName`, a renamed field or a different `Cbor` setting would leave the
     * suite refusing an envelope [AdmitMessage] never emits.
     */
    @Test
    fun theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth() = runTest {
        val width = AdmitMessage.Hello.JOINER_NONCE_SIZE
        val real = AdmitMessage.encode(
            AdmitMessage.Hello(
                displayName = DISPLAY_NAME,
                sessionId = SESSION_ID,
                protocolVersion = ProtocolVersion.CURRENT,
                key = DidKey.parse(KEY),
                joinerNonce = ByteArray(width),
            ),
        )
        assertContentEquals(
            real,
            helloWithNonceWidth(width),
            "the surrogate must emit the frame AdmitMessage.encode does, or every wrong-width " +
                "rejection below is a rejection of an envelope this codec never sees",
        )
    }

    /**
     * A real admit frame carrying a [width]-byte joiner nonce, through a surrogate that is the same
     * message without the width check: the same `@SerialName`, the same field names in the same
     * order, the same `Cbor` settings. kotlinx-serialization then writes a correct byte-string header
     * for [width], so the frame is well-formed in every respect but the nonce's width.
     */
    private fun helloWithNonceWidth(width: Int): ByteArray {
        val body = surrogateCbor.encodeToByteArray<UnconstrainedAdmitMessage>(
            UnconstrainedAdmitMessage.Hello(
                displayName = DISPLAY_NAME,
                sessionId = SESSION_ID,
                protocolVersion = ProtocolVersion.CURRENT,
                key = KEY,
                joinerNonce = ByteArray(width),
            ),
        )
        return byteArrayOf(AdmitMessage.PREFIX_BYTE) + body
    }

    private companion object {
        const val DISPLAY_NAME = "Ada"
        const val SESSION_ID = "s-1"

        /** A P-256 `did:key` from the W3C did:key test vectors. */
        const val KEY = "did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv"

        /** The admit codec's settings, mirrored: the byte-identity receipt fails if they drift apart. */
        val surrogateCbor = Cbor {
            ignoreUnknownKeys = true
            alwaysUseByteString = true
        }
    }
}

/**
 * A width-unconstrained mirror of [AdmitMessage], used only to put a wrong-width nonce on the wire.
 * Only the `hello` arm is mirrored: the polymorphic discriminator comes from `@SerialName`, so a
 * one-armed hierarchy encodes as the real one does — asserted by
 * [HelloNonceWireCodecTest.theRigIsByteIdenticalToTheRealEncoderAtTheDeclaredWidth].
 */
@Serializable
private sealed interface UnconstrainedAdmitMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val displayName: String,
        val sessionId: String,
        val deviceId: String? = null,
        val targetRoom: String? = null,
        val protocolVersion: Int? = null,
        val key: String? = null,
        val joinerNonce: ByteArray? = null,
    ) : UnconstrainedAdmitMessage {
        override fun equals(other: Any?): Boolean = other is Hello && joinerNonce.contentEquals(other.joinerNonce)
        override fun hashCode(): Int = joinerNonce.contentHashCode()
    }
}
