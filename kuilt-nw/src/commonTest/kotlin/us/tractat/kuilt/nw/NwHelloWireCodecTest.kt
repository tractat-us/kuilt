package us.tractat.kuilt.nw

import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode
import us.tractat.kuilt.core.PeerId

/**
 * [NwHello] against the fixed-width wire contract (#1822).
 *
 * The sibling of `MeshHelloWireCodecTest`: `NwHello` is a port of `MeshHello`, and #1812 was the
 * same unenforced nonce width in both. Two ports sharing a defect is exactly the case a shared
 * property is for — the next port inherits the test rather than the omission.
 */
class NwHelloWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = NwHello.decode(frame)

    /**
     * [NwHello.decode] refuses by throwing; `NwSeam.processFrame` treats a decode failure on an
     * unresolved conn as "tear this connection".
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing

    override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun exactWidthFields(): List<ExactWidthField> = listOf(
        ExactWidthField("nonce", NONCE_BYTES) { width -> helloBodyWithNonceWidth(width) },
    )

    /** See `MeshHelloWireCodecTest` — the two bodies have the same shape and the same reason. */
    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "an NwHello body is [version][idLen_be32][id][nonce] — every field after the version " +
                "byte is mandatory, so there is no header-then-arbitrary-payload region here. The " +
                "frame type byte one layer out belongs to NwWire",
        )

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()

    /**
     * `[version][idLen_be32][id][nonce×width]`, hand-assembled because [NwHello.encode] refuses
     * every width but the declared one — which is the invariant under test. Sound as byte surgery
     * because the nonce runs to the end of a self-describing body; see `MeshHelloWireCodecTest`.
     */
    private fun helloBodyWithNonceWidth(width: Int): ByteArray {
        val id = PeerId("peer-1").value.encodeToByteArray()
        val body = ByteArray(VERSION_BYTES + Int.SIZE_BYTES + id.size + width)
        body[0] = NW_WIRE_VERSION.toByte()
        body[1] = (id.size ushr 24).toByte()
        body[2] = (id.size ushr 16).toByte()
        body[3] = (id.size ushr 8).toByte()
        body[4] = id.size.toByte()
        id.copyInto(body, destinationOffset = VERSION_BYTES + Int.SIZE_BYTES)
        return body
    }
}
