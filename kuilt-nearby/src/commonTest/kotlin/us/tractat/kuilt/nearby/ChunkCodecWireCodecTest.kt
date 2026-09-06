package us.tractat.kuilt.nearby

import us.tractat.kuilt.conformance.ExactWidthField
import us.tractat.kuilt.conformance.FixedWidthHeader
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.WireCodecConformanceSuite
import us.tractat.kuilt.conformance.WireRejectionMode

/**
 * [ChunkCodec]'s header against the fixed-width wire contract (#1822).
 *
 * The one in-tree codec whose documented width is a **header** rather than a field, and the reason
 * the suite carries two obligations instead of one. Its short side is a genuine width violation —
 * a frame that cannot hold the header cannot be classified at all — while its long side is a chunk
 * with a one-byte payload and must be accepted.
 */
class ChunkCodecWireCodecTest : WireCodecConformanceSuite() {

    override fun decode(frame: ByteArray): Any? = ChunkCodec.decodeChunk(frame)

    /**
     * The declaration that carries the most weight in this file.
     *
     * [ChunkCodec.decodeChunk] is called from `NearbySeam`'s receive pump, so a throw is not a
     * refusal — it is #1819 exactly: an escaping exception killed the pump and left the seam
     * permanently deaf, with no `Torn` for a consumer to observe. Declaring `ReturningNull` makes
     * the suite score a throw as a **failure**, which is what keeps the frame-size guard in
     * `decodeChunk` from being deleted in favour of "the caller will catch it".
     */
    override fun rejectionMode(): WireRejectionMode = WireRejectionMode.ReturningNull

    /**
     * `chunkCount` is 2 bytes at offset 6, and what follows it is a variable-length payload.
     *
     * So the long side is unconstructible in the strict sense the suite means: a frame carrying a
     * 3-byte `chunkCount` is byte-identical to a well-formed frame with one extra payload byte, and
     * no rig can present the decoder with a difference. `msgId` and `chunkIndex` are the same shape
     * one and two fields earlier.
     *
     * The constraint `chunkCount` actually carries is on its **value** — `> 0`, `chunkIndex <
     * chunkCount`, and agreement across the chunks of one message (#1819, the defect that made a
     * `NearbySeam` permanently deaf from 16 bytes). `ChunkCodecTest` holds those; no arm of this
     * suite reaches them, and dressing a value constraint up as a width one would put a green here
     * that means nothing.
     */
    override fun exactWidthDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "every ChunkCodec header field sits at a fixed offset in front of a variable-length " +
                "payload, so a frame carrying one of them a byte wider is byte-identical to a " +
                "well-formed frame with one extra payload byte — the long side cannot be built. The " +
                "documented constraint on chunkCount is on its value (#1819), not its width, and this " +
                "suite does not reach value ranges",
        )

    override fun exactWidthFields(): List<ExactWidthField> = emptyList()

    override fun fixedWidthHeaderDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    override fun fixedWidthHeaders(): List<FixedWidthHeader> = listOf(
        FixedWidthHeader("ChunkCodec.HEADER_SIZE", ChunkCodec.HEADER_SIZE) { size -> chunkOfSize(size) },
    )

    /**
     * A single-chunk frame of exactly [size] bytes: a well-formed header, truncated when [size] is
     * short of one and payload-extended when it is over.
     *
     * Truncation is the right mutation for a header and the wrong one for a length-delimited field:
     * a header has no length prefix of its own to leave inconsistent, so a short frame differs from
     * a whole one in exactly the way a peer's short frame does.
     */
    private fun chunkOfSize(size: Int): ByteArray {
        val whole = ChunkCodec.encode(ByteArray(size), msgId = 1).single()
        return whole.copyOf(size)
    }
}
