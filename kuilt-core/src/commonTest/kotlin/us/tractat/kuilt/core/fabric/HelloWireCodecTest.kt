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
 * ## Why `flags` and `idLen` are declared, and the magic and version are not
 *
 * The v1 header is magic (4), version (1), flags (2), `idLen` (2). All four are fixed-width, but a
 * reverted check can expose a width property only on the last two. Measured on #2894 by removing
 * one decoder check at a time:
 *
 * - **Magic check removed**: this suite stays green (`tests=12 failures=0`). A wrong-width magic
 *   shifts the version byte, so the frame is refused by the version check instead. An earlier draft
 *   declared magic and version rows too, and they stayed green under their own check's removal.
 * - **Version check removed**: green. A wrong-width version shifts the next fields, and the
 *   length-agreement check refuses the frame.
 * - **Strict length agreement relaxed to `idLen <= remaining`**: red, `tests=12 failures=1`. The
 *   failing arm is [everyExactWidthFieldIsRejectedOneByteLong], with both rows red: a 3-byte flags
 *   or a 3-byte `idLen` makes `idLen` read as 0, and a lenient decoder accepts the body. The short
 *   and zero-width arms stay green under that mutation, because a narrower field makes `idLen` read
 *   huge.
 *
 * So magic and version rows would be refused for their content, never their width, which is the
 * vacuous rig the suite's KDoc warns about. `HelloTest` pins those refusals by name. Flags have no
 * content check at all (v1 ignores them), so their width is guarded only by length agreement, and
 * that is what this suite locks.
 *
 * There is no wire *type* to put an `init { require(...) }` on: [Hello] takes a [PeerId] and writes
 * every fixed-width field from a constant, so no caller can hand it a wrong-width field. The width
 * property lives entirely in [Hello.decode], which is what this suite exercises.
 */
class HelloWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = Hello.decode(frame)

    /** [Hello.decode] throws a named [HelloFormatException]; `handshaking` closes the link and rethrows. */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("flags", HELLO_FLAGS_BYTES) { width -> bodyWithFlagsWidth(width) },
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

    /** The rigs' receipt: at the declared widths both are byte-identical to the real encoder. */
    @Test
    fun theRigsAtTheDeclaredWidthsAreTheRealEncoding() {
        val real = Hello.encode(PeerId(ID))
        assertContentEquals(real, bodyWithFlagsWidth(HELLO_FLAGS_BYTES), "flags rig")
        assertContentEquals(real, bodyWithIdLengthWidth(HELLO_ID_LENGTH_BYTES), "idLen rig")
    }

    /**
     * Magic and version as the encoder writes them, [width] zero bytes of flags (the v1 value), then
     * `idLen` and the id. Byte surgery is sound because the layout is positional: only the flags
     * field's width changes.
     */
    private fun bodyWithFlagsWidth(width: Int): ByteArray {
        val real = helloBody(id = ID)
        return real.copyOf(HELLO_FLAGS_OFFSET) + ByteArray(width) + real.copyOfRange(HELLO_ID_LENGTH_OFFSET, real.size)
    }

    /** As [bodyWithFlagsWidth], but the TRUE id length is written big-endian into [width] bytes. */
    private fun bodyWithIdLengthWidth(width: Int): ByteArray {
        val id = ID.encodeToByteArray()
        val idLen = ByteArray(width).also { it.writeUnsignedBe(id.size.toLong(), offset = 0, width = width) }
        return helloBody(id = ID).copyOf(HELLO_ID_LENGTH_OFFSET) + idLen + id
    }

    private companion object {
        const val ID = "peer-1"
    }
}
