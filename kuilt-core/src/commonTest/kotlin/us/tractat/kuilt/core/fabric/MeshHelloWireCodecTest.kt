package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import us.tractat.kuilt.core.PeerId

/**
 * [MeshHello] against the fixed-width wire contract (#1822).
 *
 * `MeshHelloTest` already covers this codec's round trip and its named malformed-preamble
 * rejections; what it cannot do is state the property as one a *sibling* fabric inherits. #1812 was
 * the same unenforced nonce width here, in `:kuilt-nw`, and in `:kuilt-otel-tap` — three modules,
 * three serializers, one of them an independent re-derivation. This is the shared statement.
 */
class MeshHelloWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = MeshHello.decode(frame)

    /**
     * [MeshHello.decode] refuses by throwing a named [MeshWireFormatException]. Its callers are
     * written for that: `MeshSeam`'s handshake routes an `IllegalArgumentException` out of a body
     * decode through its malformed-preamble path, and the *type* is what tells a version break apart
     * from data corruption.
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("nonce", MESH_NONCE_BYTES) { width -> helloBodyWithNonceWidth(width) },
    )

    /**
     * The body's leading wire-version byte is a fixed-width *prefix*, but not a header in this
     * suite's sense: what follows it is not an arbitrary payload but three more mandatory fields, so
     * a body one byte past the version is still rejected — for lacking the id-length prefix, not for
     * a width violation. Declaring it here would assert `[version][x]` is accepted, which is false
     * and should stay false.
     */
    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "a MeshHello body is [version][idLen_be32][id][nonce] — every field after the version " +
                "byte is mandatory, so there is no header-then-arbitrary-payload region here. The " +
                "version byte's own truncation rejection is covered by MeshHelloTest, and the frame " +
                "type byte one layer out belongs to MeshWire",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /**
     * `[version][idLen_be32][id][nonce×width]`, hand-assembled because [MeshHello.encode] refuses
     * every width but the declared one — which is the invariant under test.
     *
     * Byte surgery is sound *here* and would not be for a length-delimited encoding: the nonce runs
     * to the end of a self-describing body, so changing its width leaves every other field's bytes
     * and the id-length prefix exactly right. Nothing but the nonce's width differs between widths.
     */
    private fun helloBodyWithNonceWidth(width: Int): ByteArray {
        val id = PeerId("peer-1").value.encodeToByteArray()
        val body = ByteArray(1 + Int.SIZE_BYTES + id.size + width)
        body[0] = MESH_WIRE_VERSION.toByte()
        body[1] = (id.size ushr 24).toByte()
        body[2] = (id.size ushr 16).toByte()
        body[3] = (id.size ushr 8).toByte()
        body[4] = id.size.toByte()
        id.copyInto(body, destinationOffset = 1 + Int.SIZE_BYTES)
        // The nonce region is left zero-filled: an all-zero nonce is a legal value at the declared
        // width, so nothing in this rig can make a frame fail for its CONTENT rather than its width.
        return body
    }
}
