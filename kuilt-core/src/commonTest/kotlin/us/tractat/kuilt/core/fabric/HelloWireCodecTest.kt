package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [Hello] against the fixed-width wire contract (#1822, #2894).
 *
 * `HelloTest` covers the round trip and each named refusal; this states the width property in the
 * form a sibling wire inherits.
 *
 * ## Why only `idLen` is declared, not the magic and the version
 *
 * The v1 header is magic (4), version (1), `idLen` (4). All three are fixed-width, but only `idLen`
 * has a width property a reverted check can expose. Measured on #2894 by declaring all three and
 * removing one decoder check at a time:
 *
 * - **Magic check removed**: this suite stayed green (11/11). A 3- or 5-byte magic shifts the
 *   version byte, so the frame is refused by the *version* check instead.
 * - **Version check removed**: green (11/11). A 0- or 2-byte version shifts `idLen`, so the frame
 *   is refused by the length-agreement check.
 * - **Strict length agreement relaxed to `idLen <= remaining`**: red, 1 of 11
 *   ([everyExactWidthFieldIsRejectedOneByteLong], the `idLen` row). A 5-byte `idLen` reads as 0,
 *   and a lenient decoder accepts the body. Against this final, `idLen`-only suite the same
 *   mutation reds the same arm, 1 of 12; the magic and version removals stay green, 12 of 12.
 *
 * So a magic or version row would be refused for its content, never its width. The suite KDoc
 * names that as the vacuous rig: an arm that stays green whichever check is removed. Those refusals
 * are pinned by name in `HelloTest`.
 *
 * There is no wire *type* to put an `init { require(...) }` on: [Hello] takes a [PeerId] and writes
 * every fixed-width field from a constant, so no caller can hand it a wrong-width field. The width
 * property lives entirely in [Hello.decode], which is what this suite exercises.
 */
class HelloWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = Hello.decode(frame)

    /** [Hello.decode] throws a named [HelloFormatException]; `handshaking` lets it propagate. */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("idLen", HELLO_ID_LENGTH_BYTES) { width -> bodyWithIdLengthWidth(width) },
    )

    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "a Hello body is a fixed header and then an id that must be non-empty and exactly idLen " +
                "bytes long. So a body of exactly the header width is refused for its empty id. There " +
                "is no header-then-arbitrary-payload region. HelloTest pins truncation at every prefix " +
                "of the header",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /** The rig's receipt: at the declared width it is byte-identical to the real encoder. */
    @Test
    fun theRigAtTheDeclaredWidthIsTheRealEncoding() {
        assertContentEquals(Hello.encode(PeerId(ID)), bodyWithIdLengthWidth(HELLO_ID_LENGTH_BYTES))
    }

    /**
     * Magic and version as the encoder writes them, the TRUE id length written big-endian into
     * [width] bytes, then the id. Byte surgery is sound here because the layout is positional. Only
     * the `idLen` field's width changes; its value and every other byte stay the same.
     */
    private fun bodyWithIdLengthWidth(width: Int): ByteArray {
        val id = ID.encodeToByteArray()
        val idLen = ByteArray(width).also { it.writeUnsignedBe(id.size.toLong(), offset = 0, width = width) }
        return helloBody(id = ID).copyOf(HELLO_ID_LENGTH_OFFSET) + idLen + id
    }

    private companion object {
        const val ID = "peer-1"
    }
}
